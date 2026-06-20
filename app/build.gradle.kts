import java.util.Properties
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")  // still needed for FCM
    id("org.jetbrains.kotlin.kapt")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String) = localProps.getProperty(key, "").trim()

android {
    namespace = "com.davoyans.doinplace"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davoyans.doinplace"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "2.4.6"

        buildConfigField("String", "SUPABASE_URL",          "\"${localProp("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",     "\"${localProp("supabase.anonKey")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID",  "\"${localProp("google.webClientId")}\"")
        buildConfigField("String", "GEOAPIFY_API_KEY",      "\"${localProp("GEOAPIFY_API_KEY")}\"")
        buildConfigField("String", "WEB_APP_URL",           "\"${localProp("web.appUrl")}\"")
        buildConfigField("String", "BUILD_TIME",
            "\"${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProp("signing.storeFile")
            storeFile = if (storeFilePath.isNotEmpty())
                rootProject.file(storeFilePath) else null
            storePassword = localProp("signing.storePassword")
            keyAlias      = localProp("signing.keyAlias")
            keyPassword   = localProp("signing.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("renameDebugApk") {
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val src = apkDir.resolve("app-debug.apk")
        if (src.exists()) src.copyTo(apkDir.resolve("do-in-place-debug.apk"), overwrite = true)
    }
}

tasks.register("archiveDebugApk") {
    dependsOn("renameDebugApk")
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val src = apkDir.resolve("do-in-place-debug.apk")
        if (!src.exists()) return@doLast

        val archiveDir = rootProject.rootDir.parentFile.resolve("apks").apply { mkdirs() }
        val pattern = Regex("""do-in-place-(\d+)\.apk""")
        val nextNumber = archiveDir.listFiles()
            ?.mapNotNull { file -> pattern.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull()
            ?.plus(1)
            ?: 1

        src.copyTo(archiveDir.resolve("do-in-place-$nextNumber.apk"), overwrite = true)
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("archiveDebugApk")
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Room
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // AppCompat — required for AppCompatDelegate.setApplicationLocales() locale switching
    implementation("androidx.appcompat:appcompat:1.7.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Firebase — FCM only (auth and data are handled by Supabase)
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Google Play Services – Location + Geofencing + Sign-In
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // QR code generation (pure Java, no Android deps)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    // On-device OCR — thin client: model downloaded once via Play Services, not bundled in APK
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // HTML parsing for article summary extraction
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
