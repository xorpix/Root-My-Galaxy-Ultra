package dev.busung.s25uroot

/** Stock identities come from firmware-file metadata, never a boot-time loaded
 * list (which can already contain root modules). No module code is executed. */
internal fun susfsStockModuleInventory(output: String, exitCode: Int): List<String>? = runCatching {
    require(exitCode == 0 && output.length <= 1024 * 1024)
    val lines = output.lineSequence().filter { it.isNotEmpty() }.toList()
    val count = lines.last().removePrefix("complete:").toInt()
    require(lines.last() == "complete:$count" && count in 1..4096 && count == lines.size - 1)
    val names = lines.dropLast(1).map { line ->
        require(line.startsWith("stock:"))
        line.removePrefix("stock:").also {
            require(it.length in 1..55 && it !in setOf(".", "..") &&
                it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.-" })
        }
    }.distinct().sorted()
    require(names.size in 1..1024)
    names
}.getOrNull()

internal object SusfsAutoModules {
    private const val CONTROL = "/sys/module/susfs_guard_lkm/parameters/hide_modules"
    private val status = Regex("automatic discovery: (on|off) stock=(\\d+) tracked=(\\d+)/(\\d+) overflow=(\\d+) errors=(\\d+) seeded=([01])")

    // One bounded scan at activation. Canonical firmware roots only; failed or
    // compressed/missing metadata rejects the inventory instead of trusting a
    // partial list. Newly loaded modules are subsequently handled in-kernel.
    internal fun inventoryScript(workRoot: String) = """
        set -eu
        work=${'$'}(/system/bin/toybox mktemp -d ${shellQuote(workRoot + "/rmgnext-stock.XXXXXXXX")})
        trap 'rm -rf "${'$'}work"' EXIT HUP INT TERM
        : > "${'$'}work/files"
        for root in /lib/modules /system/lib/modules /system_dlkm/lib/modules /vendor/lib/modules /vendor_dlkm/lib/modules /odm/lib/modules /odm_dlkm/lib/modules; do
            [ -d "${'$'}root" ] || continue
            canonical=${'$'}(/system/bin/toybox readlink -f "${'$'}root")
            case "${'$'}canonical" in
                /lib/modules|/system/lib/modules|/system_dlkm/lib/modules|/vendor/lib/modules|/vendor_dlkm/lib/modules|/odm/lib/modules|/odm_dlkm/lib/modules) ;;
                *) exit 65 ;;
            esac
            /system/bin/toybox find "${'$'}canonical" \( -name '*.ko' -o -name '*.ko.gz' -o -name '*.ko.xz' -o -name '*.ko.zst' \) -print >> "${'$'}work/files"
        done
        /system/bin/toybox sort -u "${'$'}work/files" > "${'$'}work/unique"
        count=0
        while IFS= read -r file; do
            count=${'$'}((count + 1))
            [ "${'$'}count" -le 4096 ] || exit 66
            case "${'$'}file" in *.ko) ;; *) exit 67 ;; esac
            file=${'$'}(/system/bin/toybox readlink -f "${'$'}file")
            case "${'$'}file" in
                /lib/modules/*|/system/lib/modules/*|/system_dlkm/lib/modules/*|/vendor/lib/modules/*|/vendor_dlkm/lib/modules/*|/odm/lib/modules/*|/odm_dlkm/lib/modules/*) ;;
                *) exit 65 ;;
            esac
            [ -f "${'$'}file" ] || exit 67
            name=${'$'}(/system/bin/toybox modinfo -F name "${'$'}file")
            case "${'$'}name" in ''|.|..|*[!a-zA-Z0-9_.-]*) exit 68 ;; esac
            [ "${'$'}{#name}" -le 55 ] || exit 69
            printf 'stock:%s\n' "${'$'}name"
        done < "${'$'}work/unique"
        printf 'complete:%s\n' "${'$'}count"
    """.trimIndent()

    internal fun configureScript(names: List<String>): String {
        require(names.isNotEmpty() && names.size <= 1024)
        require(names.all { it.length in 1..55 && it !in setOf(".", "..") &&
            it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.-" } })
        val commands = buildList {
            add("automatic begin")
            names.chunked(16).forEach { add("automatic stock " + it.joinToString(",")) }
            add("automatic commit")
        }
        return buildString {
            appendLine("set -eu")
            commands.forEach { appendLine("printf '%s\\n' ${shellQuote(it)} > ${shellQuote(CONTROL)}") }
            append(seedScript)
        }
    }

    // Commit before the snapshot: notifications cover concurrent loads. A
    // separate acknowledgement prevents an interrupted snapshot from being
    // reported as complete on the next activation. Retrying it is idempotent.
    internal val seedScript = """
        set -eu
        while read -r name rest; do
          case "${'$'}name" in ''|.|..|*[!a-zA-Z0-9_.-]*) exit 70 ;; esac
          printf 'automatic observe %s\n' "${'$'}name" > $CONTROL
        done < /proc/modules
        printf '%s\n' 'automatic ready' > $CONTROL
        cat $CONTROL
    """.trimIndent()

    fun attach(core: SusfsOutcome, shell: SusfsShell, workRoot: String): SusfsOutcome {
        if (!susfsCanSelectModules(core.status)) {
            if (core.status.state !in setOf(SusfsState.Active, SusfsState.Partial)) return core
            return detail(core, false, "Module selection requires a verified ABI plus path and symbol hiding. " + core.status.detail)
        }
        val current = shell("cat ${shellQuote(CONTROL)}", 8)
        if (current == null || current.exitCode != 0) return detail(core, false, "The current kernel-module policy could not be read.")
        val found = status.find(current.output)
        if (found == null) return detail(core, false, "The loaded module needs a full reboot before automatic kernel-module selection is available.")
        if (found.groupValues[1] == "on") {
            if (found.groupValues[7] == "1") return detail(core, healthy(found), current.output)
            val seeded = shell(seedScript, 30)
            val complete = seeded?.takeIf { it.exitCode == 0 }?.let { status.find(it.output) }
            return detail(core, complete != null && healthy(complete), seeded?.output.orEmpty())
        }
        val inventory = shell(inventoryScript(workRoot), 45)
        val names = inventory?.let { susfsStockModuleInventory(it.output, it.exitCode) }
            ?: return detail(core, false, "Firmware metadata inventory was not accepted. Exit: ${inventory?.exitCode?.toString() ?: "no response"}. " +
                inventory?.output?.lineSequence()?.filter { it.isNotBlank() }?.toList()?.takeLast(4)?.joinToString("\n").orEmpty().takeLast(1024))
        val applied = shell(configureScript(names), 30)
        val after = applied?.takeIf { it.exitCode == 0 }?.let { status.find(it.output) }
        return detail(core, after != null && healthy(after), applied?.output.orEmpty())
    }

    private fun healthy(found: MatchResult) = found.groupValues[1] == "on" &&
        found.groupValues[2].toIntOrNull()?.let { it > 0 } == true &&
        found.groupValues[5] == "0" && found.groupValues[6] == "0" && found.groupValues[7] == "1"

    private fun detail(core: SusfsOutcome, ready: Boolean, report: String): SusfsOutcome {
        val message = if (ready) "Extra kernel modules are selected automatically."
            else "Automatic kernel-module selection is incomplete."
        return core.copy(status = core.status.copy(detail = core.status.detail + ". " + message),
            report = core.report + "\nKernel module selection: " + message + "\n" + report)
    }
}
