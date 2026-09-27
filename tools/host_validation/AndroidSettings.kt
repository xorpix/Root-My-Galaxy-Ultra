package android.provider
import android.content.ContentResolver
object Settings { object Global {
    const val BOOT_COUNT = "boot_count"
    fun getString(resolver: ContentResolver, name: String): String? = resolver.bootCount
} }
