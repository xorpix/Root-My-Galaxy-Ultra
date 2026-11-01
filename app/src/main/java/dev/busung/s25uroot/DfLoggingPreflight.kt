package dev.busung.s25uroot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal enum class DfLoggingStatus { Enabled, Restored, Disabled, Unavailable }

/**
 * Developer options' Off choice prefixes persist.log.tag with "Settings".
 * Its leading S silences the init log call that triggers DirtyFrag's handoff.
 * An empty property is the normal, enabled default; a failed read is not.
 */
internal class DfLoggingPreflight(
    private val readTag: () -> String?,
    private val writeTag: ((String) -> Boolean)? = null,
) {
    fun check(): DfLoggingStatus {
        val tag = readTag() ?: return DfLoggingStatus.Unavailable
        if (!isSilent(tag)) return DfLoggingStatus.Enabled
        val write = writeTag ?: return DfLoggingStatus.Disabled
        if (!write(enabledTag(tag))) return DfLoggingStatus.Disabled
        val verified = readTag() ?: return DfLoggingStatus.Unavailable
        return if (isSilent(verified)) DfLoggingStatus.Disabled else DfLoggingStatus.Restored
    }

    private fun isSilent(tag: String): Boolean = tag.firstOrNull()?.uppercaseChar() == 'S'

    private fun enabledTag(tag: String): String {
        // Unstack Settings' sentinel, preserving the previous logging level as Settings does.
        val restored = tag.replace(Regex(",+Settings"), "")
            .replace(Regex("^Settings,*"), "")
            .replace(Regex(",+"), ",")
            .trimEnd(',')
        return if (isSilent(restored)) "" else restored
    }
}

/** No root request or Shizuku permission prompt is needed to check this prerequisite. */
internal object DfLogging {
    private const val LOG_TAG_PROPERTY = "persist.log.tag"
    private const val UNSET_TAG = "__RMGU_LOGGING_DEFAULT__"
    private const val COMMAND_TIMEOUT_MS = 3_000L

    suspend fun check(allowShizuku: Boolean): DfLoggingStatus = runInterruptible {
        DfLoggingPreflight(
            readTag = ::readTag,
            writeTag = if (allowShizuku && ShizukuController.isGranted()) ::writeTag else null,
        ).check()
    }.also {
        // BoundedProcess returns null on interruption; cancellation must still stop the run.
        currentCoroutineContext().ensureActive()
    }

    private fun readTag(): String? {
        val result = command {
            ProcessBuilder("/system/bin/getprop", LOG_TAG_PROPERTY, UNSET_TAG)
                .redirectErrorStream(true).start()
        } ?: return null
        // A missing property prints the explicit default. No output means the read failed.
        if (result.exitCode != 0 || result.output.isEmpty()) return null
        return if (result.output == UNSET_TAG) "" else result.output
    }

    private fun writeTag(value: String): Boolean = command {
        // Arguments are passed directly: a stored property is never evaluated as shell code.
        // Shell Shizuku can restore log_tag_prop even where logd's persistent size is restricted.
        // Restoring the level enables logging; the existing buffer size is sufficient.
        ShizukuController.exec(arrayOf("/system/bin/setprop", LOG_TAG_PROPERTY, value))
    }?.exitCode == 0

    private fun command(start: () -> Process): BoundedProcess.Result? = try {
        val process = start()
        try {
            BoundedProcess.read(process, COMMAND_TIMEOUT_MS)
        } finally {
            process.destroy()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

