# Pare hosted sync v1

This directory contains the database foundation for the Android/Mac sync protocol. It does not connect either app to a cloud account or implement a local outbox. The migration assumes a Supabase PostgreSQL database with `auth.users`, `auth.uid()` and the standard `anon`, `authenticated` and `service_role` roles. No additional extension is required.

## Deployment and verification

Apply `migrations/20261029161731_pare_sync_v1.sql` once, as the trusted migration owner, after checking that neither `pare_sync` nor the two RPC names already exist. Its version matches the deployed development migration history. The migration deliberately fails on an existing schema/function instead of replacing unknown objects. Apply the entire file in one transaction so a partially configured RPC cannot become public. Keep `pare_sync` out of the Data API exposed-schema list; only the two functions in `public` are intended for RPC use.

Run `tests/sync_v1.sql` as the migration owner against an isolated development database. It creates randomized fake users and exercises actual `anon` / `authenticated` roles; the final `ROLLBACK` removes all fixtures and temporary grants. With a separately supplied development connection string:

```sh
psql "$PARE_DEV_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f backend/supabase/tests/sync_v1.sql
```

`ON_ERROR_STOP` is required when using psql. An assertion failure aborts the transaction; closing that session rolls it back. Do not keep a failed test transaction open. The test is also plain SQL for execution in a trusted development SQL session. Do not copy real user IDs or production content into it.

The scripted checks cover authentication refusal, private table privileges, RLS default denial even after a temporary grant, account isolation, immutable receipts, changed-body/device rejection, all-or-conflict CAS, malformed/oversized input, tombstones, and complete-commit pagination. They do not prove concurrent-session behavior, HTTP/JWT integration, mobile network reachability, or client persistence. A real PostgreSQL execution and concurrency test are release gates; static review is not a passing database test.

For concurrency verification, use two trusted development SQL sessions and a disposable user. Session A begins a transaction and applies an operation without committing. Session B applies another operation for the same user: it must block on the stream row. A third session pulling must not see B's commit first. After A commits, B must receive the next sequence; after A rolls back, B must use the unconsumed sequence. Repeat with the same operation ID to check response-loss retries and a stale base revision to check conflict results. Clean up the disposable user afterwards. The single-session rollback test cannot perform this scenario.

## RPC contract

Clients send the project publishable key and the signed-in user's access token over HTTPS. Never ship a secret/service-role key. Ownership comes only from `auth.uid()`; there is no owner parameter.

`public.pare_sync_apply(p_operation_id UUID, p_device_id UUID, p_mutations JSONB)` accepts 1–100 mutations. Each mutation has exactly these fields:

```json
{
  "kind": "task",
  "id": "stable-entity-id",
  "base_revision": 0,
  "payload_version": 1,
  "deleted": false,
  "payload": {"title": "Example"}
}
```

Allowed kinds are `inbox`, `task`, `idea`, `goal`, `day_plan`, and `profile`. IDs must match `^[A-Za-z0-9_:@+./-]{1,160}$`: 1–160 ASCII characters suitable for UUIDs, `dayKey@IANA/timezone`, and fixed profile names. Whitespace, control characters and non-ASCII IDs are rejected; user-visible content remains Unicode JSON. SQL uses the `C` collation so validation is independent of database locale. `base_revision` is an integer from 0 through 9223372036854775806; zero requires the entity to be absent, including absence of a tombstone. `payload_version` is 1. Live payloads are JSON objects; deletion requires `deleted: true` and `payload: null`. Each `(kind, id)` may appear once per batch. Invalid requests raise SQLSTATE `22023`; missing authentication raises `42501`.

The authoritative 1 MiB limit measures `octet_length(p_mutations::text)` after PostgreSQL JSONB normalization. A client's compact-JSON length check is only a preflight check and does not guarantee server acceptance. JSON object whitespace/key order do not change idempotence; array order does. JSONB normalization can add spaces, so clients near the size ceiling must leave headroom rather than assuming their compact JSON byte count matches this check. RPC argument types also reject malformed UUIDs and numbers before entering the function.

Every response contains `protocol_version`, `status`, `sequence`, `entities`, and `conflicts`. Successful commits return all affected entities. A conflict returns no applied entities and all mismatched keys with their current entity, or `current: null` for an expected-but-missing entity. Conflict `sequence` is the user's stream position when the conflict was first processed; it does not advance the stream. It is not a replacement for the client's pull cursor.

An entity contains `kind`, `id`, `revision`, `payload_version`, `payload`, `deleted_at`, `last_device_id`, and `sequence`. Every entity in a successful batch shares one sequence and device ID; individual revisions increment by one. Times are server-generated UTC timestamps. The server retains tombstones indefinitely. A stale client cannot recreate a deleted item with revision zero or its pre-deletion revision. An explicit restore can use the tombstone's current revision.

The pair `(authenticated owner, operation_id)` identifies one immutable request, including its device ID and ordered mutations. Both success and conflict responses are retained. Exact retries always return the original response, even after later edits; reusing an ID with different content/device raises `22023`. Resolve conflicts with a new operation ID and freshly chosen base revisions.

A push receipt does not advance the client's pull cursor. Other devices may have committed unseen transactions before this acknowledgement, and an idempotent retry can return an older receipt. Only atomically applying complete pulled commits may advance that account's pull cursor.

`public.pare_sync_pull(p_after BIGINT = 0, p_limit INT = 50)` accepts a nonnegative cursor and a commit limit of 1–100. A cursor above this account's current stream sequence (zero before its first write) raises `22023`, preventing silent acceptance of a future/foreign restored cursor. It returns `protocol_version`, `cursor`, `has_more`, and `commits`. Each commit has `sequence`, `operation_id`, `device_id`, and a complete `entities` array containing immutable snapshots from that commit. Cursor advances only to the last returned commit, and an empty page preserves the input cursor. The stream high-water mark, page content and `has_more` come from one SQL statement snapshot. Clients must apply all entities in a commit and save the cursor in the same local transaction; account changes must reset the account-scoped cursor/outbox.

## Security and operational limits

All four private tables have RLS enabled and no policies, with schema/table access revoked from clients. Authenticated clients may execute only the two sync RPCs; `PUBLIC`, `anon`, and `service_role` are explicitly denied those function grants. Each `SECURITY DEFINER` function fixes `search_path` to an empty string, qualifies relations, verifies the authenticated user still exists, and filters every private-table read/write by that user. There is no dynamic SQL, filesystem access, HTTP call, or system command. Privileged database administrators remain able to access data; this is not end-to-end encryption.

The per-user `streams` row lock is held through transaction end. Sequence increments, item changes, immutable commit snapshots and receipts all commit or roll back together. This avoids the unsafe ordering of independent PostgreSQL sequence allocation. Clients should use one RPC per short transaction and retry transient transport/serialization failures with the same operation ID.

V1 retains all commit payloads, receipts and tombstones, so storage grows with editing history. Large pull limits can produce large responses (up to 100 complete batches); use smaller limits on constrained connections. Production rollout still needs request quotas/rate limits, retention and device retirement design, backup/recovery checks, security advisors, concurrency verification, and real two-device tests.

Payload objects are currently opaque to the database. The kind allowlist blocks separate API-configuration, token, history and timer entities; it cannot detect secrets embedded in an arbitrary task/profile body. Typed client DTO allowlists and privacy tests must exclude API keys, raw AI request history, local timers and device settings before uploads are enabled. Do not sync the existing application backup JSON wholesale.

Implementation references: [Supabase database functions](https://supabase.com/docs/guides/database/functions), [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [PostgreSQL row locks](https://www.postgresql.org/docs/current/explicit-locking.html).

## Development verification (2026-10-30)

The migration was applied to a new empty Free development project. The rollback SQL script passed on its actual PostgreSQL database; afterwards items, commits, receipts and fake users all counted zero. A macOS URLSession request with only the publishable project key received HTTP 401 / SQLSTATE 42501, confirming local reachability and rejection of anonymous RPC access. This is not yet a signed-in end-to-end client test.

Supabase advisors report two intentional categories: [RLS enabled without policies](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy) for the four private default-deny tables, and [authenticated SECURITY DEFINER functions](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable) for the two explicitly guarded RPCs. These were reviewed against their intended design; the report is not a claim that every production security check is complete. Independent-session concurrency, rate limits, authenticated HTTP flow and real two-device tests remain release gates.

Project-specific URL and publishable key may be stored in ignored `project.local.json`. No database password, service-role key, LLM key or real task data belongs in this directory or migration fixtures.

## Execution presence verification (2026-10-31)

The separate presence migration `20261031074438` is deployed. See the [deployment and acceptance record](docs/presence-v1-acceptance.md) for passing rollback SQL checks, 18 live HTTP checks using two Swift clients, opt-in and lease semantics, cleanup counts, and remaining security-advisor follow-up. This does not claim Android-process or two-platform GUI acceptance.
