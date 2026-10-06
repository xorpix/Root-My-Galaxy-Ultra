package dev.busung.s25uroot

import java.io.File

/** Text to scan for staging paths, excluding the exact read of another loader's boot receipt. */
internal fun stagingSourceText(source: File): String {
    val text = source.readText()
    if (source.name != "M3qRunner.kt") return text

    // This receipt is only read and must survive cleanup: it prevents a second attempt in one boot.
    // Match the complete read expression so any new write or other reference remains in the scan.
    return text.replace(
        """shell("if [ -e /data/local/tmp/ghostlock-boot.log ]; then cat /data/local/tmp/ghostlock-boot.log; fi")""",
        """shell("")""",
    )
}
