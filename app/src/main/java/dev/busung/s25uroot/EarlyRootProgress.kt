package dev.busung.s25uroot

import org.json.JSONObject

/** Persist each attempt before its side effect: a userspace restart may kill the worker immediately. */
internal class EarlyRootProgress(private val result: JSONObject) {
    val susfsAttempted: Boolean get() = result.has("susfsState")
    val susfsInProgress: Boolean get() = result.optString("susfsState") == "running"
    val restartAttempted: Boolean get() = result.has("restartState")

    fun claimSusfs(locked: Boolean, uptimeMs: Long): Boolean {
        if (susfsAttempted) return false
        result.put("susfsState", "running").put("susfsStartedLocked", locked)
            .put("susfsStartedUptimeMs", uptimeMs)
        return true
    }

    fun recordSusfs(status: SusfsStatus, locked: Boolean, uptimeMs: Long) {
        check(susfsAttempted) { "SusFS activation was not claimed" }
        result.put("susfsState", status.state.name.lowercase()).put("susfsVersion", status.version)
            .put("susfsDetail", status.detail.take(1600)).put("susfsFinishedLocked", locked)
            .put("susfsUptimeMs", uptimeMs)
    }

    fun claimRestart(locked: Boolean, uptimeMs: Long): Boolean {
        if (restartAttempted) return false
        result.put("restartState", "attempting").put("restartStartedLocked", locked)
            .put("restartStartedUptimeMs", uptimeMs)
        return true
    }

    fun recordRestart(outcome: RecoveryOutcome, locked: Boolean, uptimeMs: Long) {
        check(restartAttempted) { "Module restart was not claimed" }
        result.put("restartState", if (outcome.accepted) "accepted" else "failed")
            .put("restartDetail", outcome.detail.take(1600)).put("restartFinishedLocked", locked)
            .put("restartUptimeMs", uptimeMs)
    }
}
