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

android {
    namespace = "io.github.ravenor907.muninn"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ravenor907.muninn"
        minSdk = 26
        targetSdk = 36
        versionCode = versionNumber
        versionName = versionText
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
    }
}

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
    implementation("org.apache.commons:commons-compress:1.27.1") // bzip2 for SteamOS images
    implementation("org.tukaani:xz:1.10") // .xz images
    implementation("com.google.zxing:core:3.5.3") // QR codes for Direct connect
    implementation("com.google.android.gms:play-services-nearby:19.3.0") // close by: Bluetooth and Wi-Fi Direct
    implementation("androidx.fragment:fragment-ktx:1.8.5")   // Play services pulls an old Fragment; permission requests need 1.3+
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Nostr relays (WebSockets)
    implementation("androidx.work:work-runtime-ktx:2.10.0") // checking the inbox in the background

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json is a stub in unit tests
}
