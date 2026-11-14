package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Test

class BootPayloadTimingTest {
    private fun payload(dirtyFrag: Boolean) = CachedPayload(
        id = "timing-fixture", profileId = "timing-fixture", displayName = "Timing fixture",
        models = listOf("SM-S948B"), kernelVersions = listOf("6.12.69"),
        requiresFreshP0Session = false, routePolicy = DfCatalog.route,
        exploit = RemoteArtifact(if (dirtyFrag) DfCatalog.DF_NATIVE_URL else "https://example.invalid/exploit", 1),
        kernelSu = RemoteArtifact("asset://daemon", 1), helperSha256 = "a".repeat(64), helperSize = 1,
    )

    @Test fun existingCachedDirtyFragPayloadsIgnoreEveryOldAutomaticDelay() {
        for (seconds in BootSettle.allowedSeconds) {
            val effective = bootPayloadSettleSeconds(seconds, false, null, payload(true))
            assertEquals(0, effective)
            assertEquals(0L, BootSettle.remainingMillis(effective, 0))
        }
    }

    @Test fun retryTimingFollowsThePayloadActuallyBeingRetried() {
        assertEquals(0, bootPayloadSettleSeconds(120, true, payload(true), payload(false)))
        assertEquals(120, bootPayloadSettleSeconds(120, false, payload(true), payload(false)))
        assertEquals(120, bootPayloadSettleSeconds(120, true, payload(false), payload(true)))
        assertEquals(0, bootPayloadSettleSeconds(120, true, null, payload(true)))
    }

    @Test fun unknownAndLegacyPayloadsKeepTheirConfiguredSettlingPolicy() {
        assertEquals(60, bootPayloadSettleSeconds(60, false, null, null))
        assertEquals(300, bootPayloadSettleSeconds(300, false, null, payload(false)))
        assertEquals(120, BootSettle.automaticSeconds(119, false))
        assertEquals(0, BootSettle.automaticSeconds(600, true))
    }
}
