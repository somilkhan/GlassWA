plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasswa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glasswa"
        minSdk = 27
        targetSdk = 35
        versionCode = (project.findProperty("buildVersionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.${project.findProperty("buildVersionCode") ?: "dev"}"
    }

    signingConfigs {
        create("persistent") {
            val keystorePath = System.getenv("GLASSWA_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("GLASSWA_STORE_PASSWORD")
                keyAlias = System.getenv("GLASSWA_KEY_ALIAS")
                keyPassword = System.getenv("GLASSWA_KEY_PASSWORD")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("persistent")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("persistent")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    compileOnly("de.robv.android.xposed:api:82")
}
