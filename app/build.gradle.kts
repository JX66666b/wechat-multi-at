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
}

// 零外部依赖 - 全用Android原生API
dependencies {
}
