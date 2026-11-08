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
    private val status = Regex("automatic discovery: (on|off) stock=(\\d+) tracked=(\\d+)/(\\d+) overflow=(\\d+) errors=(\\d+)")

    // One bounded scan at activation. Canonical firmware roots only; failed or
    // compressed/missing metadata rejects the inventory instead of trusting a
    // partial list. Newly loaded modules are subsequently handled in-kernel.
    internal val inventoryScript = """
        set -eu
        work=${'$'}(/system/bin/toybox mktemp -d /data/local/tmp/rmgu-stock.XXXXXXXX)
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
            // Commit before reading the snapshot. Notifications capture loads
            // concurrent with the snapshot, closing the bootstrap load race.
            appendLine("while read -r name rest; do")
            appendLine("  case \"${'$'}name\" in ''|.|..|*[!a-zA-Z0-9_.-]*) exit 70 ;; esac")
            appendLine("  printf 'automatic observe %s\\n' \"${'$'}name\" > ${shellQuote(CONTROL)}")
            appendLine("done < /proc/modules")
            appendLine("cat ${shellQuote(CONTROL)}")
        }
    }

    fun attach(core: SusfsOutcome, shell: SusfsShell): SusfsOutcome {
        if (core.status.state != SusfsState.Active) return core
        val current = shell("cat ${shellQuote(CONTROL)}", 8)
        val found = current?.takeIf { it.exitCode == 0 }?.let { status.find(it.output) }
        if (found == null) return detail(core, false, "The loaded module needs a full reboot before automatic kernel-module selection is available.")
        if (found.groupValues[1] == "on") return detail(core, healthy(found), current?.output.orEmpty())
        val inventory = shell(inventoryScript, 45)
        val names = inventory?.let { susfsStockModuleInventory(it.output, it.exitCode) }
            ?: return detail(core, false, "Firmware module metadata could not be read completely; configured rules remain active.")
        val applied = shell(configureScript(names), 30)
        val after = applied?.takeIf { it.exitCode == 0 }?.let { status.find(it.output) }
        return detail(core, after != null && healthy(after), applied?.output.orEmpty())
    }

    private fun healthy(found: MatchResult) = found.groupValues[1] == "on" &&
        found.groupValues[2].toIntOrNull()?.let { it > 0 } == true &&
        found.groupValues[5] == "0" && found.groupValues[6] == "0"

    private fun detail(core: SusfsOutcome, ready: Boolean, report: String): SusfsOutcome {
        val message = if (ready) "Extra kernel modules are selected automatically."
            else "Automatic kernel-module selection is incomplete. Configured rules remain active."
        return core.copy(status = core.status.copy(detail = core.status.detail + ". " + message),
            report = core.report + "\nKernel module selection: " + message + "\n" + report)
    }
}
