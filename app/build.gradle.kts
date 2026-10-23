plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gassplayer.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.gassgass.gassplayer"
        // Navigation 2.10.2 requires API 24+. Fire OS 6 (API 25+) and later Android-based Fire TV devices remain supported.
        minSdk = 24
        // Match GassTV’s tested target while compiling against the newer SDK needed by current dependencies.
        targetSdk = 35
        versionCode = 1001
        versionName = "1.0.0-android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        // java.time is used by the XMLTV parser; desugar it for older Android-based Fire TV devices.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    // libVLC e i decoder FFmpeg portano librerie native: se piu' AAR includono lo stesso runtime C++ se ne tiene uno.
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }
    androidResources { localeFilters += listOf("it", "en", "es") }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Decoder FFmpeg per Media3 (stessa versione 1.11.1): audio AC3/E-AC3/DTS/TrueHD/MP2/FLAC/Opus/ALAC e video
    // H.264/HEVC/MPEG-2/VP8/VP9/AV1 in software quando il dispositivo non ha il decoder (iOS: KSPlayer/FFmpeg).
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0")
    // Secondo motore (libVLC 3.x, FFmpeg + live555): RTMP/MMS/RTP/SRT/UDP, AVI/DivX/Xvid, WMV/ASF/VC-1, RealMedia
    // e qualunque flusso che ExoPlayer non riesce a decodificare.
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    // SVG channel logos (picons): without a decoder they never rendered.
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    // Published WireGuard Android tunnel artifact; 1.0.20260315 is not present on Maven Central.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
