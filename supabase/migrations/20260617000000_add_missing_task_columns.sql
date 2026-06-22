-- Migration: add columns that the Android app sends/reads but were missing from tasks table
-- Run in Supabase Dashboard → SQL Editor

-- ── Tasks: columns used by Android app but not in original schema ──────────
alter table public.tasks add column if not exists priority text not null default 'NO_RUSH';
alter table public.tasks add column if not exists place_mode text not null default 'EXACT';
alter table public.tasks add column if not exists place_type_id text;
alter table public.tasks add column if not exists place_type_name text;
alter table public.tasks add column if not exists task_type text not null default 'SIMPLE';
alter table public.tasks add column if not exists is_everywhere boolean not null default false;

-- ── Tasks: allow reading tasks shared via task_shares ─────────────────────
-- Without this, share recipients cannot fetch the task content.
do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename  = 'tasks'
      and policyname = 'tasks: read if shared with me'
  ) then
    execute $policy$
      create policy "tasks: read if shared with me"
        on public.tasks for select
        using (
          exists (
            select 1 from public.task_shares s
            where s.task_id = tasks.id
              and s.shared_with_user_id = auth.uid()
              and s.status = 'ACTIVE'
          )
        )
    $policy$;
  end if;
end
$$;

-- ── Users: make email nullable (Google OAuth users may have no email) ──────
alter table public.users alter column email drop not null;
alter table public.users alter column email set default '';

-- ── Users: add optional columns used by Android app ──────────────────────
alter table public.users add column if not exists photo_url text;
alter table public.users add column if not exists notify_on_task_cancelled boolean not null default true;
alter table public.users add column if not exists updated_at bigint;

-- ── Reload PostgREST schema cache so new columns are visible immediately ──
notify pgrst, 'reload schema';
