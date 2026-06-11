# Do In Place — Setup Guide

## Prerequisites

- Android Studio Meerkat (2024.3+)
- JDK 17
- Google Firebase project
- Google Cloud project with Maps SDK + Places API enabled

## Firebase Setup

1. Create a Firebase project at https://console.firebase.google.com
2. Add an Android app with package name `com.davoyan.remindinplace`
3. Download `google-services.json` and replace `app/google-services.json`
4. Enable **Email/Password** authentication in Firebase Console → Authentication
5. Enable **Firestore** in Firebase Console → Firestore Database (start in test mode, then apply `firestore.rules`)
6. Enable **Cloud Messaging** in Firebase Console → Cloud Messaging

## Google Maps / Places API

1. In Google Cloud Console, enable:
   - **Maps SDK for Android**
   - **Places API (New)**
2. Create an API key restricted to your app's package and SHA-1
3. Replace `YOUR_MAPS_API_KEY_HERE` in `app/src/main/AndroidManifest.xml`

## Build

```bash
cd do-in-place
./gradlew :app:assembleDebug
```

Output APK: `app/build/outputs/apk/debug/do-in-place-debug.apk`

## Firestore Security Rules

Deploy `firestore.rules`:
```bash
firebase deploy --only firestore:rules
```

## Architecture Summary

```
Room (local DB)      ← source of truth for reminders
  └── TaskDao, SavedPlaceDao, TaskEventDao, TrustedContactDao

Firestore            ← sync layer for shared tasks and contacts
  └── FirestoreClient.kt

FCM                  ← push for: new task invite, done, rejected, arrived
  └── ReminderFcmService.kt

Geofencing API       ← triggers local notifications on place entry
  └── GeofenceManager.kt + GeofenceBroadcastReceiver.kt

WorkManager          ← periodic Firestore sync every 15 min
  └── SyncWorker.kt

BootReceiver         ← restores geofences after device reboot
```

## Permission Flow

1. Notification permission (Android 13+)
2. Foreground location (ACCESS_FINE_LOCATION)
3. In-app explanation screen before background location
4. Background location (ACCESS_BACKGROUND_LOCATION)

The app works without location permissions but geofence reminders require them.

## Data Models

| Entity | Storage | Purpose |
|---|---|---|
| Task | Room + Firestore | Personal and shared reminders |
| SavedPlace | Room | Saved locations for quick reuse |
| TaskEvent | Room → Firestore | Offline event queue; synced when online |
| TrustedContact | Room + Firestore | Contacts who can assign tasks |

## Task Status Flow

```
PENDING_ACCEPTANCE → ACTIVE → DONE
                   → REJECTED
                   → CANCELLED
                   → EXPIRED
```

Shared tasks start as `PENDING_ACCEPTANCE`. The assignee accepts/rejects.
Personal tasks start as `ACTIVE` immediately.

## Privacy Guarantees

- No live location map
- No continuous GPS polling
- Arrival sharing is opt-in per task by the assignee
- Background location used only for registered active task geofences
- All location data stays on device; only task status events are synced
