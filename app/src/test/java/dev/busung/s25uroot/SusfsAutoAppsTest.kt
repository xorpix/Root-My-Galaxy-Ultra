package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Test

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

    @Test fun bootRecordCannotCarryProtectionAcrossRebootsOrUidChanges() {
        val text = """{"schema":1,"boot":"boot-a","appId":10123,"version":"v2.3.0","state":"Automatic"}"""
        assertEquals(SusfsAutoApps.State.Automatic, SusfsAutoApps.decodeRecord(text, "boot-a", 10123)!!.state)
        assertNull(SusfsAutoApps.decodeRecord(text, "boot-b", 10123))
        assertNull(SusfsAutoApps.decodeRecord(text, "boot-a", 10124))
        assertNull(SusfsAutoApps.decodeRecord(text, null, 10123))
        assertNull(SusfsAutoApps.decodeRecord(text.replace("Automatic", "unknown"), "boot-a", 10123))
    }
}
