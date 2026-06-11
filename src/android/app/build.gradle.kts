import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Use the number of seconds/10 since Jan 1 2016 as the versionCode,
// so a new build can be uploaded at most every 10 seconds.
val autoVersion = (((System.currentTimeMillis() / 1000) - 1451606400) / 10).toInt()

fun runGit(vararg args: String): String = try {
    val output = ByteArrayOutputStream()
    ProcessBuilder("git", *args)
        .directory(project.rootDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.copyTo(output)
    output.toString().trim()
} catch (e: Exception) {
    ""
}

fun gitVersion(): String {
    val hash = runGit("rev-parse", "--short", "HEAD")
    return if (hash.isEmpty()) "0.0-unknown" else "0.1-$hash"
}

android {
    namespace = "com.hydra.threebeans"

    compileSdk = 35
    ndkVersion = "27.3.13750724"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.hydra.threebeans"
        minSdk = 24
        targetSdk = 35
        versionCode = autoVersion
        versionName = gitVersion()

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }

        buildConfigField("String", "GIT_HASH", "\"${runGit("rev-parse", "--short", "HEAD")}\"")
    }

    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASS")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        // Signed by the release key if provided, falling back to the debug key
        release {
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isJniDebuggable = true
        }
    }

    flavorDimensions.add("version")

    productFlavors {
        register("vanilla") {
            isDefault = true
            dimension = "version"
            versionNameSuffix = "-vanilla"
        }
        register("googlePlay") {
            dimension = "version"
            versionNameSuffix = "-googleplay"
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1+"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
}
