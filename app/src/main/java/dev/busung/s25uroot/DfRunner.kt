package dev.busung.s25uroot

import android.content.Context
import dev.busung.s25uroot.dirtyfrag.DfExploitRunner
import dev.busung.s25uroot.dirtyfrag.DfReporter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * DirtyFrag exploit runner (diabl0w/DFRoot family, OneUI 9 BZIG).
 *
 * Phase 1 only: run the exploit and prove the daemon reported success. This
 * runner never holds root, never touches Shizuku, and never executes anything
 * as root: privilege lands in the escalated shellcode's processes, never in
 * this one. Proof is the `/dev/dfm0` marker the shellcode creates, and
 * stating it is a plain stat any UID can make - which is also why app-seccomp
 * cannot kill it the way it kills a `--ksu-info` query from an app child.
 *
 * Two fail-closed marker directions: a marker already present before the run
 * means something (possibly another app's DirtyFrag run) already rooted this
 * boot, and a missing marker after a zero return means the proof never
 * landed. Both refuse rather than guess. Phase 2 verifies through su when a
 * persisted grant exists; without one the run parks on a question instead of
 * failing, and Continue re-checks in place - one boot, one manual toggle.
 */
/** Whether a DirtyFrag run proved its backend, or is parked waiting for a grant. */
internal sealed interface DfOutcome {
    data class Verified(val report: String) : DfOutcome
    data object GrantMissing : DfOutcome
}

private class GrantMissingException(message: String) : Exception(message)

internal class DfRunner(
    private val context: Context,
    private val log: (String) -> Unit,
    private val stage: (RunStage) -> Unit,
    private val checkStop: () -> Unit,
) {
    companion object {
        /** Patch loop plus sleeps plus the marker poll, with headroom. */
        const val DF_RUN_TIMEOUT_MS = 5 * 60 * 1000L
        private const val SUCCESS_MARKER = "/dev/dfm0"
        private const val SU_TIMEOUT_MS = 30_000L
    }

    suspend fun execute(payloads: VerifiedPayloads): DfOutcome {
        currentCoroutineContext().ensureActive()
        checkStop()
        val flavor = payloads.profile.flavor
        val library = payloads.exploit
        check(library.isFile && library.canRead()) {
            "DirtyFrag native library missing from this build. Reinstall the app."
        }
        check(!File(SUCCESS_MARKER).exists()) {
            "A DirtyFrag success marker from earlier this boot is still present. Fully reboot before another attempt."
        }
        // Never reboot from inside the native flow: this app owns reboot timing
        // and reboots only after the result is persisted. The native flag stays off.
        val softReboot = false
        stage(RunStage.Exploit)
        log("[*] DirtyFrag single exploit launch; backend ${flavor.label}; soft reboot ${if (softReboot) "on" else "off"}")
        val reporter = DfReporter { text -> log(text.trim()) }
        val rc = try {
            withTimeout(DF_RUN_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    DfExploitRunner.runPrestaged(context, reporter, softReboot, payloads.profile.flavor.id)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            error("DirtyFrag timed out after 5 minutes. Fully reboot before another attempt.")
        }
        check(rc == 0 || rc == 2) { dirtyFragFailure(rc) }
        stage(RunStage.Verify)
        if (rc == 0 && File(SUCCESS_MARKER).exists()) {
            log("[+] DirtyFrag daemon reported success (dfm0)")
            return DfOutcome.Verified("DirtyFrag root verified (dfm0)")
        }
        // Grant-free fallback: the KO may have started ksud (Termux su works,
        // managers report Working) without touching dfm0 in time. The native
        // sysfs/modules reading needs no su grant and is seccomp-safe.
        if (runCatching { NativeProbe.isKernelSuActive() }.getOrDefault(false)) {
            log("[+] DirtyFrag daemon active via native probe (no su grant needed)")
            return DfOutcome.Verified("DirtyFrag root verified (native probe: kernelsu active)")
        }
        // Phase 2: the marker shell may die with the flow while the daemon it
        // started keeps serving. A persisted su grant lets this app verify the
        // resident daemon itself, with plain commands no seccomp filter kills.
        log("[*] No marker file; verifying through su (needs a previous grant)")
        return verifyGrant(payloads)
    }

    /** su verification that reports a missing grant instead of failing it. */
    fun verifyGrant(payloads: VerifiedPayloads): DfOutcome {
        return try {
            DfOutcome.Verified(doVerifyGrant(payloads))
        } catch (missing: GrantMissingException) {
            DfOutcome.GrantMissing
        }
    }

    private data class SuResult(val code: Int, val output: String)

    private fun su(command: String): SuResult {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return SuResult(127, "")
        }
        if (!process.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return SuResult(124, "")
        }
        val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        return SuResult(process.exitValue(), output)
    }

    private fun doVerifyGrant(payloads: VerifiedPayloads): String {
        val id = su("id")
        if (id.code != 0 || !id.output.contains("uid=0")) {
            throw GrantMissingException("This app has no su grant yet")
        }
        val staged = payloads.kernelSu.absolutePath
        val version = su("${shellQuote(staged)} --version")
        val reported = version.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (version.code != 0 || reported == null) {
            throw GrantMissingException("Root is up but the backend did not report a version")
        }
        log("[+] Root verified through su; backend reports $reported")
        return "DirtyFrag root verified through su (pre-authorized grant)"
    }

    private fun dirtyFragFailure(rc: Int): String = when (rc) {
        1 -> "DirtyFrag ksud failed to start (dfm1). Fully reboot before another attempt."
        2 -> "DirtyFrag trigger timed out without daemon markers. Fully reboot before another attempt."
        3 -> "DirtyFrag page-cache patching failed. Fully reboot before another attempt."
        else -> "DirtyFrag finished with result $rc. Fully reboot before another attempt."
    }
}
