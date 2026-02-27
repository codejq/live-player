import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.streamplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streamplayer.app"
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Signing config — reads from local.properties (local dev) or env vars (CI/GitHub Actions).
    // local.properties is gitignored; env vars are injected by the GitHub Actions workflow.
    val localProps = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) load(localPropsFile.inputStream())
    }
    fun signingProp(key: String): String? = localProps.getProperty(key) ?: System.getenv(key)

    val keystorePath     = signingProp("KEYSTORE_PATH")
    val keystorePassword = signingProp("KEYSTORE_PASSWORD")
    val keyAlias         = signingProp("KEY_ALIAS")
    val keyPassword      = signingProp("KEY_PASSWORD")

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
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
            // Apply signing config when keystore info is available (local dev or CI).
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "StreamPlayer-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.workmanager)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)   // LifecycleService base class
    implementation(libs.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity.ktx)
    implementation(libs.media.compat)    // androidx.media.app.NotificationCompat.MediaStyle
}
