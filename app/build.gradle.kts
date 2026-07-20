plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dexoverlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dexoverlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "6.16"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "cyberpunk2077"
            keyAlias = System.getenv("KEY_ALIAS") ?: "cyberdeck"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "cyberpunk2077"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "Cyberpunk-DeX-HUD-v${defaultConfig.versionName}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
}
