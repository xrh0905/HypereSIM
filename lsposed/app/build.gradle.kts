plugins {
    id("com.android.application")
}

// Release signing: HYPERESIM_KEYSTORE wins, otherwise the committed CI key keeps
// local and CI artifacts signature-compatible. Debug builds stay on the debug key.
val ciKeystore = rootProject.file("keystore/hyperesim-release.jks")
val releaseKeystore = System.getenv("HYPERESIM_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    ?: ciKeystore.takeIf { it.isFile }
val releaseStorePassword = System.getenv("HYPERESIM_KEYSTORE_PASSWORD")
    ?: if (releaseKeystore == ciKeystore) "hyperesim" else null

android {
    namespace = "io.github.xrh0905.hyperesim"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xrh0905.hyperesim"
        minSdk = 33
        targetSdk = 36
        versionCode = 1000
        versionName = "v1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseKeystore != null && releaseStorePassword != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = System.getenv("HYPERESIM_KEY_ALIAS") ?: "hyperesim"
                keyPassword = System.getenv("HYPERESIM_KEY_PASSWORD") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
