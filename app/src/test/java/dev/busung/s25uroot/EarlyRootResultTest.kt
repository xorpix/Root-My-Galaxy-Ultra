package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EarlyRootResultTest {
    private fun report(state: String, boot: String = "boot-a", uptime: Long = 62_221) = JSONObject()
        .put("boot", boot).put("state", state).put("uptimeMs", uptime)
        .put("finishedLocked", state == "verified")
        .put("launch", JSONObject().put("verificationTiming", EarlyRootTiming.BEFORE))

    @Test fun lateBootCheckPreservesRootProofAndSetupClaimAcrossProcessRestart() {
        val proof = report("verified")
        EarlyRootProgress(proof).claimRestart(true, 64_000)
        val reloaded = JSONObject(proof.toString())
        val merged = EarlyRootResult.merge(reloaded, report("skipped", uptime = 336_180))
        assertEquals("verified", merged.getString("state"))
        assertEquals(62_221, merged.getLong("uptimeMs"))
        assertTrue(merged.getBoolean("finishedLocked"))
        assertEquals(EarlyRootTiming.BEFORE, merged.getJSONObject("launch").getString("verificationTiming"))
        assertFalse(EarlyRootProgress(merged).claimRestart(false, 337_000))
        assertEquals(336_180, merged.getJSONArray("laterObservations").getJSONObject(0).getLong("uptimeMs"))
    }

    @Test fun lateSkipDoesNotHideAFailedOrInterruptedAttempt() {
        for (state in listOf("failed", "running")) {
            val original = report(state).put("detail", "Original attempt")
            val merged = EarlyRootResult.merge(original, report("skipped"))
            assertEquals(state, merged.getString("state"))
            assertEquals("Original attempt", merged.getString("detail"))
        }
    }

    @Test fun newKernelBootAndNormalAttemptProgressReplaceTheCurrentRecord() {
        assertEquals("skipped", EarlyRootResult.merge(report("verified"),
            report("skipped", boot = "boot-b")).getString("state"))
        assertEquals("verified", EarlyRootResult.merge(report("running"), report("verified")).getString("state"))
        assertEquals("failed", EarlyRootResult.merge(report("running"), report("failed")).getString("state"))
    }

    @Test fun repeatedBootNotificationsCannotGrowTheRecordWithoutBound() {
        var saved = report("verified")
        repeat(40) { saved = EarlyRootResult.merge(saved, report("skipped", uptime = it.toLong())) }
        val observations = saved.getJSONArray("laterObservations")
        assertEquals(8, observations.length())
        assertEquals(32, observations.getJSONObject(0).getLong("uptimeMs"))
        assertEquals(39, observations.getJSONObject(7).getLong("uptimeMs"))
        assertEquals(62_221, saved.getLong("uptimeMs"))
    }
}
