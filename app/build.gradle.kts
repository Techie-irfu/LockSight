plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.locksight.guard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.locksight.guard"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

// Required so the .tflite model can be read directly from memory
    androidResources {
        noCompress += listOf("tflite", "onnx")
    }

    // Splits native ONNX libraries by CPU architecture -> shrinks phone APK from 95 MB to ~30 MB!
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // Aligns native .so libraries to 16 KB page boundaries for Android 15+
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Standard UI and Core Dependencies
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

// 16 KB-Aligned Microsoft ONNX Runtime (Zero Kotlin metadata mismatch)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")

    // Testing Dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
