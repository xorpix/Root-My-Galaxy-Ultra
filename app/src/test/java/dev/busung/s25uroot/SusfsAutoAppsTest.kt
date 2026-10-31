package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SusfsAutoAppsTest {
    private fun result(text: String, code: Int = 0) = ShizukuController.ShellResult(code, text)

    @Test fun selectsInstalledManagersIncludingRenamedOnesWithoutAddingOrdinaryApps() {
        val apps = listOf(
            SusfsInstalledApp("me.weishu.kernelsu", 10123),
            SusfsInstalledApp("random.renamed.manager", 10124),
            SusfsInstalledApp("com.topjohnwu.magisk", 10125),
            SusfsInstalledApp("ordinary.app", 10126),
            SusfsInstalledApp("org.bakasu.bakasu", 110123),
            SusfsInstalledApp("system.app", 1000),
            SusfsInstalledApp("../invalid", 10127),
            SusfsInstalledApp("me.weishu.kernelsu", 10123),
        )
        assertEquals(listOf("com.topjohnwu.magisk", "me.weishu.kernelsu", "random.renamed.manager"),
            susfsAutomaticManagers(apps, setOf("random.renamed.manager", "system.app", "../invalid")))
        assertTrue(susfsAutomaticManagers(emptyList(), setOf("me.weishu.kernelsu")).isEmpty())
    }

    @Test fun findsSpoofedManagerWithoutAnyKnownPackageLabelOrLauncher() {
        val directory = Files.createTempDirectory("susfs-spoofed-manager-").toFile()
        try {
            val base = File(directory, "ordinary.camera.apk")
            ZipOutputStream(base.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libksud.so")); zip.write(byteArrayOf(0x7f, 69, 76, 70)); zip.closeEntry()
            }
            assertTrue(susfsHasManagerDaemon(null, listOf(base), listOf("arm64-v8a")))
            assertEquals(listOf("totally.random.identity"), susfsAutomaticManagers(
                listOf(SusfsInstalledApp("totally.random.identity", 10137)), setOf("totally.random.identity")))
            assertFalse(susfsHasManagerDaemon(null, listOf(base), listOf("x86_64")))
        } finally { directory.deleteRecursively() }
    }

    @Test fun findsUnextractedDaemonInAnAbiSplitAndExtractedDaemonWithoutAnApk() {
        val directory = Files.createTempDirectory("susfs-split-manager-").toFile()
        try {
            val base = File(directory, "base.apk")
            val split = File(directory, "split_config.arm64_v8a.apk")
            ZipOutputStream(base.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/ordinary.txt")); zip.write(1); zip.closeEntry()
            }
            ZipOutputStream(split.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libksud.so")); zip.write(1); zip.closeEntry()
            }
            assertFalse(susfsHasManagerDaemon(null, listOf(base), listOf("arm64-v8a")))
            assertTrue(susfsHasManagerDaemon(null, listOf(base, split), listOf("arm64-v8a")))
            File(directory, "libksud.so").writeBytes(byteArrayOf(1))
            assertTrue(susfsHasManagerDaemon(directory, emptyList(), listOf("arm64-v8a")))
        } finally { directory.deleteRecursively() }
    }

    @Test fun unreadableApksAndOrdinaryAppsAreNotClassifiedFromTheirNames() {
        val directory = Files.createTempDirectory("susfs-ordinary-app-").toFile()
        try {
            val apk = File(directory, "root.manager.apk").apply { writeText("not an APK") }
            assertFalse(susfsHasManagerDaemon(null, listOf(apk, File(directory, "missing.apk")), listOf("arm64-v8a")))
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/su")); zip.write(1); zip.closeEntry()
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libksud.so")); zip.closeEntry()
            }
            assertFalse(susfsHasManagerDaemon(null, listOf(apk), listOf("arm64-v8a")))
            assertTrue(susfsAutomaticManagers(listOf(SusfsInstalledApp("root.manager.apk", 10138)), emptySet()).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun removalAndUidChangeUseTheFreshInventory() {
        assertEquals(listOf("me.weishu.kernelsu"),
            susfsAutomaticManagers(listOf(SusfsInstalledApp("me.weishu.kernelsu", 10199)), emptySet()))
        assertTrue(susfsAutomaticManagers(listOf(SusfsInstalledApp("unrelated.reused.uid", 10123)), emptySet()).isEmpty())
    }

    @Test fun recognizesOnlyCompleteAcknowledgedCommands() {
        assertTrue(susfsCommandSucceeded(result("""{"schema":1,"syscall":0,"error":0}""")))
        for (answer in listOf(null, result("""{"schema":1,"syscall":0,"error":126}""", 1),
                result("""{"schema":1,"syscall":0,"error":-2}""", 1), result("su denied", 1),
                result("timeout", 124), result("{}"), result("truncated"))) {
            assertFalse(susfsCommandSucceeded(answer))
        }
        assertTrue(susfsConfirmedFailure(result("""{"schema":1,"syscall":0,"error":-2}""", 1)))
        assertFalse(susfsConfirmedFailure(result("timeout", 124)))
        assertFalse(susfsConfirmedFailure(result("""{"schema":1,"syscall":0,"error":126}""", 1)))
        assertFalse(susfsConfirmedFailure(null))
    }

    @Test fun statusRequiresTheLiveAutomaticPolicyAndThisAppId() {
        val text = """{"schema":1,"status":"ready=1 configured=0 generation=2 automatic=1 own_appid=10123 root_lookup=1\n"}"""
        val parsed = parseSusfsAutomaticStatus(result(text))!!
        assertTrue(parsed.protects(10123)); assertFalse(parsed.protects(10124))
        assertFalse(parseSusfsAutomaticStatus(result(text.replace("root_lookup=1", "root_lookup=0")))!!.protects(10123))
        assertFalse(parseSusfsAutomaticStatus(result(text.replace("automatic=1", "automatic=0")))!!.protects(10123))
        assertNull(parseSusfsAutomaticStatus(result("""{"schema":1,"status":"ready=1 configured=1 generation=1\n"}""")))
        assertNull(parseSusfsAutomaticStatus(null))
    }

    @Test fun retainsUnreadyStateWithExtendedInitializationDiagnostics() {
        val text = """{"schema":1,"status":"ready=0 configured=0 generation=0 automatic=0 own_appid=0 root_lookup=1 init_errno=-22 stage=2 op=0 native_mask=0 compat_mask=0 compat_table=1 native_insn=d503233f compat_insn=d503233f\n"}"""
        val parsed = parseSusfsAutomaticStatus(result(text))!!
        assertTrue(parsed.rootLookup)
        assertFalse(parsed.ready)
        assertFalse(parsed.protects(10303))
    }

    @Test fun bootRecordCannotCarryProtectionAcrossRebootsOrUidChanges() {
        val text = """{"schema":1,"boot":"boot-a","appId":10123,"version":"v2.3.0","state":"Automatic"}"""
        assertEquals(SusfsAutoApps.State.Automatic, SusfsAutoApps.decodeRecord(text, "boot-a", 10123)!!.state)
        assertNull(SusfsAutoApps.decodeRecord(text, "boot-b", 10123))
        assertNull(SusfsAutoApps.decodeRecord(text, "boot-a", 10124))
        assertNull(SusfsAutoApps.decodeRecord(text, null, 10123))
        assertNull(SusfsAutoApps.decodeRecord(text.replace("Automatic", "unknown"), "boot-a", 10123))
    }
}
