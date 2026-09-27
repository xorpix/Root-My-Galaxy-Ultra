package dev.busung.s25uroot

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Original APK launch contract; management requests must retain the app's peer UID. */
internal class M3qRunner(
    private val context: Context,
    private val ceilings: RunCeilings,
    private val log: (String) -> Unit,
    private val stage: (RunStage) -> Unit,
    private val checkStop: () -> Unit,
    private val onClaim: () -> Unit,
    private val onUncertainTermination: () -> Unit,
    private val appProcess: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv).directory(File(M3qLaunch.DIRECTORY)).start()
    },
) {
    private data class Result(val code: Int, val output: String)
    private var startedAt = 0L

    suspend fun execute(payloads: VerifiedPayloads, disableModules: Boolean): String {
        startedAt = SystemClock.elapsedRealtime()
        val flavor = payloads.profile.flavor
        val backend = M3qLaunch.backend(flavor.id)
        val libraryDirectory = File(context.applicationInfo.nativeLibraryDir)
        val helper = checkedLibrary(libraryDirectory, backend.helperLibrary,
            AzhlCatalog.M3Q_ROOT_SIZE, backend.helperHash)
        val payload = checkedLibrary(libraryDirectory, M3qLaunch.PAYLOAD_LIBRARY,
            AzhlCatalog.M3Q_PAYLOAD_SIZE, AzhlCatalog.M3Q_PAYLOAD_SHA256)
        val daemonArtifact = payloads.profile.kernelSu
        val daemon = checkedLibrary(libraryDirectory, backend.daemonLibrary,
            daemonArtifact.size, requireNotNull(daemonArtifact.sha256))
        val uid = shell("id -u")
        check(uid.code == 0 && uid.output.trim() == "2000") {
            "Start Shizuku through wireless debugging (shell UID 2000). Reboot if root is already active."
        }
        check(!RootStatusProbe.isActive()) { "Root is already active. Fully reboot before changing backends." }
        claimBoot()
        log("[*] M3Q single root launch; app UID ${android.os.Process.myUid()}; backend ${flavor.label}")
        log("[*] Original Shizuku contract: root timeout 10 min; activation timeout 3 min; no automatic retry.")
        stage(RunStage.Exploit)
        val root = run(M3qLaunch.ROOT_TIMEOUT_MS, "Temporary root", kernelOperation = true) {
            ShizukuController.exec(
                M3qLaunch.command(helper.path, payload.path).toTypedArray(),
                M3qLaunch.environment(android.os.Process.myUid()).map { (key, value) -> "$key=$value" }.toTypedArray(),
                M3qLaunch.DIRECTORY,
            )
        }
        check(root.code == 0) { "M3Q root launch failed (exit ${root.code}). Fully reboot before another attempt." }

        // SO_PEERCRED in the original root server permits M3Q_APP_UID. A Shizuku
        // process would be UID 2000 and would be rejected here, even after root.
        val rootCheck = app(helper, listOf("-c", "test \"\$(id -u)\" = 0 && echo M3Q_TEMP_ROOT_OK"))
        check(rootCheck.code == 0 && rootCheck.output.lineSequence().any { it.trim() == "M3Q_TEMP_ROOT_OK" }) {
            "The app cannot reach verified temporary root. Fully reboot before another attempt."
        }
        // Query as root before loading: any driver (even one with a different
        // version/UAPI) prevents replacement in a live kernel. Only the native
        // helper's specific no-driver result is accepted as absence.
        //
        // Run --ksu-info DIRECTLY as an app child, never nested through
        // `helper -c`. The nested path (daemon spawns sh, sh runs helper as
        // root) gets SIGKILLed on AZHL — observed on both reference and Next
        // backends: the inner sh reports "Killed", the check fails closed.
        // The direct query is proven safe (same binary, same question, clean
        // answer as shell), and the app-child profile is already proven by
        // the -c outer clients that survive it.
        val existing = app(helper, listOf("--ksu-info"))
        check(existing.code == 13 && existing.output.contains("KernelSU driver fd unavailable")) {
            "An existing or unrecognized root control channel prevents loading. Fully reboot; nothing was replaced."
        }
        stage(RunStage.KernelSu)
        val script = context.assets.open("m3q/prepare-backend.sh").bufferedReader().use { it.readText() }
        val prepareCommand = listOf("/system/bin/sh", "-c", script, "m3q-prepare", flavor.id,
            daemon.path, daemonArtifact.sha256, if (disableModules) "1" else "0")
            .joinToString(" ") { shellQuote(requireNotNull(it)) }
        val prepared = app(helper, listOf("-c", prepareCommand))
        val marker = "M3Q_STAGE_OK:${flavor.id}:${daemonArtifact.sha256}"
        check(prepared.code == 0 && prepared.output.lineSequence().any { it.trim() == marker }) {
            "Backend preparation failed (exit ${prepared.code}). Modules and files were preserved; fully reboot."
        }
        log("[*] Activating ${flavor.label} once through the app's root helper")
        val loaded = app(helper, listOf("--late-load"), M3qLaunch.LOAD_TIMEOUT_MS, kernelOperation = true)
        // The native helper writes useful daemon diagnostics to this separate log.
        val detail = shell("if [ -f ${shellQuote(M3qLaunch.LOAD_LOG)} ]; then tail -c 32768 ${shellQuote(M3qLaunch.LOAD_LOG)}; fi")
        if (detail.code != 0) log("[*] The native activation log could not be read.")
        check(loaded.code == 0) { "${flavor.label} activation failed (exit ${loaded.code}). Fully reboot before another attempt." }
        stage(RunStage.Verify)
        // Successful late-load stops the bootstrap server and unlinks its socket.
        // --ksu-info is a direct control query: do not wrap it in helper -c.
        val verified = app(helper, listOf("--ksu-info"))
        check(verified.code == 0 && verifiedM3qControl(verified.output, flavor) != null) {
            "Fresh verification of ${flavor.label} failed. Fully reboot before another attempt."
        }
        return verified.output
    }

    private fun checkedLibrary(directory: File, name: String, size: Long, sha: String): File {
        val file = File(directory, name)
        check(file.isFile && file.canExecute() && file.length() == size) { "Installed native file missing or wrong size: $name" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == sha) { "Installed native checksum mismatch: $name" }
        return file
    }

    private suspend fun claimBoot() {
        if (M3qBootGuard.isClaimed(context)) {
            onClaim()
            error("This boot already has an M3Q attempt. Fully reboot; do not retry in the same boot.")
        }
        val boot = shell("cat /proc/sys/kernel/random/boot_id")
        val count = shell("settings get global boot_count")
        check(boot.code == 0 && count.code == 0) { "Cannot read the boot identity; refusing." }
        val bootId = boot.output.trim()
        val bootCount = count.output.trim()
        val claims = M3qLaunch.bootClaimNames(bootId, bootCount)
        check(M3qBootGuard.currentCount(context) == bootCount) { "Boot counters disagree or cannot be read; refusing." }
        val prior = shell("if [ -e /data/local/tmp/ghostlock-boot.log ]; then cat /data/local/tmp/ghostlock-boot.log; fi")
        check(prior.code == 0) { "Cannot read the earlier loader receipt; refusing." }
        M3qBootGuard.remember(context, bootCount)
        onClaim()
        check(!prior.output.contains(bootId)) { "An earlier loader attempted this boot. Fully reboot." }
        val command = "set -eu; umask 077; set -C; " + claims.joinToString("; ") {
            "printf '%s\\n' ${shellQuote(bootId)} > ${shellQuote(it)}"
        }
        val claimed = shell(command)
        check(claimed.code == 0) { "The boot is already claimed, or cannot be claimed. Fully reboot." }
    }

    private suspend fun shell(command: String): Result = run(ceilings.helperMillis, "Shizuku command") {
        ShizukuController.exec(arrayOf("/system/bin/sh", "-c", command), null, M3qLaunch.DIRECTORY)
    }

    private suspend fun app(
        helper: File, arguments: List<String>, timeout: Long = ceilings.helperMillis, kernelOperation: Boolean = false,
    ): Result = run(timeout, "Root helper ${arguments.first()}", kernelOperation) {
        appProcess(listOf(helper.path) + arguments)
    }

    private suspend fun run(
        timeout: Long, label: String, kernelOperation: Boolean = false, start: () -> Process,
    ): Result {
        currentCoroutineContext().ensureActive()
        checkStop()
        val now = SystemClock.elapsedRealtime()
        val remaining = ceilings.totalMillis - (now - startedAt)
        check(remaining > 0) { "Run time limit reached. Fully reboot if an attempt started." }
        val deadline = now + minOf(timeout, remaining)
        val process = start()
        val output = StringBuilder()
        val pending = StringBuilder()
        var lastOutput = now
        fun drain(stream: InputStream) {
            val bytes = ByteArray(8192)
            while (stream.available() > 0) {
                val count = stream.read(bytes, 0, minOf(bytes.size, stream.available()))
                if (count <= 0) break
                val text = bytes.copyOf(count).toString(Charsets.UTF_8)
                output.append(text)
                pending.append(text)
                lastOutput = SystemClock.elapsedRealtime()
                // Bound memory if a broken native process writes without newlines.
                if (output.length > 262144) output.delete(0, output.length - 262144)
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    log(pending.substring(0, newline).trimEnd('\r'))
                    pending.delete(0, newline + 1)
                    newline = pending.indexOf("\n")
                }
                if (pending.length > 8192) {
                    log(pending.toString())
                    pending.setLength(0)
                }
                // Return to the deadline/cancellation checks even with continuous output.
                if (SystemClock.elapsedRealtime() >= deadline) break
            }
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                checkStop()
                drain(process.inputStream)
                drain(process.errorStream)
                if (!process.isAlive) {
                    drain(process.inputStream)
                    drain(process.errorStream)
                    break
                }
                val tick = SystemClock.elapsedRealtime()
                check(tick < deadline) { "$label timed out. Fully reboot before another attempt." }
                check(!kernelOperation || tick - lastOutput < ceilings.stallMillis) {
                    "$label exceeded the silence limit. Fully reboot before another attempt."
                }
                delay(200)
            }
            if (pending.isNotEmpty()) log(pending.toString())
            return Result(process.exitValue(), output.toString())
        } finally {
            if (runCatching { process.isAlive }.getOrDefault(true)) {
                // Killing a client does not prove that native children or kernel
                // work stopped. Keep the boot guard and preserve their files.
                onUncertainTermination()
                withContext(NonCancellable) {
                    runCatching { process.destroy() }
                    repeat(10) { if (runCatching { process.isAlive }.getOrDefault(false)) delay(100) }
                    if (runCatching { process.isAlive }.getOrDefault(true)) runCatching { process.destroyForcibly() }
                }
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
