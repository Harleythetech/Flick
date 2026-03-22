plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.ultraelectronica.flick"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"  // fixed deprecation: plain string, not JavaVersion.toString()
    }

    defaultConfig {
        applicationId = "com.ultraelectronica.flick"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    packaging {  // fixed deprecation: renamed from packagingOptions
        jniLibs {
            useLegacyPackaging = true
            // Keep libc++_shared.so for Rust library
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

// Copy libc++_shared.so from NDK before building
tasks.register<Exec>("copyNdkLibs") {
    description = "Copy libc++_shared.so from Android NDK to jniLibs"
    commandLine("bash", "${projectDir}/../copy_ndk_libs.sh")
}

// Make preBuild depend on copyNdkLibs
tasks.named("preBuild") {
    dependsOn("copyNdkLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
}
