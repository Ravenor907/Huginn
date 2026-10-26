import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One dot only, read as a decimal: 0.3 < 0.31 < 0.4 < 1.0. Huginn and Muninn share the repo's VERSION file.
val versionText = rootProject.file("../VERSION").readLines().first().trim()
val versionParts = Regex("""^(\d+)\.(\d+)$""").find(versionText)?.groupValues
    ?: error("VERSION must look like 0.3 or 0.31, not \"$versionText\"")
// Same ranking as the in-app updater, so every release installs over the one before it
// (old "0.3 Test 1" builds used codes in the thousands, far below these).
val versionNumber = ((versionParts[1].toLong() * 1_000_000 + (versionParts[2] + "000000").take(6).toLong()) * 10 + 5).toInt()

// Calls: libopus, built from source with the NDK (Android's own Opus encoder only exists from Android 10).
// Pinned version and SHA-256; fetched once into build/opus by fetchOpus, built by src/main/cpp/CMakeLists.txt.
val opusVersion = "1.5.2"
val opusSha256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
val opusDir = layout.buildDirectory.dir("opus").get().asFile
val opusSrc = File(opusDir, "opus-$opusVersion")

android {
    namespace = "io.github.ravenor907.muninn"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ravenor907.muninn"
        minSdk = 26
        targetSdk = 36
        versionCode = versionNumber
        versionName = versionText
        // Tor's native library ships for these. 32-bit x86 (old emulators only) is left out to keep the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DOPUS_SRC=${opusSrc.absolutePath}", "-DANDROID_STL=none") } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // The repo's shared test key, so every build installs over the last one.
        create("muninn") {
            storeFile = rootProject.file("signing/test-key.p12")
            storePassword = "muninn-test"
            keyAlias = "muninn"
            keyPassword = "muninn-test"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("muninn")
        }
        debug {
            signingConfig = signingConfigs.getByName("muninn")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compress Tor's native library inside the APK (Android unpacks it at install): the one
        // universal Muninn.apk stays far smaller to download, which matters because updates come through Tor.
        jniLibs.useLegacyPackaging = true
    }
}

val fetchOpus by tasks.registering {
    description = "Downloads the pinned libopus source and checks its SHA-256."
    outputs.dir(opusSrc)
    doLast {
        if (File(opusSrc, "CMakeLists.txt").exists()) return@doLast
        fun sha(f: File) = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        opusDir.mkdirs()
        val tgz = File(opusDir, "opus-$opusVersion.tar.gz")
        if (!tgz.exists() || sha(tgz) != opusSha256)
            java.net.URI("https://github.com/xiph/opus/releases/download/v$opusVersion/opus-$opusVersion.tar.gz").toURL().openStream()
                .use { input -> tgz.outputStream().use { input.copyTo(it) } }
        val got = sha(tgz)
        if (got != opusSha256) { tgz.delete(); throw GradleException("libopus $opusVersion download has SHA-256 $got, expected $opusSha256") }
        project.copy { from(project.tarTree(project.resources.gzip(tgz))); into(opusDir) }
    }
}
tasks.configureEach { if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchOpus) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.zxing:core:3.5.3") // QR codes for Direct connect
    implementation("com.google.android.gms:play-services-nearby:19.3.0") // close by: Bluetooth and Wi-Fi Direct
    implementation("androidx.fragment:fragment-ktx:1.8.5")   // Play services pulls an old Fragment; permission requests need 1.3+
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Nostr relays (WebSockets)
    implementation("androidx.work:work-runtime-ktx:2.10.0") // checking the inbox in the background
    // Tor, built in: every internet connection goes through it (Guardian Project, Maven Central).
    // Its POM asks for a newer Kotlin standard library than this project's compiler; the library
    // itself is Java, so keep the project's own.
    implementation("info.guardianproject:tor-android:0.4.9.14") { exclude(group = "org.jetbrains.kotlin") }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json is a stub in unit tests
}
