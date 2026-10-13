plugins { id("com.android.application") }
android {
    namespace = "dev.experimental.rmgu.boothelper"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.experimental.rmgu.boothelper"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
    }
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
