plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "cz.zaoo.tiskwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.zaoo.tiskwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = runNo
        versionName = "1.0.$runNo"
    }

    signingConfigs {
        create("zaoo") {
            storeFile = file("zaoo.keystore")
            storePassword = "tiskwidget"
            keyAlias = "zaoo"
            keyPassword = "tiskwidget"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zaoo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
