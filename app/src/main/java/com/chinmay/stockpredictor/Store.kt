package com.chinmay.stockpredictor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A past prediction and (once that day has traded) how many picks were right. */
data class Past(val forDate: LocalDate, val rise: List<String>, val fall: List<String>, val correct: Int?, val total: Int?)

/** Saves the latest result (shown instantly / offline) and the history of past picks. */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("predictor", Context.MODE_PRIVATE)

    fun loadResult(): Forecast? = try {
        prefs.getString("result", null)?.let { Forecast.fromJson(JSONObject(it)) }
    } catch (_: Exception) { null }

    fun saveResult(r: Forecast) = prefs.edit().putString("result", r.toJson().toString()).apply()

    fun loadHistory(): List<Past> = try {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Past(
                LocalDate.parse(o.getString("for")),
                o.getJSONArray("rise").let { a -> (0 until a.length()).map { a.getString(it) } },
                o.getJSONArray("fall").let { a -> (0 until a.length()).map { a.getString(it) } },
                if (o.has("correct")) o.getInt("correct") else null,
                if (o.has("total")) o.getInt("total") else null,
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun saveHistory(list: List<Past>) {
        val arr = JSONArray()
        list.sortedBy { it.forDate }.takeLast(365).forEach { p ->
            arr.put(JSONObject().apply {
                put("for", p.forDate.toString())
                put("rise", JSONArray(p.rise)); put("fall", JSONArray(p.fall))
                if (p.correct != null) { put("correct", p.correct); put("total", p.total) }
            })
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    /** Record today's picks and score any earlier picks whose day now has real prices. */
    fun update(result: Forecast, prices: Map<String, List<Bar>>) {
        val rise = result.stocks.take(10).map { it.symbol }
        val fall = result.stocks.takeLast(10).reversed().map { it.symbol }
        val list = loadHistory().filter { it.forDate != result.predictionFor }.toMutableList()
        list.add(Past(result.predictionFor, rise, fall, null, null))
        val scored = list.map { p ->
            if (p.correct != null) return@map p
            var ok = 0; var n = 0
            fun check(sym: String, wantUp: Boolean) {
                val bars = prices[sym] ?: return
                val i = bars.indexOfFirst { it.date == p.forDate }
                if (i <= 0) return
                if ((bars[i].close > bars[i - 1].close) == wantUp) ok++
                n++
            }
            p.rise.forEach { check(it, true) }
            p.fall.forEach { check(it, false) }
            if (n >= 10) p.copy(correct = ok, total = n) else p
        }
        saveHistory(scored)
    }
}
