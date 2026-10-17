plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The one place to change the app version.
val appVersion = "1.1.4"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "com.rfsat.pocketspectro"
    compileSdk = 36

    defaultConfig {
        // Permanent once published on Google Play.
        applicationId = "com.PocketSpectro"
        minSdk = 26
        targetSdk = 36
        // Edit only appVersion (top of this file). The Play version code follows from it:
        // 1.MINOR.PATCH -> 1*10000 + MINOR*100 + PATCH (1.1.2 -> 10102), so every new version
        // gets a new, higher code and Play never sees the same one twice.
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            // Same secret names as the other RFSAT apps (DBM, BAS, TinyRAD); absent locally,
            // in which case the release build stays unsigned.
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Pseudo-locales (en-XA, ar-XB) for checking that every on-screen text comes from strings.xml.
            isPseudoLocalesEnabled = true
        }
        release {
            // R8 shrinks the libraries (Compose, MapLibre, coroutines) and drops unused resources.
            // AGP puts the mapping file inside the bundle; Play picks it up as the deobfuscation file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Screenshots (Robolectric + Roborazzi) are only rendered when asked for:
            //   ./gradlew :app:testDebugUnitTest -Pscreenshots
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            if (!project.hasProperty("screenshots")) it.exclude("**/ScreenshotTest*")
            // Optional mirror for Robolectric's runtime download of android-all jars.
            project.findProperty("robolectricRepo")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url.toString()) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Same map engine as DBM's navigation screen.
    implementation("org.maplibre.gl:android-sdk:13.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
