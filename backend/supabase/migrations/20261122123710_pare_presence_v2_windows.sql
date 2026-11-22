-- Presence RPC v2 adds Windows while keeping installed strict v1 clients compatible.
-- The response envelope remains version 1. Only the allowed platform labels differ.
-- Both endpoints retain the same auth/session checks, per-owner advisory lock, limits,
-- sequence rules, retention, private RLS table, UTC/search_path and explicit ACLs.
begin;
set local lock_timeout = '5s';

alter table pare_sync.presence_devices drop constraint presence_devices_label_check;
alter table pare_sync.presence_devices add constraint presence_devices_label_check
    check (label in ('Mac', 'Android', 'Windows'));

-- v1 keeps its original request validation and filters unsupported response labels.
create or replace function public.pare_presence_exchange(
    p_device_id uuid,
    p_sequence bigint,
    p_label text,
    p_runs jsonb
) returns jsonb
language plpgsql
security definer
set search_path = ''
set timezone = 'UTC'
as $$
declare
    v_owner uuid;
    v_claims jsonb;
    v_session_text text;
    v_session uuid;
    v_now timestamptz;
    v_started timestamptz;
    v_run jsonb;
    v_current pare_sync.presence_devices%rowtype;
    v_accepted bigint;
    v_devices jsonb;
begin
    -- Only server-verified identity/session claims participate in authorization.
    -- Malformed UUID claims get a constant auth error, never a cast error echoing input.
    begin
        v_owner := auth.uid();
        v_claims := auth.jwt();
    exception when invalid_text_representation then
        raise exception using errcode = '42501', message = 'Authentication required';
    end;
    v_session_text := v_claims->>'session_id';
    if v_owner is null
        or pg_catalog.jsonb_typeof(v_claims->'session_id') is distinct from 'string'
        or v_session_text is null
        or not (v_session_text collate "C" ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
        or not exists (select 1 from auth.users u where u.id = v_owner) then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    v_session := v_session_text::uuid;

    if p_device_id is null or p_sequence is null or p_sequence not between 1 and 9007199254740991
        or p_label is null or p_label not in ('Mac', 'Android') then
        raise exception using errcode = '22023', message = 'Invalid presence device, sequence or label';
    end if;
    if pg_catalog.jsonb_typeof(p_runs) is distinct from 'array' then
        raise exception using errcode = '22023', message = 'Presence runs must be an array';
    end if;
    if pg_catalog.jsonb_array_length(p_runs) > 32 or pg_catalog.octet_length(p_runs::text) > 32768 then
        raise exception using errcode = '22023', message = 'Presence count or size exceeds v1 limits';
    end if;
    for v_run in select value from pg_catalog.jsonb_array_elements(p_runs) loop
        if pg_catalog.jsonb_typeof(v_run) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'Each presence run must be an object';
        end if;
        if not (v_run ?& array['run_id','task_id','kind','state','started_at','elapsed_seconds'])
            or v_run - array['run_id','task_id','kind','state','started_at','elapsed_seconds','target_seconds'] <> '{}'::jsonb then
            raise exception using errcode = '22023', message = 'Unexpected or missing presence fields';
        end if;
        if pg_catalog.jsonb_typeof(v_run->'run_id') is distinct from 'string'
            or pg_catalog.jsonb_typeof(v_run->'task_id') is distinct from 'string'
            or not ((v_run->>'run_id') collate "C" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            or not ((v_run->>'task_id') collate "C" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            or pg_catalog.jsonb_typeof(v_run->'kind') is distinct from 'string'
            or v_run->>'kind' not in ('personal','waiting')
            or pg_catalog.jsonb_typeof(v_run->'state') is distinct from 'string'
            or v_run->>'state' not in ('running','paused') then
            raise exception using errcode = '22023', message = 'Invalid presence identity, kind or state';
        end if;
        if pg_catalog.jsonb_typeof(v_run->'elapsed_seconds') is distinct from 'number'
            or (v_run->>'elapsed_seconds') !~ '^(0|[1-9][0-9]{0,7})$' then
            raise exception using errcode = '22023', message = 'Invalid elapsed seconds';
        end if;
        if (v_run->>'elapsed_seconds')::bigint > 31536000 then
            raise exception using errcode = '22023', message = 'Elapsed seconds out of range';
        end if;
        if v_run ? 'target_seconds' and v_run->'target_seconds' <> 'null'::jsonb then
            if pg_catalog.jsonb_typeof(v_run->'target_seconds') is distinct from 'number'
                or (v_run->>'target_seconds') !~ '^[1-9][0-9]{0,7}$' then
                raise exception using errcode = '22023', message = 'Invalid target seconds';
            end if;
            if (v_run->>'target_seconds')::bigint > 31536000 then
                raise exception using errcode = '22023', message = 'Target seconds out of range';
            end if;
        end if;
        -- Explicit timezone is required. No timezone-dependent, relative or non-finite dates.
        if pg_catalog.jsonb_typeof(v_run->'started_at') is distinct from 'string'
            or pg_catalog.length(v_run->>'started_at') > 40
            or not ((v_run->>'started_at') collate "C" ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]([.][0-9]{1,9})?(Z|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$') then
            raise exception using errcode = '22023', message = 'Invalid presence start timestamp';
        end if;
        begin
            v_started := (v_run->>'started_at')::timestamptz;
        exception when data_exception then
            raise exception using errcode = '22023', message = 'Invalid presence start timestamp';
        end;
        if v_started < timestamptz '1970-01-01 00:00:00+00'
            or v_started > timestamptz '9999-12-31 23:59:59+00' then
            raise exception using errcode = '22023', message = 'Presence start timestamp out of range';
        end if;
    end loop;
    if exists (select 1 from pg_catalog.jsonb_array_elements(p_runs) r(value) group by value->>'run_id' having pg_catalog.count(*) > 1)
        or exists (select 1 from pg_catalog.jsonb_array_elements(p_runs) r(value) group by value->>'task_id' having pg_catalog.count(*) > 1)
        or (select pg_catalog.count(*) from pg_catalog.jsonb_array_elements(p_runs) r(value) where value->>'kind' = 'personal') > 1 then
        raise exception using errcode = '22023', message = 'Duplicate presence identity or multiple personal runs';
    end if;

    -- A separate per-owner lock never takes the content stream's row lock or changes its cursor.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('pare-presence-v1:' || v_owner::text, 0));
    v_now := pg_catalog.clock_timestamp();
    -- Match auth deletion's parent-before-child lock order; later FK checks cannot deadlock
    -- with a user deletion waiting for this request's session-row lock.
    perform 1 from auth.users u where u.id = v_owner for key share;
    if not found then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    -- Recheck after waiting for the lock. Holding this session row until commit prevents an already
    -- completed logout from racing authorization; session deletion then waits for this request.
    perform 1 from auth.sessions s where s.id = v_session and s.user_id = v_owner
        and (s.not_after is null or s.not_after > v_now) for key share;
    if not found then
        raise exception using errcode = '42501', message = 'Active session required';
    end if;

    delete from pare_sync.presence_devices where owner_id = v_owner and last_seen < v_now - interval '24 hours';
    select * into v_current from pare_sync.presence_devices where owner_id = v_owner and device_id = p_device_id;
    if found then
        if p_sequence = v_current.sequence and (p_label <> v_current.label or p_runs <> v_current.runs) then
            raise exception using errcode = '22023', message = 'Presence sequence already used for a different request';
        end if;
        if p_sequence > v_current.sequence then
            update pare_sync.presence_devices set sequence = p_sequence, label = p_label, runs = p_runs,
                last_seen = v_now, expires_at = v_now + interval '90 seconds'
                where owner_id = v_owner and device_id = p_device_id;
            v_accepted := p_sequence;
        else
            -- Stale and exact idempotent retries must not renew a lease or restore older runs.
            v_accepted := v_current.sequence;
        end if;
    else
        if (select pg_catalog.count(*) from pare_sync.presence_devices where owner_id = v_owner) >= 32 then
            raise exception using errcode = '22023', message = 'Presence device limit reached';
        end if;
        insert into pare_sync.presence_devices(owner_id, device_id, sequence, label, runs, last_seen, expires_at)
            values (v_owner, p_device_id, p_sequence, p_label, p_runs, v_now, v_now + interval '90 seconds');
        v_accepted := p_sequence;
    end if;

    select coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
        'device_id', device_id, 'sequence', sequence, 'label', label, 'last_seen', last_seen,
        'expires_at', expires_at, 'runs', runs) order by last_seen desc, device_id), '[]'::jsonb)
        into v_devices from pare_sync.presence_devices where owner_id = v_owner and last_seen >= v_now - interval '24 hours'
            and label in ('Mac', 'Android');
    return pg_catalog.jsonb_build_object('version', 1, 'server_now', v_now,
        'accepted_sequence', v_accepted, 'devices', v_devices);
end;
$$;


-- v2 changes only the accepted label set; the reviewed v1 security body is otherwise identical.
create function public.pare_presence_exchange_v2(
    p_device_id uuid,
    p_sequence bigint,
    p_label text,
    p_runs jsonb
) returns jsonb
language plpgsql
security definer
set search_path = ''
set timezone = 'UTC'
as $$
declare
    v_owner uuid;
    v_claims jsonb;
    v_session_text text;
    v_session uuid;
    v_now timestamptz;
    v_started timestamptz;
    v_run jsonb;
    v_current pare_sync.presence_devices%rowtype;
    v_accepted bigint;
    v_devices jsonb;
begin
    -- Only server-verified identity/session claims participate in authorization.
    -- Malformed UUID claims get a constant auth error, never a cast error echoing input.
    begin
        v_owner := auth.uid();
        v_claims := auth.jwt();
    exception when invalid_text_representation then
        raise exception using errcode = '42501', message = 'Authentication required';
    end;
    v_session_text := v_claims->>'session_id';
    if v_owner is null
        or pg_catalog.jsonb_typeof(v_claims->'session_id') is distinct from 'string'
        or v_session_text is null
        or not (v_session_text collate "C" ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
        or not exists (select 1 from auth.users u where u.id = v_owner) then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    v_session := v_session_text::uuid;

    if p_device_id is null or p_sequence is null or p_sequence not between 1 and 9007199254740991
        or p_label is null or p_label not in ('Mac', 'Android', 'Windows') then
        raise exception using errcode = '22023', message = 'Invalid presence device, sequence or label';
    end if;
    if pg_catalog.jsonb_typeof(p_runs) is distinct from 'array' then
        raise exception using errcode = '22023', message = 'Presence runs must be an array';
    end if;
    if pg_catalog.jsonb_array_length(p_runs) > 32 or pg_catalog.octet_length(p_runs::text) > 32768 then
        raise exception using errcode = '22023', message = 'Presence count or size exceeds v1 limits';
    end if;
    for v_run in select value from pg_catalog.jsonb_array_elements(p_runs) loop
        if pg_catalog.jsonb_typeof(v_run) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'Each presence run must be an object';
        end if;
        if not (v_run ?& array['run_id','task_id','kind','state','started_at','elapsed_seconds'])
            or v_run - array['run_id','task_id','kind','state','started_at','elapsed_seconds','target_seconds'] <> '{}'::jsonb then
            raise exception using errcode = '22023', message = 'Unexpected or missing presence fields';
        end if;
        if pg_catalog.jsonb_typeof(v_run->'run_id') is distinct from 'string'
            or pg_catalog.jsonb_typeof(v_run->'task_id') is distinct from 'string'
            or not ((v_run->>'run_id') collate "C" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            or not ((v_run->>'task_id') collate "C" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            or pg_catalog.jsonb_typeof(v_run->'kind') is distinct from 'string'
            or v_run->>'kind' not in ('personal','waiting')
            or pg_catalog.jsonb_typeof(v_run->'state') is distinct from 'string'
            or v_run->>'state' not in ('running','paused') then
            raise exception using errcode = '22023', message = 'Invalid presence identity, kind or state';
        end if;
        if pg_catalog.jsonb_typeof(v_run->'elapsed_seconds') is distinct from 'number'
            or (v_run->>'elapsed_seconds') !~ '^(0|[1-9][0-9]{0,7})$' then
            raise exception using errcode = '22023', message = 'Invalid elapsed seconds';
        end if;
        if (v_run->>'elapsed_seconds')::bigint > 31536000 then
            raise exception using errcode = '22023', message = 'Elapsed seconds out of range';
        end if;
        if v_run ? 'target_seconds' and v_run->'target_seconds' <> 'null'::jsonb then
            if pg_catalog.jsonb_typeof(v_run->'target_seconds') is distinct from 'number'
                or (v_run->>'target_seconds') !~ '^[1-9][0-9]{0,7}$' then
                raise exception using errcode = '22023', message = 'Invalid target seconds';
            end if;
            if (v_run->>'target_seconds')::bigint > 31536000 then
                raise exception using errcode = '22023', message = 'Target seconds out of range';
            end if;
        end if;
        -- Explicit timezone is required. No timezone-dependent, relative or non-finite dates.
        if pg_catalog.jsonb_typeof(v_run->'started_at') is distinct from 'string'
            or pg_catalog.length(v_run->>'started_at') > 40
            or not ((v_run->>'started_at') collate "C" ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]([.][0-9]{1,9})?(Z|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$') then
            raise exception using errcode = '22023', message = 'Invalid presence start timestamp';
        end if;
        begin
            v_started := (v_run->>'started_at')::timestamptz;
        exception when data_exception then
            raise exception using errcode = '22023', message = 'Invalid presence start timestamp';
        end;
        if v_started < timestamptz '1970-01-01 00:00:00+00'
            or v_started > timestamptz '9999-12-31 23:59:59+00' then
            raise exception using errcode = '22023', message = 'Presence start timestamp out of range';
        end if;
    end loop;
    if exists (select 1 from pg_catalog.jsonb_array_elements(p_runs) r(value) group by value->>'run_id' having pg_catalog.count(*) > 1)
        or exists (select 1 from pg_catalog.jsonb_array_elements(p_runs) r(value) group by value->>'task_id' having pg_catalog.count(*) > 1)
        or (select pg_catalog.count(*) from pg_catalog.jsonb_array_elements(p_runs) r(value) where value->>'kind' = 'personal') > 1 then
        raise exception using errcode = '22023', message = 'Duplicate presence identity or multiple personal runs';
    end if;

    -- A separate per-owner lock never takes the content stream's row lock or changes its cursor.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('pare-presence-v1:' || v_owner::text, 0));
    v_now := pg_catalog.clock_timestamp();
    -- Match auth deletion's parent-before-child lock order; later FK checks cannot deadlock
    -- with a user deletion waiting for this request's session-row lock.
    perform 1 from auth.users u where u.id = v_owner for key share;
    if not found then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    -- Recheck after waiting for the lock. Holding this session row until commit prevents an already
    -- completed logout from racing authorization; session deletion then waits for this request.
    perform 1 from auth.sessions s where s.id = v_session and s.user_id = v_owner
        and (s.not_after is null or s.not_after > v_now) for key share;
    if not found then
        raise exception using errcode = '42501', message = 'Active session required';
    end if;

    delete from pare_sync.presence_devices where owner_id = v_owner and last_seen < v_now - interval '24 hours';
    select * into v_current from pare_sync.presence_devices where owner_id = v_owner and device_id = p_device_id;
    if found then
        if p_sequence = v_current.sequence and (p_label <> v_current.label or p_runs <> v_current.runs) then
            raise exception using errcode = '22023', message = 'Presence sequence already used for a different request';
        end if;
        if p_sequence > v_current.sequence then
            update pare_sync.presence_devices set sequence = p_sequence, label = p_label, runs = p_runs,
                last_seen = v_now, expires_at = v_now + interval '90 seconds'
                where owner_id = v_owner and device_id = p_device_id;
            v_accepted := p_sequence;
        else
            -- Stale and exact idempotent retries must not renew a lease or restore older runs.
            v_accepted := v_current.sequence;
        end if;
    else
        if (select pg_catalog.count(*) from pare_sync.presence_devices where owner_id = v_owner) >= 32 then
            raise exception using errcode = '22023', message = 'Presence device limit reached';
        end if;
        insert into pare_sync.presence_devices(owner_id, device_id, sequence, label, runs, last_seen, expires_at)
            values (v_owner, p_device_id, p_sequence, p_label, p_runs, v_now, v_now + interval '90 seconds');
        v_accepted := p_sequence;
    end if;

    select coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
        'device_id', device_id, 'sequence', sequence, 'label', label, 'last_seen', last_seen,
        'expires_at', expires_at, 'runs', runs) order by last_seen desc, device_id), '[]'::jsonb)
        into v_devices from pare_sync.presence_devices where owner_id = v_owner and last_seen >= v_now - interval '24 hours';
    return pg_catalog.jsonb_build_object('version', 1, 'server_now', v_now,
        'accepted_sequence', v_accepted, 'devices', v_devices);
end;
$$;


revoke all on function public.pare_presence_exchange(uuid,bigint,text,jsonb) from public,anon,authenticated,service_role;
grant execute on function public.pare_presence_exchange(uuid,bigint,text,jsonb) to authenticated;
revoke all on function public.pare_presence_exchange_v2(uuid,bigint,text,jsonb) from public,anon,authenticated,service_role;
grant execute on function public.pare_presence_exchange_v2(uuid,bigint,text,jsonb) to authenticated;

comment on function public.pare_presence_exchange(uuid,bigint,text,jsonb) is
    'Legacy presence endpoint: Mac/Android input and response labels only; lease envelope version 1.';
comment on function public.pare_presence_exchange_v2(uuid,bigint,text,jsonb) is
    'Presence endpoint for Mac/Android/Windows; unchanged lease envelope version 1 and authorization.';

notify pgrst, 'reload schema';
commit;
