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
        assertEquals(DfPort.firmwares.size * KernelSuFlavor.entries.size, targets.size)
        assertEquals(DfPort.firmwares.values.toSet(), targets.map { it.firmware }.toSet())
        DfPort.firmwares.forEach { (id, firmware) ->
            val profiles = targets.filter { it.firmware == firmware }
            assertEquals(KernelSuFlavor.entries.toSet(), profiles.map { it.flavor }.toSet())
            KernelSuFlavor.entries.forEach { flavor ->
                val selected = targets.resolveFor(snapshot(firmware), flavor)
                assertNotNull("No $id / ${flavor.id} profile", selected)
                assertEquals("df-$id-${flavor.id}", selected?.profileId)
                assertTrue(DfPort.matches(snapshot(firmware)))
                assertNull(DfPort.identityRefusal(snapshot(firmware)))
                assertNull(backendRunRefusal(requireNotNull(selected), snapshot(firmware), flavor, null, false))
            }
        }
        targets.forEach { profile ->
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

    @Test fun canadianProfileMatchesTheSuppliedInventoryIncludingItsUSeriesKernel() {
        val observations = JSONObject(File("../docs/device-candidates/SM-S948W-BZID.json").readText())
            .getJSONObject("observations")
        val firmware = DfPort.firmwares.getValue("bzid")
        assertEquals(observations.getJSONObject("ro.product.model").getString("value"), firmware.model)
        assertEquals(observations.getJSONObject("ro.product.device").getString("value"), firmware.device)
        assertEquals(observations.getJSONObject("ro.build.version.incremental").getString("value"), firmware.incremental)
        assertEquals(observations.getJSONObject("kernel_release").getString("value"), firmware.kernelRelease)
        assertTrue(firmware.kernelRelease.contains("S948USQU4BZID"))
    }

    @Test fun recordedProfilesAndGenericRowsRemainBoundToTheirExactFirmware() {
        val targets = DfCatalog.parse(catalog())
        DfPort.firmwares.values.forEach { firmware ->
            val device = snapshot(firmware)
            val wrong = listOf(
                device.copy(model = "SM-S948U"),
                device.copy(device = "other"),
                device.copy(incremental = "new"),
                device.copy(incremental = ""),
                device.copy(kernelRelease = device.kernelRelease + "-other"),
                device.copy(kernelRelease = DfPort.firmwares.values.first { it != firmware }.kernelRelease),
                device.copy(sdk = 36), device.copy(pageSize = 16384),
                device.copy(abi = "armeabi-v7a"), device.copy(machine = "x86_64"),
                device.copy(manufacturer = "other"),
            )
            wrong.forEach { changed ->
                KernelSuFlavor.entries.forEach { assertNull(targets.resolveFor(changed, it)) }
            }
            val profile = requireNotNull(targets.resolveFor(device))
            assertFalse(profile.copy(firmware = null).matches(device))
            assertFalse(profile.copy(firmware = null, kernelVersions = setOf(device.kernelVersion)).matches(device))
            targets.filter { it.firmware != firmware }.forEach {
                assertFalse(it.matches(device))
                assertNotNull(backendRunRefusal(it, device, it.flavor, null, false))
            }
        }
        assertNull(targets.resolveFor(snapshot(AzhlPort.identity)))
    }

    @Test fun incompleteDuplicateOrFirmwareSwappedCatalogsAreRefused() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("payloads").remove(0) },
            { json ->
                val rows = json.getJSONArray("payloads")
                rows.put(1, JSONObject(rows.getJSONObject(0).toString()))
            },
            { json ->
                val row = json.getJSONArray("payloads").getJSONObject(0)
                val firmware = DfPort.firmwares.getValue("bzid")
                row.put("firmware", firmware.toJsonObject())
                row.put("models", org.json.JSONArray(listOf(firmware.model)))
                row.put("kernelVersions", org.json.JSONArray(listOf(firmware.kernelRelease)))
            },
        )
        mutations.forEachIndexed { index, mutate ->
            val json = JSONObject(catalog().toString(Charsets.UTF_8))
            mutate(json)
            assertTrue("Catalog mutation $index was accepted", runCatching {
                DfCatalog.parse(json.toString().toByteArray(Charsets.UTF_8))
            }.isFailure)
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
        for (profileIndex in 0 until JSONObject(catalog().toString(Charsets.UTF_8)).getJSONArray("payloads").length()) {
            mutations.forEachIndexed { index, mutate ->
                val json = JSONObject(catalog().toString(Charsets.UTF_8))
                mutate(json.getJSONArray("payloads").getJSONObject(profileIndex))
                assertTrue("Profile $profileIndex mutation $index was accepted", runCatching {
                    DfCatalog.parse(json.toString().toByteArray(Charsets.UTF_8))
                }.isFailure)
            }
        }
    }

    @Test fun dirtyFragLkmCarriesTheCfiExitEntryAndBridgeMarkers() {
        val folder = File("src/main/cpp/dirtyfrag/ko")
        val provenance = JSONObject(File(folder, "provenance.json").readText())
        val images = provenance.getJSONObject("images")
        listOf(
            "dirtyfrag-android16-6.12.ko",
            "dirtyfrag-android16-6.12-kernelsu.ko",
            "dirtyfrag-android16-6.12-kernelsu-next.ko",
            "dirtyfrag-android16-6.12-resukisu.ko",
            "dirtyfrag-android16-6.12-kowsu.ko",
        ).forEach { name ->
            val bytes = File(folder, name).readBytes()
            val recorded = images.getJSONObject(name)
            assertEquals("$name size", recorded.getLong("size"), bytes.size.toLong())
            assertEquals("$name hash", recorded.getString("sha256"),
                MessageDigest.getInstance("SHA-256").digest(bytes).toHex())

            // The e47ea6e module had no module_exit; kernels whose cfi_init walks
            // both jump tables rebooted in strcmp before the module could run.
            assertTrue("$name lost .exit.text", sectionNames(bytes).contains(".exit.text"))
            assertTrue("$name lost cleanup_module",
                bytes.toString(Charsets.ISO_8859_1).contains("cleanup_module"))

            val text = bytes.toString(Charsets.ISO_8859_1)
            val markers = mutableListOf("/dev/dfm0", "/dev/dfm1", "/dev/dfro", "/dev/dfwipe",
                "/dev/dfwipeskip", "/dev/dfmoddis", "df-wipe-requested",
                "df-disable-modules", "df-noro", "late-load")
            // KowSU's bridge deliberately blanks the manager override so its
            // daemon uses the compiled com.kowx712.supermanager default.
            if (!name.endsWith("-kowsu.ko")) markers += "late-load --package-name"
            markers.forEach { marker -> assertTrue("$name lost $marker", text.contains(marker)) }
        }
    }

    private fun sectionNames(bytes: ByteArray): Set<String> {
        require(bytes.size > 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte())
        fun u16(offset: Int) = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
        fun u32(offset: Int) = u16(offset) or (u16(offset + 2) shl 16)
        fun u64(offset: Int) = u32(offset).toLong() or (u32(offset + 4).toLong() shl 32)
        val shoff = u64(40)
        val shentsize = u16(58)
        val shnum = u16(60)
        val shstrndx = u16(62)
        val strOffset = u64((shoff + shentsize.toLong() * shstrndx).toInt() + 24).toInt()
        val strSize = u64((shoff + shentsize.toLong() * shstrndx).toInt() + 32).toInt()
        val strings = bytes.copyOfRange(strOffset, strOffset + strSize)
        return (0 until shnum).map { index ->
            val nameOffset = u32((shoff + shentsize.toLong() * index).toInt())
            var end = nameOffset
            while (end < strings.size && strings[end] != 0.toByte()) end++
            strings.copyOfRange(nameOffset, end).toString(Charsets.ISO_8859_1)
        }.toSet()
    }

    private fun snapshot(firmware: FirmwareRequirement) = DeviceSnapshot(
        manufacturer = "samsung", model = firmware.model, device = firmware.device,
        kernelRelease = firmware.kernelRelease, kernelVersionInfo = "#1 SMP PREEMPT",
        machine = "aarch64", buildId = firmware.incremental, fingerprint = "samsung/m3q",
        androidRelease = if (firmware.sdk == 37) "17" else "16",
        sdk = firmware.sdk, abi = firmware.abi, pageSize = firmware.pageSize,
        incremental = firmware.incremental,
    )
}
