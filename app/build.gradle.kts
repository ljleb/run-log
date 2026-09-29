plugins {
    id("com.android.application")
}

android {
    namespace = "dev.local.runlog"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.local.runlog"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.2"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
