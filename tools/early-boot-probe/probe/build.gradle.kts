plugins { id("com.android.application") }

android {
    namespace = "dev.experimental.rmgu.bootprobe"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.experimental.rmgu.bootprobe"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")
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

// Always embed the matching release helper, signed by this run's same diagnostic key.
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/helperAssets"))
val stagePrivilegedHelper by tasks.registering(Copy::class) {
    dependsOn(":helper:assembleRelease")
    from(project(":helper").layout.buildDirectory.file("outputs/apk/release/helper-release.apk"))
    into(layout.buildDirectory.dir("generated/helperAssets"))
    rename { "privileged-helper.apk" }
}
tasks.matching { it.name == "mergeReleaseAssets" || it.name == "mergeDebugAssets" }.configureEach {
    dependsOn(stagePrivilegedHelper)
}
