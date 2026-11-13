package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PayloadCacheRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "payloads")
    private val pointer get() = SharedJsonFile(File(temporary.root, "active.json"))
    private fun store(legacy: () -> String? = { null }, protect: (File) -> Unit = {}) = PayloadCache(root, pointer, legacy, protect)
    private fun input(name: String) = File(temporary.root, name).apply { if (!exists()) writeText(name) }
    private fun template(firmware: String = "old") = CachedPayload("", "profile-$firmware", "S26 Ultra",
        listOf("SM-S948B"), listOf("6.12.69-$firmware"), false, DfCatalog.route,
        RemoteArtifact(DfCatalog.DF_NATIVE_URL, input("exploit").length(), sha256 = sha256Of(input("exploit"))),
        RemoteArtifact("asset://daemon", input("daemon").length(), sha256 = sha256Of(input("daemon"))),
        helperSha256 = "a".repeat(64), helperSize = 100,
        firmware = FirmwareRequirement("SM-S948B", "m3q", firmware, "6.12.69-$firmware", 37, "arm64-v8a", 4096))
    private fun publish(cache: PayloadCache, value: CachedPayload) = cache.publish(value, input("exploit"), input("daemon"))

    @Test fun identicalBackendBytesOnNewFirmwareReplaceTheCompleteDescriptor() {
        val ui = store()
        val boot = store()
        val old = publish(ui, template())
        assertEquals(old.profileId, boot.load {}.profile.profileId)
        val current = publish(ui, template("november"))
        assertNotEquals(old.id, current.id)
        assertEquals(current, boot.describe())
        assertEquals(current.profile(), boot.load {}.profile)
        assertEquals(old.kernelSu, current.kernelSu)
        assertEquals(old.exploit, current.exploit)
    }

    @Test fun legacyPointerCannotKeepBootOnTheOldFirmwareAfterManualRefresh() {
        val old = template().copy(id = knownGoodId("1".repeat(64), "2".repeat(64), "3".repeat(64)))
        val directory = File(root, old.id).apply { mkdirs() }
        File(directory, "payload.json").writeText(old.toJson())
        input("exploit").copyTo(File(directory, "cve-2026-43499-app.so"))
        input("daemon").copyTo(File(directory, "ksud-s25u-kdp"))
        val boot = store(legacy = { old.id })
        assertEquals(old, boot.describe())
        val updated = publish(store(legacy = { old.id }), template("november"))
        assertEquals(updated, boot.describe())
        assertEquals(updated, store(legacy = { old.id }).describe())
        boot.clear()
        assertThrows(IllegalStateException::class.java) { store(legacy = { old.id }).describe() }
    }

    @Test fun publishingRepairsCorruptFilesAndCorruptMetadataEvenWithIdenticalInputs() {
        var previous = publish(store(), template())
        for (name in listOf("ksud-s25u-kdp", "payload.json", "cve-2026-43499-app.so")) {
            File(root, "${previous.id}/$name").writeText("damaged")
            assertThrows(Exception::class.java) { store().load {} }
            val current = publish(store(), template())
            assertNotEquals(previous.id, current.id)
            assertEquals(current.profile(), store().load {}.profile)
            previous = current
        }
    }

    @Test fun failedPublicationLeavesThePreviousGenerationReadable() {
        val old = publish(store(), template())
        assertThrows(IllegalStateException::class.java) {
            publish(store(protect = { error("Interrupted copy") }), template("november"))
        }
        assertEquals(old, store().describe())
        assertEquals(old.profile(), store().load {}.profile)
    }

    @Test fun damagedPointerCanBeRepairedByAnExplicitSuccessfulPublication() {
        publish(store(), template())
        File(temporary.root, "active.json").writeText("not json")
        assertThrows(Exception::class.java) { store().load {} }
        val current = publish(store(), template("november"))
        assertEquals(current, store().describe())
    }

    @Test fun reuseRequiresHealthyMetadataAndStorageRemainsBounded() {
        val old = publish(store(), template())
        assertEquals(old.id, publish(store(), template()).id)
        val second = publish(store(), template("november"))
        val third = publish(store(), template("december"))
        assertEquals(setOf(second.id, third.id), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun anUnreadableOrWrongFirmwareCacheIsNeverSilentlyReboundAtBoot() {
        val old = publish(store(), template())
        assertThrows(IllegalArgumentException::class.java) {
            store().load { require(it.firmware == template("november").firmware) }
        }
        assertEquals(old, store().describe())
    }
}
