-- Run only on an isolated development database, after the reviewed presence migration, as its owner.
-- Uses random fake users and sessions; every fixture and temporary privilege change rolls back.
-- psql "$PARE_DEV_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f backend/supabase/tests/presence_v2.sql
begin;
set local plpgsql.check_asserts = on;

do $$
declare
    u1 uuid := gen_random_uuid(); u2 uuid := gen_random_uuid();
    s1 uuid := gen_random_uuid(); s2 uuid := gen_random_uuid();
    d1 uuid := gen_random_uuid(); d2 uuid := gen_random_uuid(); d3 uuid := gen_random_uuid();
    run_id uuid := gen_random_uuid(); task_id uuid := gen_random_uuid();
    new_device uuid := gen_random_uuid();
    base jsonb;
    runs jsonb;
    first_result jsonb;
    result jsonb;
    invalid jsonb;
    first_seen timestamptz;
    first_expiry timestamptz;
    old_seen timestamptz;
    bad_sequence bigint;
    device_count integer;
begin
    insert into auth.users(id,aud,role,email) values
        (u1,'authenticated','authenticated',u1::text || '@pare-presence-v2.invalid'),
        (u2,'authenticated','authenticated',u2::text || '@pare-presence-v2.invalid');
    insert into auth.sessions(id,user_id,created_at,updated_at) values
        (s1,u1,clock_timestamp(),clock_timestamp()), (s2,u2,clock_timestamp(),clock_timestamp());
    base := jsonb_build_object('run_id',run_id,'task_id',task_id,'kind','personal','state','running',
        'started_at','2026-10-31T08:00:00.123Z','elapsed_seconds',30,'target_seconds',300);
    runs := jsonb_build_array(base);

    assert not has_schema_privilege('anon','pare_sync','USAGE'), 'anon private schema';
    assert not has_schema_privilege('authenticated','pare_sync','USAGE'), 'authenticated private schema';
    assert not has_table_privilege('authenticated','pare_sync.presence_devices','SELECT'), 'raw select grant';
    assert not has_function_privilege('anon','public.pare_presence_exchange_v2(uuid,bigint,text,jsonb)','EXECUTE'), 'anon RPC grant';
    assert not has_function_privilege('service_role','public.pare_presence_exchange_v2(uuid,bigint,text,jsonb)','EXECUTE'), 'service RPC grant';
    assert has_function_privilege('authenticated','public.pare_presence_exchange_v2(uuid,bigint,text,jsonb)','EXECUTE'), 'authenticated RPC grant';
    assert (select c.relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace
        where n.nspname='pare_sync' and c.relname='presence_devices'), 'presence RLS required';
    assert not exists(select 1 from pg_policies where schemaname='pare_sync' and tablename='presence_devices'), 'presence must default deny';
    assert (select p.prosecdef
        and exists(select 1 from unnest(p.proconfig) c where c in ('search_path=','search_path=""'))
        and exists(select 1 from unnest(p.proconfig) c where lower(c)='timezone=utc')
        from pg_proc p where p.oid='public.pare_presence_exchange_v2(uuid,bigint,text,jsonb)'::regprocedure), 'definer search path/timezone';

    perform set_config('request.jwt.claim.sub',u1::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',u1,'role','authenticated','session_id',s1)::text,true);
    set local role anon;
    begin
        perform public.pare_presence_exchange_v2(d1,1,'Windows',runs);
        raise exception 'anon RPC unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    reset role;

    set local role authenticated;
    perform set_config('request.jwt.claim.sub','',true);
    perform set_config('request.jwt.claims','{}',true);
    begin
        perform public.pare_presence_exchange_v2(d1,1,'Windows',runs);
        raise exception 'missing identity unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    perform set_config('request.jwt.claim.sub',u1::text,true);
    for invalid in select value from jsonb_array_elements(jsonb_build_array(
        null, '', 'not-a-uuid', 42, s2::text, gen_random_uuid()::text
    )) loop
        perform set_config('request.jwt.claims',jsonb_build_object('sub',u1,'role','authenticated','session_id',invalid)::text,true);
        begin
            perform public.pare_presence_exchange_v2(d1,1,'Windows',runs);
            raise exception 'missing/malformed/foreign session unexpectedly succeeded';
        exception when insufficient_privilege then null;
        end;
    end loop;
    perform set_config('request.jwt.claims',jsonb_build_object('sub',u1,'role','authenticated','session_id',s1)::text,true);

    first_result := public.pare_presence_exchange_v2(d1,1,'Windows',runs);
    assert first_result->>'version'='1' and first_result->>'accepted_sequence'='1', 'initial receipt';
    assert jsonb_array_length(first_result->'devices')=1, 'initial device count';
    assert first_result#>>'{devices,0,device_id}'=d1::text, 'include own device';
    assert first_result#>'{devices,0,runs}'=runs, 'original run state';
    assert not (first_result#>'{devices,0}' ? 'owner_id'), 'owner must not be exposed';
    first_seen := (first_result#>>'{devices,0,last_seen}')::timestamptz;
    first_expiry := (first_result#>>'{devices,0,expires_at}')::timestamptz;
    assert first_expiry-first_seen=interval '90 seconds', 'server lease duration';
    assert first_seen <= (first_result->>'server_now')::timestamptz, 'last_seen cannot exceed server_now';
    result := public.pare_presence_exchange_v2(d1,1,'Windows',runs);
    assert result->'devices'=first_result->'devices', 'idempotent retry must not renew TTL';
    begin
        perform public.pare_presence_exchange_v2(d1,1,'Android',runs);
        raise exception 'same sequence with changed label accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_presence_exchange_v2(d1,1,'Windows',jsonb_set(runs,'{0,state}','"paused"'));
        raise exception 'same sequence with changed state accepted';
    exception when invalid_parameter_value then null;
    end;
    result := public.pare_presence_exchange_v2(d1,2,'Windows','[]');
    assert result->>'accepted_sequence'='2' and result#>'{devices,0,runs}'='[]'::jsonb, 'newer clear';
    first_seen := (result#>>'{devices,0,last_seen}')::timestamptz;
    result := public.pare_presence_exchange_v2(d1,1,'Windows',runs);
    assert result->>'accepted_sequence'='2' and result#>'{devices,0,runs}'='[]'::jsonb, 'stale must not resurrect active runs';
    assert (result#>>'{devices,0,last_seen}')::timestamptz=first_seen, 'stale must not renew TTL';

    -- Current sessions are checked on every request, independently of JWT expiry.
    reset role;
    update auth.sessions set not_after=clock_timestamp()-interval '1 second' where id=s1;
    set local role authenticated;
    begin
        perform public.pare_presence_exchange_v2(d1,3,'Windows',runs);
        raise exception 'expired session accepted';
    exception when insufficient_privilege then null;
    end;
    reset role;
    update auth.sessions set not_after=null where id=s1;
    delete from auth.sessions where id=s1;
    set local role authenticated;
    begin
        perform public.pare_presence_exchange_v2(d1,3,'Windows',runs);
        raise exception 'revoked session accepted';
    exception when insufficient_privilege then null;
    end;
    reset role;
    insert into auth.sessions(id,user_id,created_at,updated_at) values(s1,u1,clock_timestamp(),clock_timestamp());
    set local role authenticated;

    for bad_sequence in select unnest(array[0::bigint,-1::bigint,9007199254740992::bigint]) loop
        begin
            perform public.pare_presence_exchange_v2(d1,bad_sequence,'Windows',runs);
            raise exception 'invalid sequence accepted';
        exception when invalid_parameter_value then null;
        end;
    end loop;
    for invalid in select value from jsonb_array_elements(jsonb_build_array(
        'null'::jsonb, '{}'::jsonb, '[null]'::jsonb,
        jsonb_build_array(base-'task_id'),
        jsonb_build_array(base || jsonb_build_object('owner_id',u2)),
        jsonb_build_array(base || '{"title":"not permitted"}'::jsonb),
        jsonb_build_array(base || '{"run_id":"invalid"}'::jsonb),
        jsonb_build_array(base || '{"run_id":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}'::jsonb),
        jsonb_build_array(base || '{"task_id":null}'::jsonb),
        jsonb_build_array(base || '{"task_id":123}'::jsonb),
        jsonb_build_array(base || '{"kind":"check"}'::jsonb),
        jsonb_build_array(base || '{"state":"complete"}'::jsonb),
        jsonb_build_array(base || '{"elapsed_seconds":-1}'::jsonb),
        jsonb_build_array(base || '{"elapsed_seconds":31536001}'::jsonb),
        jsonb_build_array(base || '{"elapsed_seconds":1.5}'::jsonb),
        jsonb_build_array(base || '{"elapsed_seconds":"1"}'::jsonb),
        jsonb_build_array(base || '{"elapsed_seconds":true}'::jsonb),
        jsonb_build_array(base || '{"target_seconds":0}'::jsonb),
        jsonb_build_array(base || '{"target_seconds":31536001}'::jsonb),
        jsonb_build_array(base || '{"target_seconds":"5"}'::jsonb),
        jsonb_build_array(base || '{"target_seconds":1.5}'::jsonb),
        jsonb_build_array(base || '{"started_at":"infinity"}'::jsonb),
        jsonb_build_array(base || '{"started_at":"tomorrow"}'::jsonb),
        jsonb_build_array(base || '{"started_at":"2026-02-30T08:00:00Z"}'::jsonb),
        jsonb_build_array(base || '{"started_at":"2026-10-31T08:00:00"}'::jsonb),
        jsonb_build_array(base || '{"started_at":"1969-12-31T23:59:59Z"}'::jsonb),
        jsonb_build_array(base,base),
        jsonb_build_array(base,base || jsonb_build_object('run_id',gen_random_uuid())),
        jsonb_build_array(base,base || jsonb_build_object('run_id',gen_random_uuid(),'task_id',gen_random_uuid())),
        jsonb_build_array(base || jsonb_build_object('unknown',repeat('x',32769))),
        (select jsonb_agg(base || jsonb_build_object('run_id',gen_random_uuid(),'task_id',gen_random_uuid(),'kind','waiting')) from generate_series(1,33))
    )) loop
        begin
            perform public.pare_presence_exchange_v2(d1,3,'Windows',invalid);
            raise exception 'invalid run shape/type/bound accepted';
        exception when invalid_parameter_value then null;
        end;
    end loop;
    begin
        perform public.pare_presence_exchange_v2(null,3,'Windows',runs);
        raise exception 'null device accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_presence_exchange_v2(d1,null,'Windows',runs);
        raise exception 'null sequence accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_presence_exchange_v2(d1,3,'Task title',runs);
        raise exception 'unbounded label accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_presence_exchange_v2(d1,3,'Windows',null);
        raise exception 'SQL null runs accepted';
    exception when invalid_parameter_value then null;
    end;

    -- Valid edge bounds and optional nullable target; then restore compact payload.
    result := public.pare_presence_exchange_v2(d1,3,'Windows',jsonb_build_array(base || '{"elapsed_seconds":31536000,"target_seconds":null,"state":"paused"}'::jsonb));
    assert result#>>'{devices,0,runs,0,state}'='paused', 'valid paused state';
    result := public.pare_presence_exchange_v2(d1,4,'Windows',jsonb_build_array((base-'target_seconds') || '{"elapsed_seconds":0}'::jsonb));
    assert result#>>'{devices,0,runs,0,elapsed_seconds}'='0', 'valid omitted target and zero elapsed';
    result := public.pare_presence_exchange_v2(d2,1,'Android',runs);
    assert jsonb_array_length(result->'devices')=2, 'two-device visibility';

    -- Upgraded clients see all three labels. Old strict decoders must never receive Windows.
    result := public.pare_presence_exchange_v2(d3,1,'Mac','[]');
    assert result->>'version'='1', 'v2 must retain the response envelope version';
    assert jsonb_array_length(result->'devices')=3, 'three-platform visibility';
    assert (select array_agg(value->>'label' order by value->>'label') from jsonb_array_elements(result->'devices'))
        = array['Android','Mac','Windows'], 'v2 labels must include all supported platforms';
    result := public.pare_presence_exchange(d3,1,'Mac','[]');
    assert result->>'version'='1' and result->>'accepted_sequence'='1', 'old-client receipt compatibility';
    assert jsonb_array_length(result->'devices')=2, 'old RPC filters Windows records';
    assert not exists(select 1 from jsonb_array_elements(result->'devices') where value->>'label'='Windows'), 'Windows leaked into old strict decoder';
    begin
        perform public.pare_presence_exchange(d1,5,'Windows',runs);
        raise exception 'old RPC accepted Windows input';
    exception when invalid_parameter_value then null;
    end;
    for invalid in select value from jsonb_array_elements(jsonb_build_array('Linux','windows','Mac/Windows',null)) loop
        begin
            perform public.pare_presence_exchange_v2(d3,2,invalid#>>'{}','[]');
            raise exception 'v2 accepted an unknown or null platform';
        exception when invalid_parameter_value then null;
        end;
    end loop;
    -- Remove only this random fixture device so the remaining inherited v1 bounds stay exact.
    reset role;
    delete from pare_sync.presence_devices where owner_id=u1 and device_id=d3;
    set local role authenticated;

    select jsonb_agg(base || jsonb_build_object('run_id',gen_random_uuid(),'task_id',gen_random_uuid(),
        'kind','waiting','elapsed_seconds',0,'target_seconds',31536000)) into invalid from generate_series(1,32);
    result := public.pare_presence_exchange_v2(d2,2,'Android',invalid);
    assert result#>>'{devices,0,device_id}'=d2::text and jsonb_array_length(result#>'{devices,0,runs}')=32,
        '32 unique waiting runs and maximum target must be accepted';

    -- Same device ID in another owner is a separate row, never a cross-account lookup.
    perform set_config('request.jwt.claim.sub',u2::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',u2,'role','authenticated','session_id',s2)::text,true);
    result := public.pare_presence_exchange_v2(d1,1,'Android','[]');
    assert jsonb_array_length(result->'devices')=1 and result#>'{devices,0,runs}'='[]'::jsonb, 'cross-owner data leak';
    perform set_config('request.jwt.claim.sub',u1::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',u1,'role','authenticated','session_id',s1)::text,true);
    result := public.pare_presence_exchange_v2(d1,4,'Windows',jsonb_build_array((base-'target_seconds') || '{"elapsed_seconds":0}'::jsonb));
    assert result->>'accepted_sequence'='4' and jsonb_array_length(result->'devices')=2, 'other owner modified state';
    begin
        perform 1 from pare_sync.presence_devices;
        raise exception 'raw read accepted';
    exception when insufficient_privilege then null;
    end;
    begin
        delete from pare_sync.presence_devices;
        raise exception 'raw delete accepted';
    exception when insufficient_privilege then null;
    end;
    reset role;

    -- No request above may create a content stream, mutation receipt or completed-run history.
    assert not exists(select 1 from pare_sync.streams where owner_id in(u1,u2)), 'presence touched content streams';
    assert not exists(select 1 from pare_sync.items where owner_id in(u1,u2)), 'presence touched content items';
    assert not exists(select 1 from pare_sync.commits where owner_id in(u1,u2)), 'presence touched content commits';
    assert not exists(select 1 from pare_sync.receipts where owner_id in(u1,u2)), 'presence touched content receipts';

    -- Expired (<24h) rows are visible as stale; exact retry does not revive them.
    old_seen := clock_timestamp()-interval '5 minutes';
    update pare_sync.presence_devices set last_seen=old_seen,expires_at=old_seen+interval '90 seconds' where owner_id=u1 and device_id=d1;
    set local role authenticated;
    result := public.pare_presence_exchange_v2(d1,4,'Windows',jsonb_build_array((base-'target_seconds') || '{"elapsed_seconds":0}'::jsonb));
    assert exists(select 1 from jsonb_array_elements(result->'devices') d(value)
        where value->>'device_id'=d1::text and (value->>'expires_at')::timestamptz < (result->>'server_now')::timestamptz), 'stale retry renewed lease';
    reset role;
    old_seen := clock_timestamp()-interval '25 hours';
    update pare_sync.presence_devices set last_seen=old_seen,expires_at=old_seen+interval '90 seconds' where owner_id=u1 and device_id=d2;
    set local role authenticated;
    result := public.pare_presence_exchange_v2(d1,5,'Windows','[]');
    assert jsonb_array_length(result->'devices')=1, 'older-than-24h device returned';
    reset role;
    assert not exists(select 1 from pare_sync.presence_devices where owner_id=u1 and device_id=d2), 'old owner rows not pruned';

    -- Device cap keeps existing online states rather than evicting an arbitrary device.
    set local role authenticated;
    for device_count in 2..32 loop perform public.pare_presence_exchange_v2(gen_random_uuid(),1,'Android','[]'); end loop;
    begin
        perform public.pare_presence_exchange_v2(new_device,1,'Windows','[]');
        raise exception '33rd retained device accepted';
    exception when invalid_parameter_value then null;
    end;
    result := public.pare_presence_exchange_v2(d1,6,'Windows','[]');
    assert jsonb_array_length(result->'devices')=32, 'device cap response count';
    reset role;
    assert (select count(*)=32 from pare_sync.presence_devices where owner_id=u1), 'device cap storage count';

    -- RLS still denies raw reads/writes even with an accidentally broad ACL.
    grant usage on schema pare_sync to authenticated;
    grant select,insert on pare_sync.presence_devices to authenticated;
    old_seen := clock_timestamp();
    set local role authenticated;
    assert (select count(*)=0 from pare_sync.presence_devices), 'RLS own/foreign read leak';
    begin
        insert into pare_sync.presence_devices(owner_id,device_id,sequence,label,runs,last_seen,expires_at)
            values(u1,new_device,1,'Windows','[]',old_seen,old_seen+interval '90 seconds');
        raise exception 'RLS raw insert accepted';
    exception when insufficient_privilege then null;
    end;
    reset role;
    revoke select,insert on pare_sync.presence_devices from authenticated;
    revoke usage on schema pare_sync from authenticated;
    delete from auth.users where id=u2;
    assert not exists(select 1 from pare_sync.presence_devices where owner_id=u2), 'user deletion must cascade presence';
    set local role authenticated;
    perform set_config('request.jwt.claim.sub',u2::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',u2,'role','authenticated','session_id',s2)::text,true);
    begin
        perform public.pare_presence_exchange_v2(d1,2,'Android','[]');
        raise exception 'deleted user JWT accepted';
    exception when insufficient_privilege then null;
    end;
    reset role;
    raise notice 'Pare presence v2 assertions passed; all fake users, sessions, state and grants roll back.';
end;
$$;

rollback;
select 'presence_v2 assertions passed and fixture transaction rolled back' as result;
