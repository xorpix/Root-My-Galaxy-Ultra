package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SusfsStatusTest {
    private fun reply(error: Int = 0, syscall: Long = 0, terminated: Boolean = true) =
        JSONObject().put("error", error).put("syscall", syscall).put("terminated", terminated)
    private fun live() = JSONObject().put("schema", 1).put("available", true)
        .put("version", "v2.3.0").put("variant", "GKI")
        .put("features", JSONArray(requiredSusfsFeatures.toList()))
        .put("queries", JSONObject().put("version", reply()).put("variant", reply()).put("features", reply()))

    @Test fun requiresAllNativeRepliesAndStandardFeatures() {
        assertEquals(SusfsState.Active, parseSusfsStatus(live().toString(), 0).state)
        val partial = live().put("features", JSONArray(requiredSusfsFeatures.toList().dropLast(1)))
        assertEquals(SusfsState.Partial, parseSusfsStatus(partial.toString(), 0).state)
    }
    @Test fun missingReplyIsNeverActiveEvenIfAvailableClaimsTrue() {
        val json = live()
        json.getJSONObject("queries").put("features", reply(error = 126))
        assertEquals(SusfsState.Partial, parseSusfsStatus(json.toString(), 0).state)
    }
    @Test fun unterminatedReplyPreventsSuccess() {
        val json = live()
        json.getJSONObject("queries").put("version", reply(terminated = false))
        assertEquals(SusfsState.Partial, parseSusfsStatus(json.toString(), 0).state)
    }
    @Test fun nonzeroExitAndKernelErrorPreventSuccess() {
        assertEquals(SusfsState.Partial, parseSusfsStatus(live().toString(), 1).state)
        val json = live()
        json.getJSONObject("queries").put("features", reply(error = -12))
        assertEquals(SusfsState.Partial, parseSusfsStatus(json.toString(), 1).state)
    }
    @Test fun unhandledRebootAbiMeansAbsent() {
        val json = live().put("available", false).put("version", "").put("variant", "").put("features", JSONArray())
        json.put("queries", JSONObject().put("version", reply(126, -22)).put("variant", reply(126, -22)).put("features", reply(126, -22)))
        assertEquals(SusfsState.Absent, parseSusfsStatus(json.toString(), 1).state)
        json.getJSONObject("queries").put("version", reply(126, -11))
        assertEquals(SusfsState.Unavailable, parseSusfsStatus(json.toString(), 1).state)
    }
    @Test fun malformedMissingOrWrongSchemaIsUnavailable() {
        assertEquals(SusfsState.Unavailable, parseSusfsStatus("Killed", 137).state)
        assertEquals(SusfsState.Unavailable, parseSusfsStatus(live().put("schema", 2).toString(), 0).state)
        val json = live(); json.getJSONObject("queries").remove("variant")
        assertEquals(SusfsState.Unavailable, parseSusfsStatus(json.toString(), 0).state)
    }
    @Test fun warningsDoNotHideTheFinalJsonReport() {
        assertEquals(SusfsState.Active, parseSusfsStatus("su warning\n${live()}\n", 0).state)
    }
    @Test fun emptyOrUnexpectedVersionNeverMeansActive() {
        assertEquals(SusfsState.Partial, parseSusfsStatus(live().put("version", "").toString(), 0).state)
        assertEquals(SusfsState.Partial, parseSusfsStatus(live().put("version", "garbage").toString(), 0).state)
    }
    private fun device() = DeviceSnapshot("samsung", "SM-S948B", "m3q", "bzig-kernel", "version", "aarch64", "BZIG", "fp", "17", 37, "arm64-v8a", 4096)
    @Test fun exactFirmwareAndArchitectureAreRequired() {
        assertNull(susfsCompatibilityIssue(device(), "bzig-kernel"))
        assertNotNull(susfsCompatibilityIssue(device().copy(kernelRelease = "different"), "bzig-kernel"))
        assertNotNull(susfsCompatibilityIssue(device().copy(pageSize = 16384), "bzig-kernel"))
        assertNotNull(susfsCompatibilityIssue(device().copy(abi = "x86_64"), "bzig-kernel"))
        assertNotNull(susfsCompatibilityIssue(device().copy(model = "SM-S948W"), "bzig-kernel"))
        assertNull(susfsCompatibilityIssue(device().copy(model = "SM-S948B/DS"), "bzig-kernel"))
    }
}
