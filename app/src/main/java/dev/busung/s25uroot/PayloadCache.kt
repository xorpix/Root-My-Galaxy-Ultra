package dev.busung.s25uroot

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Immutable generations: firmware metadata and bytes become visible together. */
internal class PayloadCache(
    private val root: File,
    private val state: SharedJsonFile,
    private val legacyActive: () -> String? = { null },
    private val protect: (File) -> Unit = {},
) {
    private fun active(saved: JSONObject?): String? =
        if (saved == null) legacyActive() else saved.optString("active").takeIf(String::isNotBlank)

    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid offline cache entry" }
        return File(root, id)
    }

    private fun descriptor(id: String): CachedPayload {
        val file = File(directory(id), "payload.json")
        require(file.isFile && file.length() in 1..65_536) { "The cached backend record is missing or damaged" }
        return CachedPayload.parse(file.readText()).also {
            require(it.id == id) { "Offline payload cache does not describe itself" }
        }
    }

    fun describe(): CachedPayload = state.transaction { saved, _ ->
        descriptor(checkNotNull(active(saved)) { "No backend is cached. Complete a manual root run first." })
    }

    fun load(check: (CachedPayload) -> Unit): VerifiedPayloads = state.transaction { saved, _ ->
        val cached = descriptor(checkNotNull(active(saved)) { "No backend is cached. Complete a manual root run first." })
        check(cached)
        val exploit = File(directory(cached.id), EXPLOIT)
        val daemon = File(directory(cached.id), KSUD)
        require(fileMatchesArtifact(exploit, cached.exploit) && fileMatchesArtifact(daemon, cached.kernelSu)) {
            "The cached backend files are missing or damaged. Complete a manual root run to refresh them."
        }
        protect(exploit)
        protect(daemon)
        VerifiedPayloads(cached.profile(), exploit, daemon, PayloadOrigin.Cached)
    }

    fun publish(template: CachedPayload, exploit: File, daemon: File): CachedPayload = state.transaction(recoverCorrupt = true) { saved, save ->
        require(fileMatchesArtifact(exploit, template.exploit) && fileMatchesArtifact(daemon, template.kernelSu)) {
            "The backend files failed verification; the previous cache was retained"
        }
        check(root.isDirectory || root.mkdirs()) { "Cannot create the offline payload cache" }
        val previousId = active(saved)
        val previous = previousId?.let { runCatching { descriptor(it) }.getOrNull() }
        // Reuse only a complete, healthy entry with identical metadata, including firmware.
        if (previous != null && previous.copy(id = "") == template.copy(id = "") &&
            fileMatchesArtifact(File(directory(previous.id), EXPLOIT), previous.exploit) &&
            fileMatchesArtifact(File(directory(previous.id), KSUD), previous.kernelSu)) {
            save(JSONObject().put("active", previous.id))
            return@transaction previous
        }
        val cached = template.copy(id = "v2-" + UUID.randomUUID())
        val destination = directory(cached.id)
        val temporary = File(root, ".${cached.id}.tmp")
        check(temporary.mkdir()) { "Cannot create the offline payload cache entry" }
        try {
            copy(exploit, File(temporary, EXPLOIT), cached.exploit)
            copy(daemon, File(temporary, KSUD), cached.kernelSu)
            FileOutputStream(File(temporary, "payload.json")).use {
                it.write(cached.toJson().toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            check(temporary.renameTo(destination)) { "Cannot publish the offline payload cache" }
            check(descriptor(cached.id) == cached) { "The saved cache record could not be verified" }
            save(JSONObject().put("active", cached.id).put("previous", previousId ?: JSONObject.NULL))
        } finally { temporary.deleteRecursively() }
        // Keep one previous generation for readers already holding its files. Clearing remains explicit.
        root.listFiles()?.filter { it.name != cached.id && it.name != previousId }?.forEach {
            runCatching { it.deleteRecursively() }
        }
        cached
    }

    fun clear() = state.transaction(recoverCorrupt = true) { _, save ->
        // A tombstone prevents a legacy SharedPreferences pointer from reviving a cleared cache.
        save(JSONObject())
        root.deleteRecursively()
        Unit
    }

    private fun copy(source: File, destination: File, artifact: RemoteArtifact) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
        }
        protect(destination)
        require(fileMatchesArtifact(destination, artifact)) { "The cached backend failed verification after copying" }
    }

    companion object {
        private const val EXPLOIT = "cve-2026-43499-app.so"
        private const val KSUD = "ksud-s25u-kdp"
    }
}
