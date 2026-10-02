package dev.busung.s25uroot

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Host-only regression: runs preparation against temporary files, never Android or native code.
 * Compile together with production M3qLaunch.kt and pass the repository path to main.
 */
fun main(args: Array<String>) {
    val project = File(args.single())
    val source = File(project, "app/src/main/assets/m3q/prepare-backend.sh")
        .readText(Charsets.UTF_8).replace("\r\n", "\n")
    val fixture = Files.createTempDirectory("rmg-script-text-").toFile()
    try {
        for (kind in listOf("LF", "CRLF", "CR", "BOM+CRLF")) {
            val root = File(fixture, kind).apply { mkdir() }
            val adb = File(root, "adb").apply { mkdir() }
            val tmp = File(root, "tmp").apply { mkdir() }
            val module = File(adb, "modules/keep").apply { mkdirs() }
            File(module, "data").writeText("preserve this")
            val daemon = File(root, "dummy daemon").apply { writeText("not executable\n") }
            val hash = MessageDigest.getInstance("SHA-256").digest(daemon.readBytes())
                .joinToString("") { "%02x".format(it) }
            val identityCheck = "[ \"\$(id -u)\" = 0 ] || exit 65"
            check(source.contains(identityCheck))
            // Redirect every mutable path; bypass identity only in this temporary test copy.
            val isolated = source.replace("/data/adb", adb.path)
                .replace("/data/local/tmp", tmp.path).replace(identityCheck, ":")
            val input = when (kind) {
                "CRLF" -> isolated.replace("\n", "\r\n")
                "CR" -> isolated.replace('\n', '\r')
                "BOM+CRLF" -> "\uFEFF" + isolated.replace("\n", "\r\n")
                else -> isolated
            }
            fun run(text: String): Pair<Int, String> {
                val child = ProcessBuilder("sh", "-c", text, "m3q-prepare", "resukisu",
                    daemon.path, hash, "0").redirectErrorStream(true).start()
                try {
                    check(child.waitFor(5, TimeUnit.SECONDS)) { "Preparation timed out" }
                    return child.exitValue() to child.inputStream.bufferedReader().readText()
                } finally {
                    if (child.isAlive) child.destroyForcibly()
                }
            }
            if (kind == "CRLF") {
                val (code, text) = run(input)
                check(code != 0 && "M3Q_STAGE_OK" !in text) { "Unnormalized CRLF unexpectedly ran: $text" }
                check(!File(module, "disable").exists())
                println("PASS reproduced raw CRLF failure before preparation")
            }
            val (code, text) = run(M3qLaunch.normalizeShellScript(input))
            check(code == 0 && "M3Q_STAGE_OK:resukisu:$hash" in text) { "$kind: $code $text" }
            check(File(module, "disable").exists())
            check(File(module, "data").readText() == "preserve this")
            check(File(adb, "azhl-last-prepared-backend").readText().trim() == "resukisu")
            for (name in listOf(".ksud-stage", "ksud-m3q-S948NKSS4AZG3-kdp")) {
                check(File(tmp, name).readBytes().contentEquals(daemon.readBytes()))
            }
            println("PASS $kind: preparation completed, staged files verified, module data preserved")
        }
    } finally {
        fixture.deleteRecursively()
    }
}
