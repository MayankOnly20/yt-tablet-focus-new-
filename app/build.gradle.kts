plugins {
    id("com.android.application")
}

android {
    namespace = "com.custom.youtubetablet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.custom.youtubetablet"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.mozilla.geckoview:geckoview:154.0.20260824154132")
}
