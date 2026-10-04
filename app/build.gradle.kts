plugins {
    id("com.android.application")
}

android {
    namespace = "dev.mokomis.adrenofloor"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mokomis.adrenofloor"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
