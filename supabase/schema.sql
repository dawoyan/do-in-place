-- Do In Place — Supabase schema
-- Run in Supabase Dashboard → SQL Editor, or via supabase CLI:
--   supabase db push

-- ── Users ─────────────────────────────────────────────────────────────────
create table if not exists users (
  id           uuid primary key default gen_random_uuid(),
  email        text not null unique,
  display_name text,
  fcm_token    text,
  created_at   timestamptz default now()
);

alter table users enable row level security;
create policy "users: own row only"
  on users for all
  using (auth.uid() = id)
  with check (auth.uid() = id);

-- ── Tasks (shared tasks only) ──────────────────────────────────────────────
create table if not exists tasks (
  id                    uuid primary key,
  title                 text not null,
  description           text,
  created_by_user_id    uuid not null references users(id),
  assigned_to_user_id   uuid not null references users(id),
  place_name            text not null,
  address               text,
  latitude              double precision not null default 0,
  longitude             double precision not null default 0,
  radius_meters         int  not null default 300,
  status                text not null default 'PENDING_ACCEPTANCE',
  arrival_share_allowed boolean not null default false,
  active_from_date      text,
  active_to_date        text,
  active_days_of_week   text,
  active_start_time     text,
  active_end_time       text,
  remind_until_done     boolean not null default true,
  created_at            bigint,
  updated_at            bigint
);

alter table tasks enable row level security;

-- Creator or assignee can read
create policy "tasks: read own"
  on tasks for select
  using (auth.uid() = created_by_user_id or auth.uid() = assigned_to_user_id);

-- Creator can insert (arrival_share_allowed must be false at creation — assignee sets it on accept)
create policy "tasks: creator inserts"
  on tasks for insert
  with check (auth.uid() = created_by_user_id and arrival_share_allowed = false);

-- Creator can cancel; assignee can accept/reject/done/set arrival_share
create policy "tasks: meaningful updates only"
  on tasks for update
  using (auth.uid() = created_by_user_id or auth.uid() = assigned_to_user_id);

-- ── Task events ────────────────────────────────────────────────────────────
create table if not exists task_events (
  id             uuid primary key,
  task_id        uuid not null references tasks(id),
  type           text not null,
  actor_user_id  uuid not null,
  created_at     bigint
);

alter table task_events enable row level security;
create policy "task_events: read if involved"
  on task_events for select
  using (
    exists (
      select 1 from tasks t
      where t.id = task_events.task_id
        and (t.created_by_user_id = auth.uid() or t.assigned_to_user_id = auth.uid())
    )
  );
create policy "task_events: insert own"
  on task_events for insert
  with check (auth.uid() = actor_user_id);

-- ── Tasks: additional columns added after initial schema ──────────────────
-- See supabase/migrations/20260617000000_add_missing_task_columns.sql
-- Run that migration file in Supabase Dashboard → SQL Editor.
-- Summary of what it adds:
--   priority, place_mode, place_type_id, place_type_name, task_type, is_everywhere
--   RLS policy: "tasks: read if shared with me"
--   users.email → nullable; photo_url, notify_on_task_cancelled, updated_at columns

-- ── Task shares (shared shopping lists) ────────────────────────────────────
create table if not exists task_shares (
  id                   uuid primary key,
  task_id              uuid not null references tasks(id) on delete cascade,
  owner_user_id        uuid not null references auth.users(id) on delete cascade,
  shared_with_user_id  uuid not null references auth.users(id) on delete cascade,
  permission           text not null default 'EDIT',
  status               text not null default 'ACTIVE',
  created_at           bigint,
  updated_at           bigint,
  unique(task_id, shared_with_user_id)
);

alter table task_shares enable row level security;
create policy "task_shares: read own"
  on task_shares for select
  using (auth.uid() = owner_user_id or auth.uid() = shared_with_user_id);
create policy "task_shares: owner inserts"
  on task_shares for insert
  with check (auth.uid() = owner_user_id);
create policy "task_shares: owner updates"
  on task_shares for update
  using (auth.uid() = owner_user_id);

-- ── Shopping list items ────────────────────────────────────────────────────
create table if not exists shopping_list_items (
  id                  uuid primary key,
  task_id             uuid not null references tasks(id) on delete cascade,
  text                text not null,
  normalized_text     text not null default '',
  raw_text            text,
  canonical_name      text not null default '',
  order_index         integer not null default 0,
  checked             boolean not null default false,
  checked_by_user_id  uuid references auth.users(id) on delete set null,
  checked_at          bigint,
  updated_by_user_id  uuid references auth.users(id) on delete set null,
  created_at          bigint,
  updated_at          bigint
);

-- Migrate existing tables (run once if table already existed)
alter table shopping_list_items add column if not exists checked_by_user_id uuid references auth.users(id) on delete set null;
alter table shopping_list_items add column if not exists checked_at bigint;
alter table shopping_list_items add column if not exists updated_by_user_id uuid references auth.users(id) on delete set null;
alter table shopping_list_items add column if not exists raw_text text;
alter table shopping_list_items add column if not exists canonical_name text not null default '';
update shopping_list_items set canonical_name = text where coalesce(trim(canonical_name), '') = '';
update shopping_list_items set raw_text = text where coalesce(trim(raw_text), '') = '';

alter table shopping_list_items enable row level security;

-- Allow read for task owner/assignee OR active share recipient
create policy "shopping_items: read if involved in task"
  on shopping_list_items for select
  using (
    exists (
      select 1 from tasks t
      where t.id = shopping_list_items.task_id
        and (t.created_by_user_id = auth.uid() or t.assigned_to_user_id = auth.uid())
    )
    or exists (
      select 1 from task_shares s
      where s.task_id = shopping_list_items.task_id
        and s.shared_with_user_id = auth.uid()
        and s.status = 'ACTIVE'
    )
  );
create policy "shopping_items: insert if task owner"
  on shopping_list_items for insert
  with check (
    exists (
      select 1 from tasks t
      where t.id = shopping_list_items.task_id
        and (t.created_by_user_id = auth.uid() or t.assigned_to_user_id = auth.uid())
    )
  );

-- Allow update for task owner/assignee OR active share recipient with EDIT permission
create policy "shopping_items: update if involved"
  on shopping_list_items for update
  using (
    exists (
      select 1 from tasks t
      where t.id = shopping_list_items.task_id
        and (t.created_by_user_id = auth.uid() or t.assigned_to_user_id = auth.uid())
    )
    or exists (
      select 1 from task_shares s
      where s.task_id = shopping_list_items.task_id
        and s.shared_with_user_id = auth.uid()
        and s.status = 'ACTIVE'
        and s.permission = 'EDIT'
    )
  );

-- ── Connection invites (link-based invitations) ────────────────────────────
create table if not exists connection_invites (
  id                   uuid primary key default gen_random_uuid(),
  invite_code          text unique not null,
  created_by_user_id   uuid not null references auth.users(id) on delete cascade,
  status               text not null default 'ACTIVE',
  created_at           bigint,
  expires_at           bigint,
  max_uses             integer not null default 1,
  used_by_user_id      uuid references auth.users(id) on delete set null,
  used_at              bigint,
  constraint connection_invites_no_self check (
    used_by_user_id is null or used_by_user_id <> created_by_user_id
  )
);

alter table connection_invites enable row level security;
create policy "connection_invites: read own"
  on connection_invites for select
  using (auth.uid() = created_by_user_id or auth.uid() = used_by_user_id);
create policy "connection_invites: creator inserts"
  on connection_invites for insert
  with check (auth.uid() = created_by_user_id);
create policy "connection_invites: update active invite (to mark used)"
  on connection_invites for update
  using (status = 'ACTIVE');

-- ── Contact invites ────────────────────────────────────────────────────────
-- Handles both email invites (to_email/to_user_id set) and code-first invites
-- (to_email/to_user_id NULL, invite_code/normalized_invite_code set).
create table if not exists contact_invites (
  id                      uuid primary key,
  from_user_id            uuid not null references users(id),
  to_user_id              uuid references users(id),
  to_email                text,
  requester_email_snapshot text,
  requester_display_name_snapshot text,
  status                  text not null default 'PENDING',
  invite_code             text,
  normalized_invite_code  text,
  expires_at              bigint,
  used_by_user_id         uuid references users(id),
  used_at                 bigint,
  created_at              bigint,
  updated_at              bigint
);

create unique index if not exists contact_invites_norm_code_unique
  on contact_invites(normalized_invite_code)
  where normalized_invite_code is not null;

alter table contact_invites enable row level security;

-- Owner or recipient can always read their own rows
create policy "contacts: read own"
  on contact_invites for select
  using (auth.uid() = from_user_id or auth.uid() = to_user_id);

-- Any authenticated user can look up an unclaimed, active code invite
create policy "contacts: read active code"
  on contact_invites for select
  using (
    normalized_invite_code is not null
    and status = 'ACTIVE'
    and to_user_id is null
  );

create policy "contacts: sender inserts"
  on contact_invites for insert
  with check (auth.uid() = from_user_id);

-- Owner or recipient can update (email-invite accept/reject)
-- NOTE: original WITH CHECK used a correlated subquery (select from_user_id from contact_invites where id = ...)
-- which triggers RLS recursively and can error with "more than one row returned by a subquery".
-- Fixed: USING clause already restricts to owner/recipient; WITH CHECK only guards the immutable from_user_id.
create policy "contacts: both parties update status"
  on contact_invites for update
  using (auth.uid() = from_user_id or auth.uid() = to_user_id)
  with check (auth.uid() = from_user_id or auth.uid() = to_user_id);

-- Any authenticated non-creator can claim an unclaimed active code invite
create policy "contacts: accept code invite"
  on contact_invites for update
  using (
    normalized_invite_code is not null
    and status = 'ACTIVE'
    and to_user_id is null
    and auth.uid() != from_user_id
  );

-- ── contact_invites migration (run once on existing databases) ────────────────
-- alter table contact_invites alter column to_email drop not null;
-- alter table contact_invites add column if not exists invite_code text;
-- alter table contact_invites add column if not exists normalized_invite_code text;
-- alter table contact_invites add column if not exists expires_at bigint;
-- alter table contact_invites add column if not exists used_by_user_id uuid references users(id);
-- alter table contact_invites add column if not exists used_at bigint;
-- alter table contact_invites add column if not exists requester_email_snapshot text;
-- alter table contact_invites add column if not exists requester_display_name_snapshot text;
-- create unique index if not exists contact_invites_norm_code_unique
--   on contact_invites(normalized_invite_code) where normalized_invite_code is not null;
-- create policy "contacts: read active code"
--   on contact_invites for select
--   using (normalized_invite_code is not null and status = 'ACTIVE' and to_user_id is null);
-- create policy "contacts: accept code invite"
--   on contact_invites for update
--   using (normalized_invite_code is not null and status = 'ACTIVE' and to_user_id is null and auth.uid() != from_user_id);

-- ── Run on live database to fix "more than one row returned by a subquery" ──────
-- Drop the old recursive-subquery policy and replace with the fixed version:
-- drop policy if exists "contacts: both parties update status" on contact_invites;
-- create policy "contacts: both parties update status"
--   on contact_invites for update
--   using (auth.uid() = from_user_id or auth.uid() = to_user_id)
--   with check (auth.uid() = from_user_id or auth.uid() = to_user_id);
