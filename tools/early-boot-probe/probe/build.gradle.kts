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

// Generated assets use the variant API so Gradle carries their producer dependency.
abstract class StagePrivilegedHelper : DefaultTask() {
    @get:InputFile abstract val helperApk: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun stage() {
        val destination = outputDirectory.get().asFile
        destination.mkdirs()
        helperApk.get().asFile.copyTo(destination.resolve("privileged-helper.apk"), overwrite = true)
    }
}
val stagePrivilegedHelper by tasks.registering(StagePrivilegedHelper::class) {
    dependsOn(":helper:assembleRelease")
    helperApk.set(project(":helper").layout.buildDirectory.file("outputs/apk/release/helper-release.apk"))
    outputDirectory.set(layout.buildDirectory.dir("generated/helperAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(stagePrivilegedHelper, StagePrivilegedHelper::outputDirectory)
}
