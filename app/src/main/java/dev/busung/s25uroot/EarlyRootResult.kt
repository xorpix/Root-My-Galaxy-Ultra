package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject

/** A late boot notification must not erase the attempt it is reporting on. */
internal object EarlyRootResult {
    fun merge(previous: JSONObject?, next: JSONObject): JSONObject {
        if (previous == null || previous.optString("boot") != next.optString("boot")) return next
        val preserve = previous.optString("state") == "verified" ||
            (next.optString("state") == "skipped" && previous.optString("state") in setOf("running", "failed"))
        if (!preserve) return next
        val result = JSONObject(previous.toString())
        val observations = result.optJSONArray("laterObservations") ?: JSONArray()
        val bounded = JSONArray()
        for (i in maxOf(0, observations.length() - 7) until observations.length()) {
            bounded.put(observations.get(i))
        }
        bounded.put(next)
        return result.put("laterObservations", bounded)
    }
}
