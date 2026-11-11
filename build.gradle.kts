plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.example.uhdrcam"
    compileSdk = 35

    // Cada build de GitHub Actions lleva su propio número (GITHUB_RUN_NUMBER); en una compilación local queda 1
    val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.example.uhdrcam"
        minSdk = 34
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Clave fija guardada en el repo: así cada build nueva se instala encima de la anterior.
    // Es una clave de prueba para uso personal, no para publicar en Play Store.
    signingConfigs {
        create("fixed") {
            storeFile = file("keystore/uhdrcam.jks")
            storePassword = "uhdrcam123"
            keyAlias = "uhdrcam"
            keyPassword = "uhdrcam123"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
