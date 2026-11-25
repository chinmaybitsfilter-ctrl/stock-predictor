package com.chinmay.stockpredictor

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Predictions are made every evening by GitHub Actions and saved in the repo's data/ folder. */
private const val DATA_URL =
    "https://raw.githubusercontent.com/chinmaybitsfilter-ctrl/stock-predictor/main/data/"

data class Stock(
    val symbol: String,
    val close: Double,
    val changePct: Double,
    val probUp: Double,
    val liquid: Boolean,
    val reasons: String,
)

data class Market(
    val code: String,
    val name: String,
    val currency: String,
    val dataDate: LocalDate,
    val predictionFor: LocalDate,
    val accAll: Double,
    val accTop: Double,
    val testDays: Int,
    val topRise: List<String>,
    val topFall: List<String>,
    val stocks: List<Stock>,            // sorted by chance of rising, highest first
) {
    val bySymbol: Map<String, Stock> by lazy { stocks.associateBy { it.symbol } }
}

data class Past(val market: String, val forDate: LocalDate, val correct: Int?, val total: Int?)

data class Snapshot(val generatedAt: String, val markets: Map<String, Market>, val history: List<Past>)

class NotReadyException : Exception(
    "Predictions are still being prepared on GitHub. Try again in about 15–20 minutes."
)

object Remote {
    fun download(name: String): String {
        val conn = URL(DATA_URL + name + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.useCaches = false
            if (conn.responseCode == 404) throw NotReadyException()
            if (conn.responseCode != 200) throw Exception("Server error ${conn.responseCode}. Try again later.")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            throw NotReadyException()
        } finally {
            conn.disconnect()
        }
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    fun parse(latest: String, history: String?): Snapshot {
        val root = JSONObject(latest)
        val ms = root.getJSONObject("markets")
        val markets = LinkedHashMap<String, Market>()
        for (code in listOf("IN", "US")) {
            val m = ms.optJSONObject(code) ?: continue
            val arr = m.getJSONArray("stocks")
            val stocks = (0 until arr.length()).map { i ->
                val s = arr.getJSONArray(i)
                Stock(s.getString(0), s.getDouble(1), s.getDouble(2), s.getDouble(3), s.getInt(4) == 1, s.optString(5))
            }
            markets[code] = Market(
                code, m.getString("name"), m.getString("currency"),
                LocalDate.parse(m.getString("data_date")), LocalDate.parse(m.getString("prediction_for")),
                m.getDouble("accuracy_all"), m.getDouble("accuracy_top"), m.getInt("test_days"),
                strings(m.optJSONArray("top_rise")), strings(m.optJSONArray("top_fall")), stocks,
            )
        }
        val past = ArrayList<Past>()
        if (history != null) {
            try {
                val h = JSONArray(history)
                for (i in 0 until h.length()) {
                    val o = h.getJSONObject(i)
                    past.add(Past(
                        o.getString("market"), LocalDate.parse(o.getString("for")),
                        if (o.has("correct")) o.getInt("correct") else null,
                        if (o.has("total")) o.getInt("total") else null,
                    ))
                }
            } catch (_: Exception) { }
        }
        return Snapshot(root.optString("generated_at"), markets, past)
    }
}
