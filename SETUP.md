# Do In Place — Setup & Next Steps

## What you need before the app works end-to-end

| Service | Required for | Free tier |
|---|---|---|
| Geoapify | Place name search in the picker | 3 000 req/day |
| Supabase | Auth, task sync, contacts | 500 MB DB, 50 000 MAU |
| Google Cloud / Firebase | Google Sign-In button, FCM push notifications | Free |

---

## 1. Geoapify — place search

### Get the key

1. Go to **https://www.geoapify.com**
2. Click **Sign up** (Google or email — free, no credit card)
3. Open the dashboard → **API Keys** → **Create API key**
4. Name it anything, e.g. `do-in-place-dev`
5. Copy the key (looks like `abc123def456...`)

### Paste it

Open `local.properties` in the project root and replace the placeholder:

```properties
GEOAPIFY_API_KEY=abc123def456...   ← paste your key here
```

### Rebuild

```bash
cd C:\BlitzBank_android_app\do-in-place
./gradlew :app:assembleDebug
```

APK lands at `app/build/outputs/apk/debug/do-in-place-debug.apk`
and is copied automatically to `C:\BlitzBank_android_app\apks\do-in-place.apk`.

### Test

1. Install the APK on a device or emulator.
2. Log in → create task → tap **Search** button.
3. Type "Dalma" → wait ~1 s → suggestions appear.
4. Tap a suggestion → place is saved and filled into the task form.

> **Rate limit:** 3 000 free requests/day. The app debounces (700 ms) and
> caches results per session, so normal usage stays well within the limit.

---

## 2. Supabase — required tables (run this SQL first!)

Open **Supabase Dashboard → SQL Editor** and run the following. Without this,
tasks and friend invites will not sync between devices.

```sql
-- ────────────────────────────────────────────────────────────────────────────
-- Tasks table with RLS — allows users to see tasks they created or are assigned to
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tasks (
    id                      UUID        PRIMARY KEY,
    title                   TEXT        NOT NULL,
    description             TEXT,
    created_by_user_id      UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    assigned_to_user_id     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    place_name              TEXT        NOT NULL,
    address                 TEXT,
    latitude                FLOAT8      NOT NULL,
    longitude               FLOAT8      NOT NULL,
    radius_meters           INTEGER     DEFAULT 100,
    status                  TEXT        DEFAULT 'ACTIVE',
    arrival_share_allowed   BOOLEAN     DEFAULT FALSE,
    active_from_date        TEXT,
    active_to_date          TEXT,
    active_days_of_week     TEXT,
    active_start_time       TEXT,
    active_end_time         TEXT,
    remind_until_done       BOOLEAN     DEFAULT TRUE,
    created_at              BIGINT,
    updated_at              BIGINT
);

ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;

-- Users can read tasks they created or are assigned to
CREATE POLICY "tasks_select" ON tasks FOR SELECT
    USING (created_by_user_id = auth.uid() OR assigned_to_user_id = auth.uid());

-- Users can insert tasks only where they are the creator
CREATE POLICY "tasks_insert" ON tasks FOR INSERT
    WITH CHECK (created_by_user_id = auth.uid());

-- Users can update tasks they created or are assigned to (for status updates)
CREATE POLICY "tasks_update" ON tasks FOR UPDATE
    USING (created_by_user_id = auth.uid() OR assigned_to_user_id = auth.uid());

-- ────────────────────────────────────────────────────────────────────────────
-- Task events table — log of who accepted/rejected/completed tasks
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS task_events (
    id              UUID        PRIMARY KEY,
    task_id         UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    type            TEXT        NOT NULL,
    actor_user_id   UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at      BIGINT
);

-- ────────────────────────────────────────────────────────────────────────────
-- Trusted contact invites (bidirectional friend requests)
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS contact_invites (
    id           UUID        PRIMARY KEY,
    from_user_id UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    to_user_id   UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    to_email     TEXT,
    status       TEXT        NOT NULL DEFAULT 'PENDING',
    created_at   BIGINT,
    updated_at   BIGINT
);

ALTER TABLE contact_invites ENABLE ROW LEVEL SECURITY;

-- Users can see invites they sent or received
CREATE POLICY "contacts_select" ON contact_invites FOR SELECT
    USING (from_user_id = auth.uid() OR to_user_id = auth.uid());

-- Users can only insert invites where they are the sender
CREATE POLICY "contacts_insert" ON contact_invites FOR INSERT
    WITH CHECK (from_user_id = auth.uid());

-- Sender or recipient can update status
CREATE POLICY "contacts_update" ON contact_invites FOR UPDATE
    USING (from_user_id = auth.uid() OR to_user_id = auth.uid());
```

> **Why tasks don't sync**: The `tasks` table must exist with RLS so that UserA
> can create tasks and UserB can read tasks assigned to them. If the RLS policy
> is missing or the table doesn't exist, the query succeeds but returns empty
> results, so UserB's device never sees the task. **After running this SQL,
> pull-to-refresh (swipe down on Home) will sync tasks to other users' devices.**

---

## 3. Supabase — auth and data sync keys

Your keys are already in `local.properties`. This section is only needed if
you create a new project or rotate keys.

```properties
supabase.url=https://<project-ref>.supabase.co
supabase.anonKey=sb_publishable_...
```

### Where to find them

1. Open **https://supabase.com/dashboard**
2. Select your project → **Project Settings** → **API**
3. Copy **Project URL** and **anon / public** key

### What Supabase stores

- User profiles (created on first sign-in)
- Tasks pushed/pulled by `SyncWorker`
- Trusted contact invites
- FCM token (for push notifications to other users)

---

## 3. Google Sign-In — web client ID

The Google Sign-In button needs a Web Client ID from your Firebase project.

### Steps

1. Open **https://console.firebase.google.com**
2. Select (or create) your project
3. Go to **Project settings** → **General** → scroll to **Your apps**
4. Click the Android app → download `google-services.json`
5. Place the file at: `app/google-services.json`  
   *(it's already referenced by the `google-services` Gradle plugin)*
6. In Firebase Console → **Authentication** → **Sign-in method** →
   enable **Google**
7. Copy the **Web client ID** shown there (format: `…apps.googleusercontent.com`)

### Paste it

```properties
google.webClientId=389360985083-xxxxxx.apps.googleusercontent.com
```

---

## 4. Firebase Cloud Messaging — push notifications

FCM lets you notify another user when they receive a task.

### Enable it

1. In Firebase Console → **Cloud Messaging** — already enabled by default
2. Make sure `google-services.json` is in place (step 3 above covers this)
3. No extra key needed — the app uses the Firebase SDK automatically

### Test

- Create a task and assign it to a contact.
- The contact's device should receive a push notification.
- If it doesn't arrive, check the Supabase Edge Function that sends the FCM call.

---

## 5. local.properties — full reference

```properties
sdk.dir=C:\BlitzBank_android_app\BlitzBank\android-sdk

# Supabase
supabase.url=https://<ref>.supabase.co
supabase.anonKey=sb_publishable_...

# Google OAuth
google.webClientId=<number>-<hash>.apps.googleusercontent.com

# Geoapify (free place search)
GEOAPIFY_API_KEY=<your_key>
```

> `local.properties` is in `.gitignore` — never commit it.
> Other developers clone the repo and fill in their own keys.

---

## 6. Build commands

Run from `C:\BlitzBank_android_app\do-in-place\`:

```bash
# Debug APK (fast, for testing)
./gradlew :app:assembleDebug

# Release AAB (for Google Play upload)
./gradlew :app:bundleRelease
```

For Play Store release you also need a signing keystore — see
[Android signing docs](https://developer.android.com/studio/publish/app-signing).

---

## 7. Quick QA checklist

- [ ] Type a place name in the picker → suggestions appear within 1 s
- [ ] Select a suggestion → lat/lng saved, task form fills in
- [ ] Reopen app → saved place still visible in the picker
- [ ] Tap ✕ on a saved place → it disappears; existing tasks keep their place snapshot
- [ ] Turn off Wi-Fi → search shows "Place search is temporarily unavailable" (no crash)
- [ ] Launcher icon appears on home screen (not a generic Android icon)
- [ ] Round icon appears correctly in circular icon launchers

---

## 8. Replacing Geoapify later

All place search is isolated in two files:

| File | Role |
|---|---|
| `data/location/PlaceSearchProvider.kt` | Interface |
| `data/location/GeoapifyPlaceSearchProvider.kt` | Implementation |

To switch to LocationIQ, Nominatim, or any other provider:

1. Create `LocationIqPlaceSearchProvider.kt` implementing `PlaceSearchProvider`
2. Swap the `remember { GeoapifyPlaceSearchProvider() }` line in `PlacePickerScreen.kt`
3. No other UI or repository changes needed

---

## 9. What's not required for the free MVP

| Thing | Why not needed |
|---|---|
| Google Maps SDK | App stores coordinates as numbers; no map is displayed |
| Google Places API | Replaced by Geoapify |
| Google Maps API key | Removed from manifest |
| Background location (on install) | Only requested when user enables a place reminder |
