plugins {
    id("com.android.library")
}

android {
    namespace = "com.github.kr328.clash.hideapi"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
