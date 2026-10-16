package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** A durable claim shared by the UI and both boot processes, including before first unlock. */
internal class KernelBootClaim(private val file: File) {
    companion object {
        private val monitor = Any()
        private val tokenPattern = Regex("[A-Za-z0-9_.-]{1,128}")
    }

    fun claimed(token: String): Boolean = access(token, false)
    fun claim(token: String): Boolean = access(token, true)

    private fun access(token: String, write: Boolean): Boolean = synchronized(monitor) {
        require(tokenPattern.matches(token)) { "The kernel boot identity is unavailable" }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        RandomAccessFile(file, "rw").use { record ->
            record.channel.lock().use {
                check(record.length() <= 128) { "The boot attempt record is invalid; refusing another attempt" }
                val bytes = ByteArray(record.length().toInt())
                record.readFully(bytes)
                val previous = bytes.toString(Charsets.US_ASCII)
                check(previous.isEmpty() || tokenPattern.matches(previous)) {
                    "The boot attempt record is invalid; refusing another attempt"
                }
                if (!write) return@synchronized previous == token
                if (previous == token) return@synchronized false
                record.seek(0)
                record.write(token.toByteArray(Charsets.US_ASCII))
                record.setLength(token.length.toLong())
                record.fd.sync()
                true
            }
        }
    }
}

internal object DfBootAttempt {
    private fun directory(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, "df-boot-claims")
    private fun file(context: Context, token: String) = KernelBootClaim(File(directory(context),
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()))

    fun claimed(context: Context, token: String): Boolean = file(context, token).claimed(token)

    fun claim(context: Context) {
        val token = requireNotNull(kernelBootToken()) { "Cannot identify this kernel boot; root was not started" }
        check(file(context, token).claim(token)) {
            "A DirtyFrag attempt has already started in this kernel boot. Fully reboot before another attempt."
        }
        // Old claims never gate a new kernel, including a damaged record from a previous boot.
        val keep = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()
        directory(context).listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
    }
}
