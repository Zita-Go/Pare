-- Pare sync v1. Apply as the trusted migration owner, in one transaction.
-- Only the two public RPCs are exposed; pare_sync must remain a private schema.
begin;

create schema pare_sync;
revoke all on schema pare_sync from public, anon, authenticated, service_role;

create table pare_sync.streams (
    owner_id uuid primary key references auth.users(id) on delete cascade,
    sequence bigint not null default 0 check (sequence >= 0)
);

create table pare_sync.items (
    owner_id uuid not null references pare_sync.streams(owner_id) on delete cascade,
    kind text not null check (kind in ('inbox', 'task', 'idea', 'goal', 'day_plan', 'profile')),
    id text not null check (id collate "C" ~ '^[A-Za-z0-9_:@+./-]{1,160}$'),
    revision bigint not null check (revision > 0),
    payload_version integer not null check (payload_version = 1),
    payload jsonb,
    deleted_at timestamptz,
    last_device_id uuid not null,
    sequence bigint not null check (sequence > 0),
    primary key (owner_id, kind, id),
    check ((deleted_at is null and payload is not null and jsonb_typeof(payload) = 'object')
        or (deleted_at is not null and payload is null))
);

-- Immutable snapshots, not references to today's item versions: pull never splits a commit.
create table pare_sync.commits (
    owner_id uuid not null references pare_sync.streams(owner_id) on delete cascade,
    sequence bigint not null check (sequence > 0),
    operation_id uuid not null,
    device_id uuid not null,
    entities jsonb not null check (jsonb_typeof(entities) = 'array'),
    primary key (owner_id, sequence),
    unique (owner_id, operation_id)
);

-- Store the canonical JSONB request as well as its result, including conflicts.
-- No hash collision or response-loss ambiguity; no expiry in v1.
create table pare_sync.receipts (
    owner_id uuid not null references pare_sync.streams(owner_id) on delete cascade,
    operation_id uuid not null,
    device_id uuid not null,
    mutations jsonb not null check (jsonb_typeof(mutations) = 'array'),
    response jsonb not null check (jsonb_typeof(response) = 'object'),
    primary key (owner_id, operation_id)
);

alter table pare_sync.streams enable row level security;
alter table pare_sync.items enable row level security;
alter table pare_sync.commits enable row level security;
alter table pare_sync.receipts enable row level security;
-- Deliberately no policies: default deny, including if table access is accidentally granted.
revoke all on all tables in schema pare_sync from public, anon, authenticated, service_role;

create function public.pare_sync_apply(
    p_operation_id uuid,
    p_device_id uuid,
    p_mutations jsonb
) returns jsonb
language plpgsql
security definer
set search_path = ''
set timezone = 'UTC'
as $$
declare
    v_owner uuid := auth.uid();
    v_mutation jsonb;
    v_current pare_sync.items%rowtype;
    v_receipt pare_sync.receipts%rowtype;
    v_sequence bigint;
    v_revision bigint;
    v_conflicts jsonb := '[]'::jsonb;
    v_entities jsonb := '[]'::jsonb;
    v_response jsonb;
    v_deleted_at timestamptz;
begin
    if v_owner is null or not exists (select 1 from auth.users where id = v_owner) then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_operation_id is null or p_device_id is null then
        raise exception using errcode = '22023', message = 'Operation and device IDs are required';
    end if;
    if jsonb_typeof(p_mutations) is distinct from 'array' then
        raise exception using errcode = '22023', message = 'Mutations must be an array';
    end if;
    if jsonb_array_length(p_mutations) not between 1 and 100
        or octet_length(p_mutations::text) > 1048576 then
        raise exception using errcode = '22023', message = 'Mutation count or size exceeds v1 limits';
    end if;

    -- Validate before acquiring the per-user lock. Extra fields (including owner) are rejected.
    for v_mutation in select value from jsonb_array_elements(p_mutations) loop
        if jsonb_typeof(v_mutation) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'Each mutation must be an object';
        end if;
        if not (v_mutation ?& array['kind', 'id', 'base_revision', 'payload_version', 'deleted', 'payload'])
            or (v_mutation - array['kind', 'id', 'base_revision', 'payload_version', 'deleted', 'payload']) <> '{}'::jsonb then
            raise exception using errcode = '22023', message = 'Unexpected or missing mutation fields';
        end if;
        if jsonb_typeof(v_mutation->'kind') is distinct from 'string'
            or v_mutation->>'kind' not in ('inbox', 'task', 'idea', 'goal', 'day_plan', 'profile')
            or jsonb_typeof(v_mutation->'id') is distinct from 'string'
            or not ((v_mutation->>'id') collate "C" ~ '^[A-Za-z0-9_:@+./-]{1,160}$') then
            raise exception using errcode = '22023', message = 'Invalid entity kind or ID';
        end if;
        if jsonb_typeof(v_mutation->'base_revision') is distinct from 'number'
            or (v_mutation->>'base_revision') !~ '^(0|[1-9][0-9]{0,18})$' then
            raise exception using errcode = '22023', message = 'base_revision must be a nonnegative bigint';
        end if;
        if (v_mutation->>'base_revision')::numeric >= 9223372036854775807 then
            raise exception using errcode = '22023', message = 'base_revision is out of range';
        end if;
        if v_mutation->'payload_version' is distinct from '1'::jsonb
            or jsonb_typeof(v_mutation->'deleted') is distinct from 'boolean' then
            raise exception using errcode = '22023', message = 'Unsupported payload version or invalid deleted flag';
        end if;
        if ((v_mutation->>'deleted')::boolean and v_mutation->'payload' <> 'null'::jsonb)
            or (not (v_mutation->>'deleted')::boolean and jsonb_typeof(v_mutation->'payload') <> 'object') then
            raise exception using errcode = '22023', message = 'Live payloads must be objects; deleted payloads must be null';
        end if;
    end loop;
    if exists (
        select 1 from jsonb_array_elements(p_mutations) as m(value)
        group by value->>'kind', value->>'id' having count(*) > 1
    ) then
        raise exception using errcode = '22023', message = 'Duplicate entity keys in mutation batch';
    end if;

    insert into pare_sync.streams (owner_id) values (v_owner) on conflict do nothing;
    -- Held until the outer transaction commits/rolls back. A second writer cannot allocate
    -- sequence N+1 before N is visible, unlike nextval() on a PostgreSQL sequence.
    select sequence into strict v_sequence from pare_sync.streams
        where owner_id = v_owner for update;

    select * into v_receipt from pare_sync.receipts
        where owner_id = v_owner and operation_id = p_operation_id;
    if found then
        if v_receipt.device_id <> p_device_id or v_receipt.mutations <> p_mutations then
            raise exception using errcode = '22023', message = 'Operation ID already used for a different request';
        end if;
        return v_receipt.response;
    end if;

    -- Validate the entire CAS set before modifying any item.
    for v_mutation in select value from jsonb_array_elements(p_mutations) loop
        select * into v_current from pare_sync.items
            where owner_id = v_owner and kind = v_mutation->>'kind' and id = v_mutation->>'id';
        if coalesce(v_current.revision, 0) <> (v_mutation->>'base_revision')::bigint then
            v_conflicts := v_conflicts || jsonb_build_array(jsonb_build_object(
                'kind', v_mutation->>'kind', 'id', v_mutation->>'id',
                'current', case when v_current.owner_id is null then 'null'::jsonb
                    else to_jsonb(v_current) - 'owner_id' end));
        end if;
    end loop;
    if jsonb_array_length(v_conflicts) > 0 then
        v_response := jsonb_build_object('protocol_version', 1, 'status', 'conflict',
            'sequence', v_sequence, 'entities', '[]'::jsonb, 'conflicts', v_conflicts);
    else
        update pare_sync.streams set sequence = sequence + 1
            where owner_id = v_owner returning sequence into v_sequence;
        v_deleted_at := clock_timestamp();
        for v_mutation in select value from jsonb_array_elements(p_mutations) loop
            v_revision := (v_mutation->>'base_revision')::bigint + 1;
            insert into pare_sync.items as item (
                owner_id, kind, id, revision, payload_version, payload,
                deleted_at, last_device_id, sequence
            ) values (
                v_owner, v_mutation->>'kind', v_mutation->>'id', v_revision, 1,
                case when (v_mutation->>'deleted')::boolean then null else v_mutation->'payload' end,
                case when (v_mutation->>'deleted')::boolean then v_deleted_at else null end,
                p_device_id, v_sequence
            ) on conflict (owner_id, kind, id) do update set
                revision = excluded.revision, payload_version = excluded.payload_version,
                payload = excluded.payload, deleted_at = excluded.deleted_at,
                last_device_id = excluded.last_device_id, sequence = excluded.sequence
            returning * into v_current;
            v_entities := v_entities || jsonb_build_array(to_jsonb(v_current) - 'owner_id');
        end loop;
        insert into pare_sync.commits (owner_id, sequence, operation_id, device_id, entities)
            values (v_owner, v_sequence, p_operation_id, p_device_id, v_entities);
        v_response := jsonb_build_object('protocol_version', 1, 'status', 'applied',
            'sequence', v_sequence, 'entities', v_entities, 'conflicts', '[]'::jsonb);
    end if;
    insert into pare_sync.receipts (owner_id, operation_id, device_id, mutations, response)
        values (v_owner, p_operation_id, p_device_id, p_mutations, v_response);
    return v_response;
end;
$$;

create function public.pare_sync_pull(p_after bigint default 0, p_limit integer default 50)
returns jsonb
language plpgsql
security definer
set search_path = ''
set timezone = 'UTC'
as $$
declare
    v_owner uuid := auth.uid();
    v_head bigint;
    v_response jsonb;
begin
    if v_owner is null or not exists (select 1 from auth.users where id = v_owner) then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_after is null or p_after < 0 or p_limit is null or p_limit not between 1 and 100 then
        raise exception using errcode = '22023', message = 'Invalid cursor or commit limit (1–100)';
    end if;
    -- One snapshot supplies stream high-water mark, page, cursor and has_more.
    with candidates as materialized (
        select sequence, operation_id, device_id, entities from pare_sync.commits
        where owner_id = v_owner and sequence > p_after
        order by sequence limit (p_limit + 1)
    ), page as (
        select * from candidates order by sequence limit p_limit
    )
    select coalesce((select sequence from pare_sync.streams where owner_id = v_owner), 0),
    jsonb_build_object(
        'protocol_version', 1,
        'cursor', coalesce((select max(sequence) from page), p_after),
        'has_more', (select count(*) > p_limit from candidates),
        'commits', coalesce((select jsonb_agg(to_jsonb(page) order by sequence) from page), '[]'::jsonb)
    ) into v_head, v_response;
    if p_after > v_head then
        raise exception using errcode = '22023', message = 'Cursor exceeds this account stream';
    end if;
    return v_response;
end;
$$;

-- Supabase default privileges can include explicit grants; revoke each client role.
revoke all on function public.pare_sync_apply(uuid, uuid, jsonb) from public, anon, authenticated, service_role;
revoke all on function public.pare_sync_pull(bigint, integer) from public, anon, authenticated, service_role;
grant execute on function public.pare_sync_apply(uuid, uuid, jsonb) to authenticated;
grant execute on function public.pare_sync_pull(bigint, integer) to authenticated;

commit;
