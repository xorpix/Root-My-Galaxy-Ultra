plugins { id("com.android.application") }

android {
    namespace = "dev.experimental.rmgu.bootprobe"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.experimental.rmgu.bootprobe"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes {
        release {
            // Separate diagnostic identity; never uses the RMGU release key.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
