import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics.plugin)
}

val localProperties = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "io.vocaguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.vocaguard"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "1.0.7"

        // ARM-only. x86/x86_64 are emulator-only (no real phones), and LiteRT's
        // x86_64 libtensorflowlite_jni.so is still 4KB-aligned, which fails Play's
        // 16KB page-size requirement. Real 16KB devices are all arm64, so dropping
        // x86/x86_64 clears the check and shrinks the app with no user impact.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val secret = localProperties.getProperty("token_server_secret")
            ?: System.getenv("TOKEN_SERVER_SECRET")
            ?: ""
        buildConfigField("String", "TOKEN_SERVER_SECRET", "\"$secret\"")

        val sipPassword = localProperties.getProperty("sip_password")
            ?: System.getenv("SIP_PASSWORD")
            ?: "vocaguard123"
        buildConfigField("String", "SIP_PASSWORD", "\"$sipPassword\"")

        val tokenServerHost = localProperties.getProperty("token_server_host")
            ?: System.getenv("TOKEN_SERVER_HOST")
            ?: "api.vocaguard.com"
        buildConfigField("String", "TOKEN_SERVER_HOST", "\"$tokenServerHost\"")
    }

    // Signing config for CI — reads from environment variables set by the workflow.
    // For local release builds, place a keystore.jks in the project root and set
    // the four env vars, or configure a local.properties-based signing config.
    val ciKeyAlias     = System.getenv("SIGNING_KEY_ALIAS")     ?: localProperties.getProperty("signing.keyAlias")
    val ciKeyPassword  = System.getenv("SIGNING_KEY_PASSWORD")  ?: localProperties.getProperty("signing.keyPassword")
    val ciStorePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: localProperties.getProperty("signing.storePassword")
    val ciKeystore = rootProject.file("keystore.jks")

    signingConfigs {
        if (ciKeyAlias != null && ciKeyPassword != null && ciStorePassword != null && ciKeystore.exists()) {
            create("release") {
                storeFile = ciKeystore
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk { debugSymbolLevel = "FULL" }
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) signingConfig = releaseSigning
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        disable += "InvalidFragmentVersionForActivityResult"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        mlModelBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // LiteRT (formerly TensorFlow Lite)
    implementation("com.google.ai.edge.litert:litert:1.0.1")

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ViewModel + lifecycle-aware state collection
    implementation(libs.viewmodel.compose)
    implementation(libs.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)

    // WorkManager
    implementation(libs.workmanager.ktx)

    // Phone number parsing and E.164 normalization
    implementation(libs.libphonenumber)

    // Adaptive navigation (NavigationSuiteScaffold — auto-switches between bar/rail/drawer)
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")

    // EncryptedSharedPreferences for secure API key storage
    implementation(libs.security.crypto)

    // Firebase Crashlytics — degrades gracefully without google-services.json.
    // To fully enable: add app/google-services.json and apply both plugins (see CrashReporter.kt).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)
    implementation(libs.billing.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pinned to exact versions that ship 16 KB-page-aligned native libs
    // (required for targetSdk 35+; 5.3.x / 0.3.47 were 4 KB-aligned and Play
    // rejects them for production). See 16KB alignment check before bumping.
    implementation("org.linphone:linphone-sdk-android:5.4.125")
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(libs.room.testing)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}