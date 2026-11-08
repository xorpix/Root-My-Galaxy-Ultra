package dev.busung.s25uroot

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SusfsRuntimeReadTest {
    private fun live(missing: Set<String> = emptySet()): String {
        fun reply() = JSONObject().put("syscall", 0).put("error", 0).put("terminated", true)
        return JSONObject().put("schema", 1).put("available", true).put("version", "v2.3.0").put("variant", "GKI-LKM")
            .put("features", JSONArray((requiredSusfsFeatures - missing).toList()))
            .put("queries", JSONObject().put("version", reply()).put("variant", reply()).put("features", reply())).toString()
    }

    @Test fun incompleteStatusReadIsRetriedWithoutALoadOrConfigurationCommand() {
        val text = live()
        val calls = mutableListOf<String>()
        val shell: SusfsShell = { command, _ ->
            calls.add(command)
            ShizukuController.ShellResult(0, if (calls.size == 1) text.dropLast(3) else text)
        }
        val outcome = readSusfsStatus("status-helper", shell)
        assertEquals(listOf("status-helper", "status-helper"), calls)
        assertEquals(SusfsState.Active, outcome.status.state)
        assertTrue(outcome.status.abiVerified)
    }

    @Test fun aFailedRetryCannotTurnAnUncertainFirstReplyIntoAbsence() {
        val absent = JSONObject(live()).put("available", false).put("version", "").put("variant", "").put("features", JSONArray())
        for (key in listOf("version", "variant", "features"))
            absent.getJSONObject("queries").put(key, JSONObject().put("syscall", -22).put("error", 126).put("terminated", true))
        var calls = 0
        val shell: SusfsShell = { _, _ ->
            ++calls
            if (calls == 1) ShizukuController.ShellResult(0, "truncated") else ShizukuController.ShellResult(1, absent.toString())
        }
        assertEquals(SusfsState.Unavailable, readSusfsStatus("status-helper", shell).status.state)
        assertEquals(2, calls)
    }

    @Test fun verifiedPartialFeatureSetNeedsNoStatusRetry() {
        var calls = 0
        val shell: SusfsShell = { _, _ -> calls++; ShizukuController.ShellResult(0, live(setOf("CONFIG_KSU_SUSFS_OPEN_REDIRECT"))) }
        val outcome = readSusfsStatus("status-helper", shell)
        assertEquals(1, calls)
        assertEquals(SusfsState.Partial, outcome.status.state)
        assertTrue(susfsCanSelectModules(outcome.status))
    }

    @Test fun latestReportReplacesTheCompletePreviousReportAndLeavesNoTemporaryFile() {
        val directory = Files.createTempDirectory("susfs-report-test-").toFile()
        try {
            val first = SusfsOutcome(SusfsStatus(SusfsState.Unavailable, detail = "first failure"), "old report")
            writeSusfsActivationReport(directory, "App: test-old\n", first)
            val current = SusfsOutcome(SusfsStatus(SusfsState.Partial, detail = "Open redirect missing"), "complete current report")
            writeSusfsActivationReport(directory, "App: test-new\n", current)
            val saved = File(directory, "susfs-last-report.txt").readText()
            assertTrue(saved.startsWith("App: test-new\nState: Partial\n"))
            assertTrue(saved.contains("Open redirect missing"))
            assertTrue(saved.endsWith("complete current report\n"))
            assertFalse(saved.contains("old report"))
            assertEquals(listOf("susfs-last-report.txt"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }
}
