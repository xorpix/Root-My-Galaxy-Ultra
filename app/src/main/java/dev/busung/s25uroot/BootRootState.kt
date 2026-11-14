package dev.busung.s25uroot

import org.json.JSONObject

/** Boot receipts are read by three processes; SharedPreferences cannot keep them coherent. */
internal class BootRootState(private val file: SharedJsonFile, private val legacy: () -> JSONObject = { JSONObject() }) {
    private fun read() = file.read() ?: legacy()
    fun hasReceipt(): Boolean = read().optBoolean("verified")
    fun verifiedBoot(): String? = read().let {
        if (it.optBoolean("verified")) it.optString("verifiedBoot").takeIf(String::isNotBlank) else null
    }
    fun markVerified(token: String) = file.transaction(recoverCorrupt = true) { saved, save ->
        save((saved ?: legacy()).put("verified", true).put("verifiedBoot", token))
    }
    fun attempted(token: String): Boolean = read().optString("attemptedBoot") == token
    fun claimBroadcast(token: String): Boolean = claim("broadcastBoot", token)
    fun claimAttempt(token: String): Boolean = claim("attemptedBoot", token)

    private fun claim(key: String, token: String): Boolean = file.transaction { saved, save ->
        require(token.isNotBlank())
        val value = saved ?: legacy()
        if (value.optString(key) == token) false else {
            save(value.put(key, token))
            true
        }
    }

    fun reset() = file.transaction { saved, save ->
        val value = saved ?: legacy()
        value.remove("broadcastBoot")
        value.remove("attemptedBoot")
        save(value)
    }
}
