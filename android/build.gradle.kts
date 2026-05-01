plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.musync"
    compileSdk = 34

    /**
     * Build-time configuration values.
     *
     * These are sourced (in priority order) from:
     *   1. Gradle project properties (e.g. `-PSERVER_URL=...` or
     *      `ORG_GRADLE_PROJECT_SERVER_URL` env var, which is how GitHub Actions
     *      injects values from `vars`/`secrets`)
     *   2. Plain environment variables of the same name
     *   3. The hard-coded fallbacks below, which keep local emulator builds
     *      working out of the box.
     *
     * This makes the CI-built debug APK a fully-working artifact whose
     * endpoints can be pointed at any environment without code changes.
     */
    fun configValue(
        name: String,
        default: String,
    ): String {
        val fromProperty = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        val fromEnv = System.getenv(name)?.takeIf { it.isNotBlank() }
        return fromProperty ?: fromEnv ?: default
    }

    val serverUrl = configValue("SERVER_URL", "http://10.0.2.2:3000")
    val inviteLinkBaseUrl = configValue("INVITE_LINK_BASE_URL", "https://listen.yourdomain.com/room")
    val inviteLinkHost = configValue("INVITE_LINK_HOST", "listen.yourdomain.com")

    defaultConfig {
        applicationId = "com.musync"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.musync.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "INVITE_LINK_BASE_URL", "\"$inviteLinkBaseUrl\"")

        // Parameterise the deep-link host declared in AndroidManifest.xml so
        // installed APKs can be pointed at the same domain as the configured
        // invite-link base URL.
        manifestPlaceholders["inviteLinkHost"] = inviteLinkHost
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Required for Socket.IO to avoid duplicate file errors
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }

    testOptions {
        unitTests {
            // Return default values (0 / null / false) for stubbed Android framework
            // methods such as android.util.Log.* used by AppLogger, instead of throwing.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coroutines & Flow
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // YouTube Player
    implementation(libs.android.youtube.player)

    // Socket.IO
    implementation(libs.socket.io.client) {
        // Exclude the default engine.io-client to avoid version conflicts
        exclude(group = "org.json", module = "json")
    }
    implementation(libs.okhttp)

    // Material
    implementation(libs.material)

    // Image loading (used for YouTube thumbnails on CreateRoomScreen)
    implementation(libs.coil.compose)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
