package com.chinmay.stockpredictor

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/** NIFTY 50 stocks (NSE symbols). Symbols that fail to download are skipped. */
val TICKERS = listOf(
    "RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "INFY", "BHARTIARTL", "ITC", "SBIN", "LT",
    "HINDUNILVR", "KOTAKBANK", "AXISBANK", "BAJFINANCE", "MARUTI", "SUNPHARMA", "HCLTECH",
    "M&M", "TITAN", "ULTRACEMCO", "NTPC", "ASIANPAINT", "POWERGRID", "TATASTEEL", "ONGC",
    "ADANIENT", "ADANIPORTS", "BAJAJFINSV", "NESTLEIND", "COALINDIA", "WIPRO", "JSWSTEEL",
    "TECHM", "GRASIM", "HINDALCO", "CIPLA", "DRREDDY", "SBILIFE", "HDFCLIFE", "BRITANNIA",
    "EICHERMOT", "APOLLOHOSP", "TATACONSUM", "BAJAJ-AUTO", "HEROMOTOCO", "SHRIRAMFIN",
    "TRENT", "BEL", "INDUSINDBK", "ETERNAL", "JIOFIN"
)

data class Bar(val date: LocalDate, val close: Double, val volume: Double)

data class StockPred(
    val symbol: String,
    val close: Double,
    val changePct: Double,
    val probUp: Double,
    val reasons: List<String>,
)

data class Forecast(
    val generatedAt: String,
    val dataDate: LocalDate,
    val predictionFor: LocalDate,
    val accAll: Double,
    val accTop: Double,
    val testDays: Int,
    val stocks: List<StockPred>,   // sorted by probUp, highest first
    val failed: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("generatedAt", generatedAt)
        put("dataDate", dataDate.toString())
        put("predictionFor", predictionFor.toString())
        put("accAll", accAll); put("accTop", accTop); put("testDays", testDays)
        put("failed", JSONArray(failed))
        put("stocks", JSONArray().apply {
            stocks.forEach { s ->
                put(JSONObject().apply {
                    put("symbol", s.symbol); put("close", s.close); put("changePct", s.changePct)
                    put("probUp", s.probUp); put("reasons", JSONArray(s.reasons))
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Forecast {
            val arr = o.getJSONArray("stocks")
            val stocks = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val r = s.getJSONArray("reasons")
                StockPred(
                    s.getString("symbol"), s.getDouble("close"), s.getDouble("changePct"),
                    s.getDouble("probUp"), (0 until r.length()).map { r.getString(it) }
                )
            }
            val f = o.optJSONArray("failed") ?: JSONArray()
            return Forecast(
                o.getString("generatedAt"),
                LocalDate.parse(o.getString("dataDate")),
                LocalDate.parse(o.getString("predictionFor")),
                o.getDouble("accAll"), o.getDouble("accTop"), o.getInt("testDays"),
                stocks, (0 until f.length()).map { f.getString(it) }
            )
        }
    }
}

/** Downloads ~2 years of daily prices from Yahoo Finance's public chart endpoint. */
object Yahoo {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun fetch(symbol: String): List<Bar>? {
        val enc = URLEncoder.encode("$symbol.NS", "UTF-8")
        for (host in listOf("query1", "query2")) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://$host.finance.yahoo.com/v8/finance/chart/$enc?range=2y&interval=1d&includeAdjustedClose=true")
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) continue
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val bars = parse(text)
                if (bars != null && bars.size >= 120) return bars
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun parse(text: String): List<Bar>? {
        val results = JSONObject(text).getJSONObject("chart").optJSONArray("result") ?: return null
        val r = results.getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: return null
        val ind = r.getJSONObject("indicators")
        val q = ind.getJSONArray("quote").getJSONObject(0)
        val closes = q.optJSONArray("close")
        val vols = q.optJSONArray("volume")
        val adj = ind.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
        val byDate = LinkedHashMap<LocalDate, Bar>()
        for (i in 0 until ts.length()) {
            val c = when {
                adj != null && i < adj.length() && !adj.isNull(i) -> adj.getDouble(i)
                closes != null && i < closes.length() && !closes.isNull(i) -> closes.getDouble(i)
                else -> continue
            }
            if (c.isNaN() || c <= 0) continue
            val v = if (vols != null && i < vols.length() && !vols.isNull(i)) vols.getDouble(i) else 0.0
            val d = Instant.ofEpochSecond(ts.getLong(i)).atZone(IST).toLocalDate()
            byDate[d] = Bar(d, c, v)
        }
        return byDate.values.sortedBy { it.date }
    }
}
