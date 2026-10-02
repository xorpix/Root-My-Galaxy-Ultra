package android.content
import java.io.File
class ContentResolver(var bootCount: String? = "7")
class ApplicationInfo(val nativeLibraryDir: String)
class AssetManager(private val base: File) { fun open(name: String) = File(base, name).inputStream() }
class Context(val filesDir: File, val applicationInfo: ApplicationInfo, val assets: AssetManager) {
    val contentResolver = ContentResolver()
}
