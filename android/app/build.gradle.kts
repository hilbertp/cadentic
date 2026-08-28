plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.cadentic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cadentic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        /**
         * Where the Mesocycle Engine lives, per build type (Epic 2 story 5).
         *
         * Debug points at the dev machine. `10.0.2.2` is the emulator's alias for the host
         * loopback; on a physical device set `cadentic.engineBaseUrl` in
         * `~/.gradle/gradle.properties` or `local.properties` to the machine's LAN address
         * (`http://192.168.1.42:8787`) and start the backend with a matching `HOST`.
         *
         * The shared secret is a *development* credential: it stops anything else on the
         * LAN from spending the owner's subscription. It is not a user secret and is not the
         * Claude token — that never leaves the backend and appears nowhere in this app.
         */
        debug {
            buildConfigField(
                "String",
                "ENGINE_BASE_URL",
                "\"${project.findProperty("cadentic.engineBaseUrl") ?: "http://10.0.2.2:8787"}\"",
            )
            buildConfigField(
                "String",
                "ENGINE_SHARED_SECRET",
                "\"${project.findProperty("cadentic.engineSharedSecret") ?: ""}\"",
            )
        }
        release {
            isMinifyEnabled = false
            /**
             * Signed with the **debug** keystore. That is not a real release signing setup and
             * must not become one — it exists so a personal test build is installable on a
             * phone at all, since Android refuses unsigned APKs.
             *
             * Why bother with a release build: it is the only one with no cleartext
             * permission. Against an HTTPS engine, a debug build would still carry the
             * `src/debug/` network config that allows plain HTTP — harmless but untrue of how
             * the app now talks. Sharing the debug keystore also means this installs over an
             * existing debug build instead of demanding an uninstall.
             *
             * A real release needs its own keystore and `isMinifyEnabled = true`, and neither
             * is in scope until there is something to distribute.
             */
            signingConfig = signingConfigs.getByName("debug")
            // No default: a release build must be pointed at a real, TLS-terminated engine.
            // The cleartext dev host is debug-only and the release manifest does not permit it.
            buildConfigField(
                "String",
                "ENGINE_BASE_URL",
                "\"${project.findProperty("cadentic.engineBaseUrl") ?: ""}\"",
            )
            buildConfigField(
                "String",
                "ENGINE_SHARED_SECRET",
                "\"${project.findProperty("cadentic.engineSharedSecret") ?: ""}\"",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // The engine contract is a test resource, so ContractSchemaTest can assert that the
        // Kotlin types still match the same file the backend validates against. One file,
        // two consumers, no hand-mirrored enums (Epic 2 story 0).
        getByName("test") { resources.srcDir(rootProject.file("../contracts")) }
    }
    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // Artifact persistence (Epic 1): JSON documents on device.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    /**
     * HTTP + JSON stack (Epic 2 story 5, recorded per its ACs): **OkHttp with
     * kotlinx.serialization.** OkHttp because generation is a multi-minute call the athlete
     * can abandon, and `Call.cancel()` is a real cancellation — `HttpURLConnection` only
     * offers `disconnect()` from another thread, which is neither reliable nor prompt.
     * kotlinx.serialization because the artifacts already use it, so the plan the engine
     * returns and the plan written to disk decode through one serializer, not two.
     * Retrofit/Moshi would add a second JSON library and an interface layer over one endpoint.
     */
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
