plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "pl.karoonotify.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.karoonotify.phone"
        minSdk = 31 // Pixel 10 Pro = Android 16+, 31 wystarcza z zapasem
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/karoo-notify.jks")
            storePassword = "karoonotify"
            keyAlias = "karoonotify"
            keyPassword = "karoonotify"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
