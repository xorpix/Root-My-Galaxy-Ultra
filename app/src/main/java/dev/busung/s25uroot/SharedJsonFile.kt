package dev.busung.s25uroot

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reopened reads and locked, atomic writes shared by the UI and boot workers. */
internal class SharedJsonFile(private val file: File) {
    fun exists(): Boolean = file.exists()

    fun read(): JSONObject? {
        if (!file.exists()) return null
        require(file.length() in 1..65_536) { "Invalid saved state: ${file.name}" }
        return JSONObject(file.readText())
    }

    fun write(value: JSONObject) = transaction(recoverCorrupt = true) { _, save -> save(value) }

    fun update(change: (JSONObject?) -> JSONObject) = transaction { previous, save -> save(change(previous)) }

    fun <T> transaction(recoverCorrupt: Boolean = false, action: (JSONObject?, (JSONObject) -> Unit) -> T): T = synchronized(monitor) {
        val directory = file.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create saved state directory" }
        // The lock inode is never replaced when the JSON record is published.
        RandomAccessFile(File(directory, file.name + ".lock"), "rw").use { lock ->
            lock.channel.lock().use {
                val current = if (recoverCorrupt) runCatching { read() }.getOrNull() else read()
                action(current, ::replace)
            }
        }
    }

    private fun replace(value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..65_536) { "Saved state is too large" }
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object {
        // FileChannel locks also need JVM serialization to avoid OverlappingFileLockException.
        private val monitor = Any()
    }
}
