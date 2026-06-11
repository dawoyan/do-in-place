# Room — keep entity and DAO classes
-keep class com.davoyans.doinplace.data.model.** { *; }
-keep class com.davoyans.doinplace.data.db.** { *; }

# Kotlin serialization / reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod
-keep class kotlin.Metadata { *; }

# org.json (used by Supabase client)
-keep class org.json.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# Firebase / FCM
-keep class com.google.firebase.** { *; }

# Google Play Services
-keep class com.google.android.gms.** { *; }

# ZXing QR
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# Suppress notes for missing classes in third-party libs
-dontnote com.google.**
-dontnote android.content.**
