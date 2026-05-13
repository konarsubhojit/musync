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
    compileSdk = 35

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

    // Derive the deep-link host from INVITE_LINK_BASE_URL automatically.
    // INVITE_LINK_HOST only needs to be set when the invite-link host differs
    // from the INVITE_LINK_BASE_URL host (an uncommon scenario).
    //
    // The derived value is sanitised to a bare hostname because android:host in
    // AndroidManifest.xml must not contain a scheme (https://) or path (/room).
    // If someone accidentally configures INVITE_LINK_HOST as a full URL
    // (e.g. "https://musync-5mdf.onrender.com") the scheme and any trailing
    // path/port are stripped so the manifest placeholder is always correct.
    fun extractBareHost(raw: String): String {
        // Strip any accidental scheme prefix, then take only the authority
        // (host[:port]) before the first path separator, then drop any port.
        // This makes android:host safe for any combination of inputs:
        //   "musync.example.com"              → "musync.example.com"
        //   "https://musync.example.com"      → "musync.example.com"
        //   "https://musync.example.com/room" → "musync.example.com"
        //   "musync.example.com:3000"         → "musync.example.com"
        val withoutScheme = raw.removePrefix("https://").removePrefix("http://")
        val authority = withoutScheme.substringBefore("/")
        val host = authority.substringBefore(":")
        return host.trim().ifEmpty { raw }
    }

    val derivedInviteLinkHost = extractBareHost(inviteLinkBaseUrl)
    val inviteLinkHostRaw = configValue("INVITE_LINK_HOST", derivedInviteLinkHost)
    val inviteLinkHost = extractBareHost(inviteLinkHostRaw)

    if (inviteLinkHostRaw != inviteLinkHost) {
        logger.warn(
            "[musync] INVITE_LINK_HOST was '$inviteLinkHostRaw' — sanitised to bare hostname '$inviteLinkHost' " +
                "for AndroidManifest android:host. Set INVITE_LINK_HOST to '$inviteLinkHost' to suppress this warning.",
        )
    }

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

        // Inject the sanitised bare hostname into AndroidManifest.xml so the
        // deep-link <intent-filter> matches https://<host>/room/<id> URLs.
        // INVITE_LINK_BASE_URL is used to build the actual invite link URL;
        // this placeholder only controls which host the OS intercepts.
        manifestPlaceholders["inviteLinkHost"] = inviteLinkHost
    }

    // ── Deterministic debug signing ───────────────────────────────────────────
    // When all four env vars below are set (e.g. in CI), the debug build type
    // is signed with the supplied keystore so the SHA-256 fingerprint is stable
    // across machines.  This lets `ANDROID_APP_SHA256_FINGERPRINTS` on the
    // server stay fixed for CI-built debug APKs used with Android App Links.
    //
    // Required environment variables (all must be non-blank to take effect):
    //   DEBUG_KEYSTORE_PATH      – absolute path to the .keystore / .jks file
    //   DEBUG_KEYSTORE_PASSWORD  – store password
    //   DEBUG_KEY_ALIAS          – key alias inside the keystore
    //   DEBUG_KEY_PASSWORD       – key password
    //
    // When any variable is absent the debug build falls back to the default
    // Android debug keystore so local development is never broken.
    val debugKeystorePath = System.getenv("DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val debugKeystorePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val debugKeyAlias = System.getenv("DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val debugKeyPassword = System.getenv("DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

    val hasCustomDebugSigning =
        listOf(
            debugKeystorePath,
            debugKeystorePassword,
            debugKeyAlias,
            debugKeyPassword,
        ).all { it != null }

    if (hasCustomDebugSigning) {
        signingConfigs {
            create("debugCustom") {
                storeFile = file(debugKeystorePath!!)
                storePassword = debugKeystorePassword!!
                keyAlias = debugKeyAlias!!
                keyPassword = debugKeyPassword!!
            }
        }
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
            if (hasCustomDebugSigning) {
                signingConfig = signingConfigs.getByName("debugCustom")
            }
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
    implementation(libs.androidx.compose.material3.window.size)
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

    // Media session + notification controls (background playback)
    implementation(libs.androidx.media)

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

    // DataStore
    implementation(libs.androidx.datastore.preferences)

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
