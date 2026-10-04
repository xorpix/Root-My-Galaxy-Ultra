package dev.busung.s25uroot

import android.content.ApplicationInfo
import android.content.AssetManager
import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

private open class Reply(private val code: Int = 0, text: String = "") : Process() {
    private val input = ByteArrayInputStream(text.toByteArray())
    override fun getInputStream() = input
    override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun waitFor() = code
    override fun exitValue() = code
    override fun destroy() {}
    override fun isAlive() = false
}

private class Hanging : Reply() {
    var alive = true
    override fun isAlive() = alive
    override fun destroy() { alive = false }
}

private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) = runBlocking {
    val project = File(args.single())
    parseProjectSources(project)
    val fixture = Files.createTempDirectory("m3q-runner-").toFile()
    try {
        val libraries = File(fixture, "lib").apply { mkdir() }
        File(project, "app/src/main/jniLibs/arm64-v8a").listFiles()!!.filter { it.name.startsWith("libm3q") }
            .forEach { it.copyTo(File(libraries, it.name)).setExecutable(true) }
        val bootId = "01234567-89ab-cdef-0123-456789abcdef"
        check(M3qLaunch.bootClaimNames(bootId, "7") != M3qLaunch.bootClaimNames(bootId, "8"))
        for (bad in listOf("", "-1", "07", "2147483648", "7; true")) {
            check(runCatching { M3qLaunch.bootClaimNames(bootId, bad) }.isFailure)
        }
        check(runCatching { M3qLaunch.environment(2000) }.isFailure)
        check(M3qLaunch.environment(10345)["M3Q_APP_UID"] == "10345")
        for (id in listOf("kernelsu", "kernelsu-next", "resukisu")) {
            val version = M3qLaunch.backend(id).version
            check(M3qLaunch.acceptsControl(id, version, 5, 5))
            check(!M3qLaunch.acceptsControl(id, version, 1, 5))
            check(!M3qLaunch.acceptsControl(id, version, 5, 4))
            check(!M3qLaunch.acceptsControl(id, version, 5, 6))
            check(!M3qLaunch.acceptsControl(id, 1, 5, 5))
        }
        var passed = 0
        // Every case uses real runner code, with no Android/kernel/native execution.
        for (scenario in listOf("success", "next", "resuki", "root-failure", "existing-driver", "bad-verification", "wrong-uapi", "timeout", "prior-claim", "no-receipt-access")) {
            val context = Context(File(fixture, scenario).apply { mkdir() }, ApplicationInfo(libraries.path),
                AssetManager(File(project, "app/src/main/assets")))
            val flavor = when (scenario) {
                "next" -> KernelSuFlavor.KernelSuNext
                "resuki" -> KernelSuFlavor.ReSukiSU
                else -> KernelSuFlavor.KernelSu
            }
            val backend = M3qLaunch.backend(flavor.id)
            val daemon = File(libraries, backend.daemonLibrary)
            val sha = digest(daemon)
            val payloads = VerifiedPayloads(TargetProfile(flavor, RemoteArtifact(daemon.length(), sha)))
            val commands = mutableListOf<List<String>>()
            val stages = mutableListOf<RunStage>()
            var rootStarts = 0
            var loaded = false
            var controlQueries = 0
            var claimed = false
            var uncertain = false
            if (scenario == "prior-claim") M3qBootGuard.remember(context, "7")
            ShizukuController.handler = { argv, env, dir ->
                check(dir == M3qLaunch.DIRECTORY)
                if (argv.getOrNull(1) == "--run-payload") {
                    rootStarts++
                    check(argv.toList() == M3qLaunch.command(File(libraries, backend.helperLibrary).path,
                        File(libraries, M3qLaunch.PAYLOAD_LIBRARY).path))
                    check(env!!.toSet() == M3qLaunch.environment(10345).map { (k, v) -> "$k=$v" }.toSet())
                    when (scenario) {
                        "root-failure" -> Reply(255, "root refused\n")
                        "timeout" -> Hanging()
                        else -> Reply(0, "temporary root returned\n")
                    }
                } else {
                    val command = argv[2]
                    when {
                        command == "id -u" -> Reply(0, "2000\n")
                        command == "cat /proc/sys/kernel/random/boot_id" -> Reply(0, "$bootId\n")
                        command == "settings get global boot_count" -> Reply(0, "7\n")
                        command.endsWith(" --ksu-info") -> {
                            controlQueries++
                            if (!loaded) {
                                if (scenario == "existing-driver") Reply(14, "unexpected version\n")
                                else Reply(13, "KernelSU driver fd unavailable\n")
                            } else {
                                val version = if (scenario == "bad-verification") 1 else backend.version
                                val uapi = if (scenario == "wrong-uapi") 4 else 5
                                Reply(0, "KernelSU control verified version=$version flags=0x5 uapi=$uapi features=0x3\n")
                            }
                        }
                        "ghostlock-boot.log" in command -> if (scenario == "no-receipt-access") Reply(1) else Reply()
                        else -> Reply()
                    }
                }
            }
            val runner = M3qRunner(context, RunCeilings(10_000, if (scenario == "timeout") 100 else 20_000, 5000),
                log = {}, stage = { stages.add(it) }, checkStop = {}, onClaim = { claimed = true },
                onUncertainTermination = { uncertain = true }, appProcess = { argv ->
                    commands.add(argv)
                    check(argv.first() == File(libraries, backend.helperLibrary).path)
                    when {
                        argv.getOrNull(1) == "--late-load" -> { loaded = true; Reply() }
                        argv.getOrNull(1) == "--ksu-info" -> error("Control query must use the Shizuku shell")
                        "M3Q_TEMP_ROOT_OK" in argv.last() -> Reply(0, "M3Q_TEMP_ROOT_OK\n")
                        argv.last().endsWith("--ksu-info") -> if (scenario == "existing-driver") Reply(14, "unexpected version\n")
                            else Reply(13, "KernelSU driver fd unavailable\n")
                        else -> Reply(0, "M3Q_STAGE_OK:${flavor.id}:$sha\n")
                    }
                })
            val result = runCatching { runner.execute(payloads, disableModules = false) }
            val success = scenario in listOf("success", "next", "resuki")
            check(result.isSuccess == success) { "$scenario: $result" }
            if (success) {
                check(stages == listOf(RunStage.Exploit, RunStage.KernelSu, RunStage.Verify))
                check(commands.count { it[1] == "--late-load" } == 1)
                check(commands.last()[1] == "--late-load")
                check(controlQueries == 2)
            }
            if (scenario in listOf("root-failure", "timeout", "prior-claim", "no-receipt-access")) check(commands.isEmpty())
            if (scenario == "existing-driver") check(commands.none { it[1] == "--late-load" })
            if (scenario == "prior-claim" || scenario == "no-receipt-access") check(rootStarts == 0) else check(rootStarts == 1)
            if (scenario == "timeout") check(uncertain)
            if (scenario != "no-receipt-access") {
                check(claimed && M3qBootGuard.isClaimed(context))
                // Another Context/process sees the receipt; userspace restart does not clear it.
                val second = Context(context.filesDir, context.applicationInfo, context.assets)
                check(M3qBootGuard.isClaimed(second))
                second.contentResolver.bootCount = "8"
                check(!M3qBootGuard.isClaimed(second))
                second.contentResolver.bootCount = null
                check(M3qBootGuard.isClaimed(second))
            }
            println("PASS runner: $scenario")
            passed++
        }
        println("PASS $passed simulated runner scenarios; UID, boot identity and control rejection checks")
    } finally {
        fixture.deleteRecursively()
    }
}
