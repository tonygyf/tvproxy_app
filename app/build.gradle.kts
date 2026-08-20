plugins {
    kotlin("android")
    id("com.android.application")
}

android {
    namespace = "com.tvip.proxy"

    defaultConfig {
        applicationId = "com.tvip.proxy"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    compileSdk = 34

    // :core 模块内部用 alpha/meta 两个变体来对应golang插件的两套源码集，
    // 这里只是为了让依赖解析不产生歧义，我们只用 alpha 这一个变体，忽略 meta。
    flavorDimensions += "feature"
    productFlavors {
        create("alpha") {
            dimension = "feature"
            isDefault = true
        }
        create("meta") {
            dimension = "feature"
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
