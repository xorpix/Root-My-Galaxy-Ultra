package dev.busung.s25uroot

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DfBundleTest {
    private val assets = File("src/main/assets")
    private fun catalog() = File(assets, "df/catalog.json").readBytes()

    @Test fun eachDirtyFragBackendHasItsOwnVerifiedDaemonAndExactFirmware() {
        val targets = DfCatalog.parse(catalog())
        assertEquals(KernelSuFlavor.entries.toSet(), targets.map { it.flavor }.toSet())
        targets.forEach { profile ->
            assertEquals(BzigPort.identity, profile.firmware)
            assertEquals("df-bzig-${profile.flavor.id}", profile.profileId)
            assertEquals(DfCatalog.SOURCE, profile.sourceId)
            assertEquals(1, profile.routePolicy.attempts)
            assertFalse(profile.routePolicy.p0OffsetCache)
            assertFalse(profile.routePolicy.prefersShellTransport)
            assertEquals(DfCatalog.DF_NATIVE_URL, profile.exploit.url)
            assertEquals(azhlReleaseVersion(profile.flavor), profile.kernelSuVersion)
            val bytes = File(assets, profile.kernelSu.url.removePrefix("asset://")).readBytes()
            assertEquals(profile.kernelSu.size, bytes.size.toLong())
            assertEquals(profile.kernelSu.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
        }
    }

    @Test fun malformedOrSubstitutedBundlesAreRefused() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("firmware").put("incremental", "other") },
            { it.put("flavor", "unknown") },
            { it.getJSONObject("kernelsu").put("url", "asset://df/../ksud") },
            { it.getJSONObject("kernelsu").put("version", "9.9.9") },
            { it.getJSONObject("routePolicy").put("attempts", 2) },
        )
        mutations.forEachIndexed { index, mutate ->
            val json = JSONObject(catalog().toString(Charsets.UTF_8))
            mutate(json.getJSONArray("payloads").getJSONObject(0))
            assertTrue("Mutation $index was accepted", runCatching {
                DfCatalog.parse(json.toString().toByteArray(Charsets.UTF_8))
            }.isFailure)
        }
    }
}
