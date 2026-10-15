import java.util.Properties
import java.security.MessageDigest

plugins { id("com.android.application") }

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingProperty(env: String, property: String): String? =
    System.getenv(env)?.takeIf(String::isNotBlank) ?: keystoreProperties.getProperty(property)

val helperCode = 1
val sourceInputs = files(
    fileTree("src/main"),
    fileTree(rootProject.file("d2-shared/src/main/java")),
    file("build.gradle.kts"), rootProject.file("build.gradle.kts"),
)
val sourceFingerprint = MessageDigest.getInstance("SHA-256").run {
    sourceInputs.files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach {
        update(it.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        update(0.toByte())
        update(it.readBytes())
    }
    digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
abstract class D2IdentityTask : DefaultTask() {
    @get:Input abstract val fingerprint: Property<String>
    @get:Input abstract val code: Property<Int>
    @get:OutputFile abstract val output: RegularFileProperty
    @TaskAction fun writeIdentity() {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""{"api":1,"package":"dev.experimental.azhlroot.d2helper","version":${code.get()},"source":"${fingerprint.get()}"}""")
        }
    }
}
tasks.register<D2IdentityTask>("writeD2Identity") {
    fingerprint.set(sourceFingerprint)
    code.set(helperCode)
    output.set(layout.buildDirectory.file("outputs/d2-identity.json"))
}

android {
    namespace = "dev.experimental.rmgu.d2helper"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.experimental.azhlroot.d2helper"
        minSdk = 33
        targetSdk = 36
        versionCode = helperCode
        versionName = "1.0"
        buildConfigField("String", "SOURCE_SHA256", "\"$sourceFingerprint\"")
    }
    sourceSets.getByName("main").java.srcDir(rootProject.file("d2-shared/src/main/java"))
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("release") {
            signingProperty("KEYSTORE_FILE", "storeFile")?.let {
                storeFile = rootProject.file(it)
                storeType = signingProperty("KEYSTORE_TYPE", "storeType") ?: "PKCS12"
                storePassword = signingProperty("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingProperty("KEY_ALIAS", "keyAlias")
                keyPassword = signingProperty("KEY_PASSWORD", "keyPassword")
            }
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
        debug {
            isDebuggable = false
            signingConfigs.getByName("release").storeFile?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
