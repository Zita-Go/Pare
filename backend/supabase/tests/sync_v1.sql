-- Run only against an isolated Supabase development database, as postgres/migration owner.
-- No extensions required. All fake users, data and temporary privilege changes roll back.
-- psql: psql "$PARE_DEV_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f backend/supabase/tests/sync_v1.sql
begin;
set local plpgsql.check_asserts = on;

do $$
declare
    u1 uuid := gen_random_uuid();
    u2 uuid := gen_random_uuid();
    d1 uuid := gen_random_uuid();
    d2 uuid := gen_random_uuid();
    create_op uuid := gen_random_uuid();
    conflict_op uuid := gen_random_uuid();
    initial jsonb := '[
      {"kind":"task","id":"alpha","base_revision":0,"payload_version":1,"deleted":false,"payload":{"title":"First"}},
      {"kind":"idea","id":"beta","base_revision":0,"payload_version":1,"deleted":false,"payload":{"title":"Idea"}}
    ]'::jsonb;
    conflict_batch jsonb := '[
      {"kind":"task","id":"alpha","base_revision":0,"payload_version":1,"deleted":false,"payload":{"title":"Stale"}},
      {"kind":"task","id":"charlie","base_revision":0,"payload_version":1,"deleted":false,"payload":{}},
      {"kind":"task","id":"missing","base_revision":7,"payload_version":1,"deleted":false,"payload":{}}
    ]'::jsonb;
    base jsonb := '{"kind":"task","id":"invalid-test","base_revision":0,"payload_version":1,"deleted":false,"payload":{}}';
    first_result jsonb;
    conflict_result jsonb;
    result jsonb;
    invalid jsonb;
begin
    insert into auth.users (id, aud, role, email) values
        (u1, 'authenticated', 'authenticated', u1::text || '@pare.invalid'),
        (u2, 'authenticated', 'authenticated', u2::text || '@pare.invalid');

    assert not has_schema_privilege('anon', 'pare_sync', 'USAGE'), 'anon must not access private schema';
    assert not has_schema_privilege('authenticated', 'pare_sync', 'USAGE'), 'authenticated must not access private schema';
    assert not has_function_privilege('anon', 'public.pare_sync_apply(uuid,uuid,jsonb)', 'EXECUTE'), 'anon apply grant';
    assert not has_function_privilege('anon', 'public.pare_sync_pull(bigint,integer)', 'EXECUTE'), 'anon pull grant';
    assert not has_function_privilege('service_role', 'public.pare_sync_pull(bigint,integer)', 'EXECUTE'), 'service role RPC grant';
    assert has_function_privilege('authenticated', 'public.pare_sync_apply(uuid,uuid,jsonb)', 'EXECUTE'), 'missing apply grant';
    assert has_function_privilege('authenticated', 'public.pare_sync_pull(bigint,integer)', 'EXECUTE'), 'missing pull grant';
    assert (select count(*) = 4 and bool_and(c.relrowsecurity)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'pare_sync' and c.relkind = 'r'), 'RLS must cover all four tables';
    assert (select count(*) = 0 from pg_policies where schemaname = 'pare_sync'), 'private tables must default deny';

    -- Even a forged claim does not give anon EXECUTE permission.
    perform set_config('request.jwt.claim.sub', u1::text, true);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', u1, 'role', 'authenticated')::text, true);
    set local role anon;
    begin
        perform public.pare_sync_apply(create_op, d1, initial);
        raise exception 'anon apply unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public.pare_sync_pull();
        raise exception 'anon pull unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    reset role;

    set local role authenticated;
    perform set_config('request.jwt.claim.sub', '', true);
    perform set_config('request.jwt.claims', '{}', true);
    begin
        perform public.pare_sync_pull();
        raise exception 'missing auth UID unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public.pare_sync_apply(create_op, d1, initial);
        raise exception 'missing auth UID apply unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    perform set_config('request.jwt.claim.sub', u1::text, true);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', u1, 'role', 'authenticated')::text, true);
    assert public.pare_sync_pull() = '{"protocol_version":1,"cursor":0,"has_more":false,"commits":[]}'::jsonb, 'initial pull';
    begin
        perform public.pare_sync_pull(1, 1);
        raise exception 'future cursor on absent stream unexpectedly accepted';
    exception when invalid_parameter_value then null;
    end;

    first_result := public.pare_sync_apply(create_op, d1, initial);
    assert first_result->>'status' = 'applied' and first_result->>'sequence' = '1', 'first commit';
    assert jsonb_array_length(first_result->'entities') = 2, 'atomic create entity count';
    assert first_result#>>'{entities,0,revision}' = '1' and first_result#>>'{entities,1,sequence}' = '1', 'first revisions';
    assert not ((first_result#>'{entities,0}') ? 'owner_id'), 'owner must not appear in entity';
    assert public.pare_sync_apply(create_op, d1, initial) = first_result, 'idempotent retry';
    begin
        perform public.pare_sync_apply(create_op, d2, initial);
        raise exception 'changed device unexpectedly accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_sync_apply(create_op, d1, jsonb_set(initial, '{0,payload,title}', '"Changed"'));
        raise exception 'changed operation body unexpectedly accepted';
    exception when invalid_parameter_value then null;
    end;

    conflict_result := public.pare_sync_apply(conflict_op, d2, conflict_batch);
    assert conflict_result->>'status' = 'conflict' and conflict_result->>'sequence' = '1', 'CAS conflict';
    assert conflict_result->'entities' = '[]'::jsonb, 'conflict must not apply entities';
    assert jsonb_array_length(conflict_result->'conflicts') = 2, 'all CAS conflicts must be returned';
    assert conflict_result#>>'{conflicts,0,current,payload,title}' = 'First', 'conflict current value';
    assert conflict_result#>'{conflicts,1,current}' = 'null'::jsonb, 'missing entity conflict';
    assert public.pare_sync_apply(conflict_op, d2, conflict_batch) = conflict_result, 'conflict receipt retry';
    reset role;
    assert (select count(*) = 2 from pare_sync.items where owner_id = u1), 'no partial create on conflict';
    assert (select sequence = 1 from pare_sync.streams where owner_id = u1), 'conflict must not advance cursor';
    set local role authenticated;

    -- Guards reject malformed input before any stream/item/receipt writes.
    for invalid in select value from jsonb_array_elements(jsonb_build_array(
        null, '{}', '[]'::jsonb, '[null]'::jsonb,
        jsonb_build_array(base - 'payload'),
        jsonb_build_array(base || jsonb_build_object('owner_id', u2)),
        jsonb_build_array(base || '{"kind":"token"}'::jsonb),
        jsonb_build_array(base || '{"kind":"api_config"}'::jsonb),
        jsonb_build_array(base || '{"kind":"request_history"}'::jsonb),
        jsonb_build_array(base || '{"kind":"timer"}'::jsonb),
        jsonb_build_array(base || '{"id":""}'::jsonb),
        jsonb_build_array(base || '{"id":" leading"}'::jsonb),
        jsonb_build_array(base || '{"id":"trailing "}'::jsonb),
        jsonb_build_array(base || jsonb_build_object('id', U&'\00A0leading')),
        jsonb_build_array(base || jsonb_build_object('id', U&'trailing\3000')),
        jsonb_build_array(base || jsonb_build_object('id', U&'embedded\0009tab')),
        jsonb_build_array(base || jsonb_build_object('id', U&'embedded\200Bformat')),
        jsonb_build_array(base || jsonb_build_object('id', repeat('x', 161))),
        jsonb_build_array(base || jsonb_build_object('id', repeat('中', 54))),
        jsonb_build_array(base || '{"id":"中文"}'::jsonb),
        jsonb_build_array(base || '{"id":42}'::jsonb),
        jsonb_build_array(base || '{"base_revision":-1}'::jsonb),
        jsonb_build_array(base || '{"base_revision":1.5}'::jsonb),
        jsonb_build_array(base || '{"base_revision":"0"}'::jsonb),
        jsonb_build_array(base || '{"base_revision":null}'::jsonb),
        jsonb_build_array(base || '{"base_revision":9223372036854775808}'::jsonb),
        jsonb_build_array(base || '{"base_revision":9223372036854775807}'::jsonb),
        jsonb_build_array(base || '{"payload_version":2}'::jsonb),
        jsonb_build_array(base || '{"payload_version":"1"}'::jsonb),
        jsonb_build_array(base || '{"deleted":"false"}'::jsonb),
        jsonb_build_array(base || '{"payload":null}'::jsonb),
        jsonb_build_array(base || '{"payload":[]}'::jsonb),
        jsonb_build_array(base || '{"deleted":true}'::jsonb),
        jsonb_build_array(base, base),
        jsonb_build_array(base || jsonb_build_object('payload', jsonb_build_object('text', repeat('x', 1048576)))),
        (select jsonb_agg(base || jsonb_build_object('id', n::text)) from generate_series(1, 101) n)
    )) loop
        begin
            perform public.pare_sync_apply(gen_random_uuid(), d1, invalid);
            raise exception 'Invalid batch unexpectedly accepted: %', left(invalid::text, 200);
        exception when invalid_parameter_value then null;
        end;
    end loop;
    begin
        perform public.pare_sync_apply(gen_random_uuid(), d1, null);
        raise exception 'SQL null mutations unexpectedly accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_sync_apply(null, d1, initial);
        raise exception 'null operation ID unexpectedly accepted';
    exception when invalid_parameter_value then null;
    end;
    begin
        perform public.pare_sync_apply(gen_random_uuid(), null, initial);
        raise exception 'null device ID unexpectedly accepted';
    exception when invalid_parameter_value then null;
    end;
    for invalid in select value from jsonb_array_elements('[[-1,1],[2,1],[0,0],[0,101],[null,1],[0,null]]') loop
        begin
            perform public.pare_sync_pull((invalid->>0)::bigint, (invalid->>1)::integer);
            raise exception 'Invalid pull parameters unexpectedly accepted';
        exception when invalid_parameter_value then null;
        end;
    end loop;
    reset role;
    assert (select count(*) = 2 from pare_sync.receipts where owner_id = u1), 'invalid requests must not create receipts';
    set local role authenticated;

    result := public.pare_sync_apply(gen_random_uuid(), d2, '[
      {"kind":"task","id":"alpha","base_revision":1,"payload_version":1,"deleted":false,"payload":{"title":"Second"}},
      {"kind":"idea","id":"beta","base_revision":1,"payload_version":1,"deleted":true,"payload":null}
    ]');
    assert result->>'sequence' = '2' and result#>>'{entities,0,revision}' = '2', 'second atomic commit';
    assert result#>'{entities,1,payload}' = 'null'::jsonb and result#>>'{entities,1,deleted_at}' is not null, 'tombstone';
    assert public.pare_sync_apply(create_op, d1, initial) = first_result, 'old retry after newer update';
    assert public.pare_sync_apply(conflict_op, d2, conflict_batch) = conflict_result, 'conflict retry must stay stable after newer update';

    result := public.pare_sync_apply(gen_random_uuid(), d1, '[
      {"kind":"idea","id":"beta","base_revision":1,"payload_version":1,"deleted":false,"payload":{"title":"Offline copy"}}
    ]');
    assert result->>'status' = 'conflict' and result#>>'{conflicts,0,current,revision}' = '2', 'stale resurrection refused';
    result := public.pare_sync_apply(gen_random_uuid(), d1, '[
      {"kind":"idea","id":"beta","base_revision":0,"payload_version":1,"deleted":false,"payload":{}}
    ]');
    assert result->>'status' = 'conflict', 'tombstone cannot be recreated with revision zero';

    result := public.pare_sync_pull(0, 1);
    assert result->>'cursor' = '1' and result->>'has_more' = 'true', 'first page cursor';
    assert jsonb_array_length(result->'commits') = 1 and jsonb_array_length(result#>'{commits,0,entities}') = 2, 'whole commit pagination';
    assert result#>>'{commits,0,entities,0,payload,title}' = 'First', 'pull must retain historical snapshot';
    assert result#>>'{commits,0,operation_id}' = create_op::text, 'pull operation identity';
    result := public.pare_sync_pull(1, 1);
    assert result->>'cursor' = '2' and result->>'has_more' = 'false', 'second page cursor';
    assert result#>>'{commits,0,entities,0,payload,title}' = 'Second', 'pull latest snapshot';
    assert result#>>'{commits,0,entities,1,deleted_at}' is not null, 'pull propagates delete';
    assert public.pare_sync_pull(2, 1) = '{"protocol_version":1,"cursor":2,"has_more":false,"commits":[]}'::jsonb, 'empty page cursor stability';

    -- Same keys/operation ID in another account belong to a different stream.
    perform set_config('request.jwt.claim.sub', u2::text, true);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', u2, 'role', 'authenticated')::text, true);
    assert public.pare_sync_pull()->'commits' = '[]'::jsonb, 'cross-account pull leak';
    begin
        perform public.pare_sync_pull(2, 1);
        raise exception 'account A cursor unexpectedly accepted for empty account B';
    exception when invalid_parameter_value then null;
    end;
    result := public.pare_sync_apply(create_op, d2, jsonb_set(initial, '{0,payload,title}', '"Account B"'));
    assert result->>'status' = 'applied' and result->>'sequence' = '1', 'independent account CAS and receipt';
    assert public.pare_sync_pull()#>>'{commits,0,entities,0,payload,title}' = 'Account B', 'account B own data';
    perform set_config('request.jwt.claim.sub', u1::text, true);
    perform set_config('request.jwt.claims', jsonb_build_object('sub', u1, 'role', 'authenticated')::text, true);
    assert public.pare_sync_pull(1, 1)#>>'{commits,0,entities,0,payload,title}' = 'Second', 'account B must not mutate A';
    begin
        perform 1 from pare_sync.items;
        raise exception 'raw table read unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    begin
        update pare_sync.streams set sequence = 999;
        raise exception 'raw table write unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    reset role;

    -- Prove RLS separately from ACLs, then restore ACLs. Entire test rolls back as well.
    grant usage on schema pare_sync to authenticated;
    grant select, insert on pare_sync.items to authenticated;
    set local role authenticated;
    assert (select count(*) = 0 from pare_sync.items), 'RLS must deny even own raw rows';
    begin
        insert into pare_sync.items (owner_id, kind, id, revision, payload_version, payload, last_device_id, sequence)
            values (u2, 'task', 'forbidden', 1, 1, '{}', d1, 1);
        raise exception 'RLS raw write unexpectedly succeeded';
    exception when insufficient_privilege then null;
    end;
    reset role;
    revoke select, insert on pare_sync.items from authenticated;
    revoke usage on schema pare_sync from authenticated;
    assert (select count(*) = 2 from pare_sync.commits where owner_id = u1), 'only successful requests create commits';
    assert (select count(*) = 1 from pare_sync.commits where owner_id = u2), 'account B commit count';
    raise notice 'Pare sync v1 assertions passed; rolling back all fixtures and grants.';
end;
$$;

rollback;
