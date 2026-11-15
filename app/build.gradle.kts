plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zhaoge.miragedroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhaoge.miragedroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            // qemu 以 libqemu-system-arm.so 的名字放进 jniLibs，运行时需要从
            // nativeLibraryDir 直接 exec 它 —— 必须保持解压（不压缩进 APK）。
            useLegacyPackaging = true
        }
    }

    // devicecore 的 JNI 工程落地之前（还没有 app/src/main/cpp/CMakeLists.txt），
    // 不挂 externalNativeBuild，保证 CI 始终可编；文件出现后自动生效。
    val jniCMake = file("src/main/cpp/CMakeLists.txt")
    if (jniCMake.exists()) {
        externalNativeBuild {
            cmake {
                path = jniCMake
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
