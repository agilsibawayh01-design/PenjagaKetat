plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mas.strictguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mas.strictguard"
        minSdk = 29
        targetSdk = 34
        val buildNo = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Dibangun dengan kunci lama (debug.keystore); langkah "Sign release" di workflow lalu
            // menandatangani ulang dengan kunci baru + rotasi (lineage) bila GitHub Secrets diisi,
            // sehingga APK tetap menimpa aplikasi lama tanpa uninstall dan tanpa kehilangan data.
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
