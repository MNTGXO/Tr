plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mntgxo.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mntgxo.terminal"
        minSdk = 24
        // Deliberately 28 (same as Termux): apps targeting 29+ may not execve() files from their
        // own data directory, which a package-manager style terminal depends on.
        targetSdk = 28
        versionCode = 2
        versionName = "2.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // proot is shipped as lib*.so and must be extracted to disk so it can be executed.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(project(":terminal-view"))
}
