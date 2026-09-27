package dev.busung.s25uroot

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AzhlBundleTest {
    private val assets = File("src/main/assets")
    private fun catalog() = File(assets, "azhl/catalog.json").readBytes()

    @Test fun eachBundledBackendHasItsOwnVerifiedFilesAndExactFirmware() {
        val targets = AzhlCatalog.parse(catalog())
        assertEquals(KernelSuFlavor.entries.toSet(), targets.map { it.flavor }.toSet())
        targets.forEach { profile ->
            assertEquals(AzhlPort.identity, profile.firmware)
            assertEquals(AzhlCatalog.SOURCE, profile.sourceId)
            assertEquals(1, profile.routePolicy.attempts)
            assertFalse(profile.routePolicy.p0OffsetCache)
            listOf(profile.exploit, profile.kernelSu).forEach { artifact ->
                val bytes = File(assets, artifact.url.removePrefix("asset://")).readBytes()
                assertEquals(artifact.size, bytes.size.toLong())
                assertEquals(artifact.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
            }
        }
    }

    @Test fun malformedOrSubstitutedBundlesAreRefused() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("firmware").put("incremental", "other") },
            { it.put("flavor", "unknown") },
            { it.getJSONObject("kernelsu").put("url", "asset://azhl/../ksud") },
            { it.getJSONObject("exploit").remove("sha256") },
            { it.getJSONObject("routePolicy").put("attempts", 2) },
        )
        mutations.forEachIndexed { index, mutate ->
            val json = JSONObject(catalog().toString(Charsets.UTF_8))
            mutate(json.getJSONArray("payloads").getJSONObject(0))
            assertTrue("Mutation $index was accepted", runCatching {
                AzhlCatalog.parse(json.toString().toByteArray())
            }.isFailure)
        }
    }

    @Test fun successMarkerMustNameTheSelectedBackendAndVersion() {
        KernelSuFlavor.entries.forEach { flavor ->
            val marker = "AZHL_BACKEND_VERIFIED ${flavor.id} ${azhlDriverVersion(flavor)}"
            assertTrue(azhlBackendVerified("other output\n$marker\n", flavor))
            assertFalse(azhlBackendVerified("old log: $marker", flavor))
            assertFalse(azhlBackendVerified("AZHL_BACKEND_VERIFIED ${flavor.id} 1", flavor))
            KernelSuFlavor.entries.filter { it != flavor }.forEach { other ->
                assertFalse(azhlBackendVerified(marker, other))
            }
        }
        assertFalse(azhlBackendVerified("exploit completed done=1 root=1", KernelSuFlavor.KernelSu))
    }

    @Test fun runEnvironmentKeepsTheBackendAndModulePolicyTogether() {
        KernelSuFlavor.entries.forEach { flavor ->
            val enabled = azhlEnvironment(flavor, true).toList()
            val disabled = azhlEnvironment(flavor, false).toList()
            assertEquals(listOf("AZHL_BACKEND=${flavor.id}",
                "AZHL_DRIVER_VERSION=${azhlDriverVersion(flavor)}", "AZHL_DISABLE_MODULES=1"), enabled)
            assertEquals(enabled.take(2), disabled.take(2))
            assertEquals("AZHL_DISABLE_MODULES=0", disabled.last())
        }
    }

    @Test fun driverReportAndMarkerMustBothConfirmTheChosenBackend() {        val flavor = KernelSuFlavor.KernelSuNext
        val report = "KernelSU control verified version=33294 flags=0x5 uapi=4 features=0x3"
        val marker = "AZHL_BACKEND_VERIFIED kernelsu-next 33294"
        assertEquals(33294, verifiedAzhlControl("$report\n$marker", flavor)?.version)
        assertNull(verifiedAzhlControl(report, flavor))
        assertNull(verifiedAzhlControl(marker, flavor))
        assertNull(verifiedAzhlControl("${report.replace("33294", "32636")}\n$marker", flavor))
        listOf("0x1", "0x4").forEach { flags ->
            assertNull(verifiedAzhlControl("${report.replace("0x5", flags)}\n$marker", flavor))
        }
    }

    @Test fun m3qReferenceAssetsMatchTheirDeclaredHashes() {
        val expected = mapOf(
            "m3q/libm3qpayload.so" to (AzhlCatalog.M3Q_PAYLOAD_SIZE to AzhlCatalog.M3Q_PAYLOAD_SHA256),
            "m3q/libm3qoracle.so" to (AzhlCatalog.M3Q_ORACLE_SIZE to AzhlCatalog.M3Q_ORACLE_SHA256),
            "m3q/libm3qroot.so" to (AzhlCatalog.M3Q_ROOT_SIZE to AzhlCatalog.M3Q_ROOT_SHA256),
        )
        expected.forEach { (asset, sizeAndSha) ->
            val bytes = File(assets, asset).readBytes()
            assertEquals(sizeAndSha.first, bytes.size.toLong())
            assertEquals(sizeAndSha.second, MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
        }
    }

    @Test fun everyBackendUsesTheTestedM3qPayload() {
        val targets = AzhlCatalog.parse(catalog())
        targets.forEach { profile ->
            assertEquals(AzhlCatalog.M3Q_PAYLOAD_ASSET, profile.exploit.url)
            assertTrue(AzhlCatalog.isM3qPayload(profile))
        }
    }

    @Test fun m3qControlLineVerifiesEachBackendAgainstItsOwnDriver() {
        KernelSuFlavor.entries.forEach { flavor ->
            val version = azhlDriverVersion(flavor)
            val good = "KernelSU control verified version=$version flags=0x5 uapi=4 features=0x3"
            assertEquals(version, verifiedM3qControl("noise\n$good\n", flavor)?.version)
            assertNull(verifiedM3qControl(good.replace(version.toString(), "1"), flavor))
            assertNull(verifiedM3qControl(good.replace("uapi=4", "uapi=3"), flavor))
            assertNull(
                verifiedM3qControl(
                    "KernelSU control verified version=$version flags=0x1 uapi=4 features=0x3",
                    flavor,
                ),
            )
        }
        assertNull(verifiedM3qControl("AZHL_BACKEND_VERIFIED kernelsu 32636", KernelSuFlavor.KernelSu))
        assertNull(verifiedM3qControl("exploit completed done=1 root=1", KernelSuFlavor.KernelSu))
    }

    @Test fun nativeDaemonCopiesMatchTheVerifiedCatalog() {
        val native = File("src/main/jniLibs/arm64-v8a")
        AzhlCatalog.parse(catalog()).forEach { profile ->
            val backend = M3qLaunch.backend(profile.flavor.id)
            val daemon = File(native, backend.daemonLibrary).readBytes()
            assertEquals(profile.kernelSu.size, daemon.size.toLong())
            assertEquals(profile.kernelSu.sha256, MessageDigest.getInstance("SHA-256").digest(daemon).toHex())
            val helper = File(native, backend.helperLibrary).readBytes()
            assertEquals(backend.helperHash, MessageDigest.getInstance("SHA-256").digest(helper).toHex())
        }
    }
}
