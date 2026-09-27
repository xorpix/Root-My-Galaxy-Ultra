package dev.busung.s25uroot

/** The original APK's AZHL Shizuku contract. No Android dependencies: host-testable. */
internal object M3qLaunch {
    const val DIRECTORY = "/data/local/tmp"
    const val ROOT_TIMEOUT_MS = 600_000L
    const val LOAD_TIMEOUT_MS = 180_000L
    const val PAYLOAD_LIBRARY = "libm3qpayload.so"
    const val DAEMON_PATH = "/data/local/tmp/ksud-m3q-S948NKSS4AZG3-kdp"
    const val DAEMON_STAGE = "/data/local/tmp/.ksud-stage"
    const val LOAD_LOG = "/data/local/tmp/m3q-kernelsu-late-load.log"

    /** Android sh treats a CR after `set -eu` as another option, even with `sh -c`. */
    fun normalizeShellScript(source: String): String = source
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    data class Backend(val id: String, val version: Int, val helperHash: String) {
        val helperLibrary: String get() = "libm3qroot_${id.replace('-', '_')}.so"
        val daemonLibrary: String get() = "libm3qksud_${id.replace('-', '_')}.so"
    }

    fun backend(id: String): Backend = when (id) {
        "kernelsu" -> Backend(id, 32636, "39b018c3648c26fc7e801f6ec7a25018b3ef8544033afadbad4b36dd714d9d59")
        "kernelsu-next" -> Backend(id, 33294, "98a41d063f57e288e0332780b9938f33f4c25082af4b0185a8c17d4fa7021c77")
        "resukisu" -> Backend(id, 35171, "b4700da91d3ad24169cd805786e8fe7f258f36ec53a46c3c092526d5fc1858ab")
        else -> error("Unknown M3Q backend: $id")
    }

    fun environment(appUid: Int): Map<String, String> {
        require(appUid >= 10000) { "M3Q_APP_UID must be the application's UID, not the shell UID." }
        return fixedEnvironment() + ("M3Q_APP_UID" to appUid.toString())
    }

    // Transcribed from M3qRootEngine.configureRootEnvironment(tracefs=true).
    fun fixedEnvironment(): Map<String, String> = linkedMapOf(
        "HOME" to DIRECTORY, "TMPDIR" to DIRECTORY, "PATH" to "/system/bin:/system/xbin",
        "M3Q_STAGE" to "root-single", "M3Q_ENABLE_WRITE" to "1",
        "M3Q_REQUIRE_TRACEFS" to "1", "M3Q_REQUIRE_APP_P0" to "0",
        "M3Q_POPSICLE_WALK" to "1", "M3Q_ATTR_CARRIER" to "1", "M3Q_ATTR_ROOT" to "1",
        "M3Q_ACCEPT_PANIC_RISK" to "1", "TMP_PAGE_UNAME" to "1",
        "TMP_UNAME_PIPEI_SWEEP" to "1", "TMP_UNAME_PIPEI_SLOT_CANDIDATES" to "1",
        "TMP_UNAME_PIPEI_MAX_ATTEMPTS" to "1", "GHOSTLOCK_CORE" to "6",
        "GHOSTLOCK_CONSUMER_CORE" to "7", "PSELECT_RECLAIM_CORE" to "2",
        "PSELECT_RECLAIM_SINGLE_FRAG" to "1", "PSELECT_RECLAIM_SENDS" to "8",
        "PSELECT_RECLAIM_SINGLE_SYSCALL" to "1", "PSELECT_PREPARE_SLABS" to "8",
        "PSELECT_GRAB_HOLD" to "0", "PSELECT_MM_KICK_SLABS" to "0",
        "PSELECT_OWNER_NULL" to "1", "PSELECT_REAL_WAITER_TASK" to "1",
        "PSELECT_W0_PRIO_OVERRIDE" to "100", "PSELECT_ROUTE_TIMERFD" to "0",
        "PSELECT_ROUTE_TIMEOUT_SEC" to "1", "PSELECT_ROUTE_WAIT_SECONDS" to "1",
        "M3Q_SKB_METADATA_RESERVE" to "0", "M3Q_DMAHEAP_SWEEP" to "0",
        "M3Q_DRAIN_CHILDREN" to "0", "PIPEI_DRAIN_TARGET_MB" to "0",
        "PIPEI_PIN_ENABLE" to "0", "PIPEI_PIN_CHILD" to "0", "PIPEI_ORACLE_WALK" to "1",
        "PIPEI_ORACLE_REGION" to "3", "PIPEI_ORACLE_INLINE" to "0",
        "PIPEI_CHILD_REGIONS" to "5", "PIPEI_SWEEP_INLINE" to "0",
        "M3Q_WORKSPACE_ATTEMPTS" to "3", "M3Q_PROBE_STRICT" to "1",
    )

    fun command(helper: String, payload: String): List<String> {
        require(helper.startsWith('/') && payload.startsWith('/'))
        return listOf(helper, "--run-payload", payload, helper)
    }

    fun acceptsControl(id: String, version: Int, flags: Int, uapi: Int): Boolean =
        version == backend(id).version && (flags and 5) == 5 && uapi == 4

    fun bootClaimNames(bootId: String, bootCount: String): List<String> {
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(bootId))
        require(Regex("0|[1-9][0-9]{0,9}").matches(bootCount) && bootCount.toLong() <= Int.MAX_VALUE)
        // BOOT_COUNT survives a diagnostic mutation of boot_id; retain both guards.
        return listOf("$DIRECTORY/.rmgnext-m3q-boot-$bootCount.claim", "$DIRECTORY/azhl-$bootId.claim")
    }
}
