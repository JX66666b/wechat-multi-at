plugins {
    id("com.android.application")
}

android {
    namespace = "com.seagull.multiat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seagull.multiat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Shizuku API - 用于在app内一键激活无障碍服务
    implementation("moe.shizuku:api:13.1.5")
}
