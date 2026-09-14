plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "isro.itantra"
    compileSdk = 36

    defaultConfig {
        applicationId = "isro.itantra"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // P7 replaces this with the real release keystore; debug key keeps the
            // release APK installable for on-device testing until then
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // per-ABI APKs keep the base download small on low-end phones (budget §8);
    // universal remains for emulator/side-load convenience
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation(libs.junit4)
}
