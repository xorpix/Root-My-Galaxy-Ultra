package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EarlyRootProgressTest {
    private fun rootProof() = JSONObject().put("boot", "kernel-boot").put("state", "verified")
        .put("startedLocked", true).put("finishedLocked", true).put("uptimeMs", 62315)

    private fun active() = SusfsStatus(SusfsState.Active, "v2.1.0", "GKI", detail = "Active")

    @Test fun interruptedRestartIsNotRepeatedByANewWorker() {
        val original = rootProof()
        assertTrue(EarlyRootProgress(original).claimRestart(true, 70000))
        // Reload the saved record as a replacement process would after userspace is restarted.
        val resumed = JSONObject(original.toString())
        assertTrue(EarlyRootProgress(resumed).restartAttempted)
        assertFalse(EarlyRootProgress(resumed).claimRestart(true, 90000))
        assertFalse(EarlyRootProgress(resumed).claimRestart(false, 110000))
        assertEquals("attempting", resumed.getString("restartState"))
        assertEquals(70000, resumed.getLong("restartStartedUptimeMs"))
        assertTrue(resumed.getBoolean("restartStartedLocked"))
    }

    @Test fun neitherAcceptedNorRefusedRestartsAreRepeated() {
        for (accepted in listOf(true, false)) {
            val saved = rootProof()
            val progress = EarlyRootProgress(saved)
            assertTrue(progress.claimRestart(true, 70000))
            progress.recordRestart(RecoveryOutcome(accepted, "Daemon reply"), true, 71000)
            assertFalse(EarlyRootProgress(JSONObject(saved.toString())).claimRestart(false, 120000))
            assertEquals(if (accepted) "accepted" else "failed", saved.getString("restartState"))
        }
    }

    @Test fun interruptedSusfsAllowsAStatusReadWithoutAnotherActivationClaim() {
        val saved = rootProof()
        assertTrue(EarlyRootProgress(saved).claimSusfs(true, 64000))
        val resumed = JSONObject(saved.toString())
        val progress = EarlyRootProgress(resumed)
        assertTrue(progress.susfsInProgress)
        assertFalse(progress.claimSusfs(true, 65000))
        progress.recordSusfs(active(), true, 66000)
        assertFalse(progress.susfsInProgress)
        assertFalse(EarlyRootProgress(JSONObject(resumed.toString())).claimSusfs(false, 90000))
    }

    @Test fun failedOrPartialSusfsIsNeverBlindlyLoadedAgain() {
        for (state in listOf(SusfsState.Absent, SusfsState.Partial, SusfsState.Unavailable)) {
            val saved = rootProof()
            val progress = EarlyRootProgress(saved)
            assertTrue(progress.claimSusfs(true, 64000))
            progress.recordSusfs(SusfsStatus(state, detail = "Not verified"), true, 66000)
            val resumed = EarlyRootProgress(JSONObject(saved.toString()))
            assertFalse(resumed.susfsInProgress)
            assertFalse(resumed.claimSusfs(false, 90000))
        }
    }

    @Test fun laterUnlockAndFinalizationPreserveOriginalRootAndSusfsEvidence() {
        val saved = rootProof()
        val progress = EarlyRootProgress(saved)
        progress.claimSusfs(true, 64000)
        progress.recordSusfs(active(), true, 66000)
        progress.claimRestart(true, 67000)
        // The user unlocks while the handoff is being acknowledged.
        progress.recordRestart(RecoveryOutcome(true, "Accepted"), false, 69000)
        saved.put("finalized", true)
        assertEquals("verified", saved.getString("state"))
        assertEquals("kernel-boot", saved.getString("boot"))
        assertTrue(saved.getBoolean("finishedLocked"))
        assertEquals(62315, saved.getLong("uptimeMs"))
        assertTrue(saved.getBoolean("susfsFinishedLocked"))
        assertTrue(saved.getBoolean("restartStartedLocked"))
        assertFalse(saved.getBoolean("restartFinishedLocked"))
        assertEquals("v2.1.0", saved.getString("susfsVersion"))
    }

    @Test fun aNewKernelBootGetsFreshSetupAttempts() {
        val old = rootProof()
        EarlyRootProgress(old).claimRestart(true, 70000)
        val next = rootProof().put("boot", "next-kernel-boot")
        assertTrue(EarlyRootProgress(next).claimSusfs(true, 63000))
        assertTrue(EarlyRootProgress(next).claimRestart(true, 70000))
    }
}
