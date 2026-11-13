package dev.busung.s25uroot

import org.json.JSONObject

/** The master switch and retry request must stay current while another process waits at boot. */
internal class BootRootOptions(
    private val file: SharedJsonFile,
    private val legacyEnabled: () -> Boolean = { false },
    private val legacyRetry: () -> String? = { null },
) {
    private data class Value(val enabled: Boolean = false, val retry: String? = null) {
        fun json() = JSONObject().put("enabled", enabled).put("retryBoot", retry.orEmpty())
    }

    private fun decode(saved: JSONObject?): Value = when {
        saved != null -> runCatching {
            Value(saved.getBoolean("enabled"), saved.getString("retryBoot").takeIf(String::isNotBlank))
        }.getOrDefault(Value())
        !file.exists() -> Value(legacyEnabled(), legacyRetry())
        else -> Value() // An explicit edit can repair damaged state without restoring an old opt-in.
    }

    fun enabled(): Boolean = runCatching { decode(file.read()).enabled }.getOrDefault(false)
    fun retry(): String? = runCatching { decode(file.read()).retry }.getOrNull()

    fun setEnabled(enabled: Boolean) = file.transaction(recoverCorrupt = true) { saved, save ->
        save(decode(saved).copy(enabled = enabled).json())
    }

    fun setRetry(token: String?) = file.transaction(recoverCorrupt = true) { saved, save ->
        save(decode(saved).copy(retry = token).json())
    }

    fun consumeRetry(boot: String): Boolean = file.transaction { saved, save ->
        val current = decode(saved)
        if (skipTakesBackRetry(current.retry, boot)) {
            save(current.copy(retry = null).json())
            true
        } else false
    }
}
