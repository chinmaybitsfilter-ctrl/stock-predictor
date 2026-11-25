package com.chinmay.stockpredictor

import android.content.Context

/** Keeps the last downloaded predictions so the app opens instantly and works offline. */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("predictor_v2", Context.MODE_PRIVATE)

    fun load(): Snapshot? = try {
        prefs.getString("latest", null)?.let { Remote.parse(it, prefs.getString("history", null)) }
    } catch (_: Exception) { null }

    fun save(latest: String, history: String?) =
        prefs.edit().putString("latest", latest).putString("history", history).apply()

    var market: String
        get() = prefs.getString("market", "IN") ?: "IN"
        set(v) = prefs.edit().putString("market", v).apply()
}
