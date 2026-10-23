plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.viewcounter.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.viewcounter.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.5.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "viewcounter123"
            keyAlias = "viewcounter"
            keyPassword = "viewcounter123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
