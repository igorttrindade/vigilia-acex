import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.vigilia.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vigilia.app"
        minSdk = 26
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${localProperties["SUPABASE_URL"]}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${localProperties["SUPABASE_KEY"]}\"")
    }

    signingConfigs {
        // Release keystore: gerada localmente com keytool, guardada fora do repo
        // (~/vigilia-release.jks). Credenciais vêm de local.properties (gitignored).
        // Se qualquer campo estiver ausente, o build release falha loudmente em vez
        // de silenciosamente cair pra debug signing — força configuração consciente.
        create("release") {
            val keystorePath = localProperties["RELEASE_KEYSTORE_PATH"] as? String
            val keystorePass = localProperties["RELEASE_KEYSTORE_PASSWORD"] as? String
            val alias = localProperties["RELEASE_KEY_ALIAS"] as? String
            val keyPass = localProperties["RELEASE_KEY_PASSWORD"] as? String
            if (!keystorePath.isNullOrBlank() && !keystorePass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Distribuição é APK direto (side-load), não Play Store. Release keystore
            // gerada localmente com keytool, credenciais em local.properties (gitignored).
            // Ver "History of decisions" em CLAUDE.md pra setup e backup do .jks.
            signingConfig = signingConfigs.getByName("release")
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
    }
    packaging {
        jniLibs {
            // MediaPipe native libs must be extracted to the filesystem;
            // the default compressed-in-APK approach fails on API < 29.
            useLegacyPackaging = true
        }
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
    //noinspection UseTomlInstead
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // MediaPipe
    implementation(libs.mediapipe.tasks.vision)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)

    // Supabase
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    releaseImplementation(libs.supabase.android)
    debugImplementation("io.github.jan-tennert.supabase:supabase-kt-android-debug:3.1.4")
    implementation(libs.ktor.android)
    implementation(libs.play.services.location)
    implementation(libs.androidx.work.runtime.ktx)

    // OpenCV — used only for CLAHE + gamma preprocessing in low-light mode. Loader init is
    // wrapped in try/catch so the app degrades gracefully if native libs fail on a device.
    implementation(libs.opencv)

    testImplementation("org.json:json:20231013")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}