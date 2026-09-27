package dev.busung.s25uroot

import android.content.Context
import android.provider.Settings
import java.io.File

/** Kept for the entire boot, including app death, failed activation and cancellation. */
internal object M3qBootGuard {
    private fun receipt(context: Context) = File(context.filesDir, "m3q-boot-attempt")

    fun currentCount(context: Context): String? = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.BOOT_COUNT)
            ?.takeIf { it.toIntOrNull()?.let { count -> count >= 0 } == true }
    }.getOrNull()

    fun isClaimed(context: Context): Boolean {
        // Read the file afresh: SharedPreferences caches are per process, while
        // the boot service and activity can both ask whether sweeping is safe.
        val file = receipt(context)
        if (!file.exists()) return false
        val saved = runCatching { file.readText().trim() }.getOrNull() ?: return true
        if (saved.toIntOrNull()?.let { it >= 0 } != true) return true
        val current = currentCount(context)
        return current == null || current == saved
    }

    fun remember(context: Context, count: String) {
        val temporary = File.createTempFile("m3q-attempt-", ".tmp", context.filesDir)
        try {
            temporary.outputStream().use { output ->
                output.write(count.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(receipt(context))) { "Cannot save the boot attempt guard; refusing." }
        } finally {
            temporary.delete()
        }
    }
}
