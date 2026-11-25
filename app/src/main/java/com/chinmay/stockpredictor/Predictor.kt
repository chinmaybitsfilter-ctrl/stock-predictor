package com.chinmay.stockpredictor

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.sqrt

/** One day of one stock: 12 features + whether the NEXT day closed higher (NaN = unknown yet). */
class Row(val symbol: String, val date: LocalDate, val x: DoubleArray, val y: Double, val close: Double)

const val N_FEAT = 12
// Feature order: 0 ret1, 1 ret5, 2 ret20, 3 rsi14, 4 macdHist, 5 sma20gap, 6 sma50gap,
//                7 bollinger %B, 8 volRatio, 9 volatility20, 10 mktRet1, 11 mktRet5
private const val TOP_N = 10
private const val TEST_DAYS = 60

object Predictor {

    fun nextTradingDay(d: LocalDate): LocalDate {
        // Skips weekends only (NSE holidays are not known to the app)
        var n = d.plusDays(1)
        while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) n = n.plusDays(1)
        return n
    }

    /** Downloads prices for every stock in parallel. */
    fun download(progress: (done: Int, total: Int) -> Unit): Map<String, List<Bar>> {
        val now = ZonedDateTime.now(IST)
        val marketOpen = now.toLocalTime().isBefore(LocalTime.of(15, 35))
        val today = now.toLocalDate()
        val out = java.util.concurrent.ConcurrentHashMap<String, List<Bar>>()
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(6)
        for (sym in TICKERS) {
            pool.execute {
                var bars = Yahoo.fetch(sym)
                // Drop today's still-forming candle while the market is open
                if (bars != null && marketOpen && bars.last().date == today) bars = bars.dropLast(1)
                if (bars != null && bars.size >= 120) out[sym] = bars
                progress(done.incrementAndGet(), TICKERS.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.MINUTES)
        return out
    }

    // ------------------------------------------------------------ features --

    private fun marketReturns(prices: Map<String, List<Bar>>): Map<LocalDate, Double> {
        val sum = HashMap<LocalDate, Double>()
        val cnt = HashMap<LocalDate, Int>()
        for (bars in prices.values) for (i in 1 until bars.size) {
            val r = bars[i].close / bars[i - 1].close - 1
            sum[bars[i].date] = (sum[bars[i].date] ?: 0.0) + r
            cnt[bars[i].date] = (cnt[bars[i].date] ?: 0) + 1
        }
        return sum.mapValues { (d, s) -> s / cnt.getValue(d) }
    }

    private fun ema(src: DoubleArray, span: Int): DoubleArray {
        val a = 2.0 / (span + 1)
        val out = DoubleArray(src.size)
        out[0] = src[0]
        for (i in 1 until src.size) out[i] = out[i - 1] + a * (src[i] - out[i - 1])
        return out
    }

    private fun mean(a: DoubleArray, end: Int, n: Int): Double {
        var s = 0.0
        for (k in end - n + 1..end) s += a[k]
        return s / n
    }

    private fun std(a: DoubleArray, end: Int, n: Int): Double {
        val m = mean(a, end, n)
        var s = 0.0
        for (k in end - n + 1..end) s += (a[k] - m) * (a[k] - m)
        return sqrt(s / (n - 1))
    }

    fun buildRows(symbol: String, bars: List<Bar>, mkt: Map<LocalDate, Double>): List<Row> {
        val n = bars.size
        val c = DoubleArray(n) { bars[it].close }
        val v = DoubleArray(n) { bars[it].volume }
        val ret1 = DoubleArray(n) { if (it == 0) 0.0 else c[it] / c[it - 1] - 1 }

        // RSI (Wilder smoothing)
        val rsi = DoubleArray(n) { 50.0 }
        var avgG = 0.0; var avgL = 0.0
        for (i in 1 until n) {
            val d = c[i] - c[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            if (i == 1) { avgG = g; avgL = l } else { avgG += (g - avgG) / 14; avgL += (l - avgL) / 14 }
            rsi[i] = if (avgL == 0.0) 100.0 else 100 - 100 / (1 + avgG / avgL)
        }
        val e12 = ema(c, 12); val e26 = ema(c, 26)
        val macd = DoubleArray(n) { e12[it] - e26[it] }
        val sig = ema(macd, 9)

        val rows = ArrayList<Row>()
        for (i in 55 until n) {
            val sma20 = mean(c, i, 20); val sma50 = mean(c, i, 50); val sd20 = std(c, i, 20)
            val vMean = mean(v, i, 20)
            val m1 = mkt[bars[i].date] ?: 0.0
            var m5 = 1.0
            for (k in i - 4..i) m5 *= 1 + (mkt[bars[k].date] ?: 0.0)
            val x = doubleArrayOf(
                ret1[i],
                c[i] / c[i - 5] - 1,
                c[i] / c[i - 20] - 1,
                rsi[i],
                (macd[i] - sig[i]) / c[i],
                c[i] / sma20 - 1,
                c[i] / sma50 - 1,
                if (sd20 > 0) (c[i] - (sma20 - 2 * sd20)) / (4 * sd20) else 0.5,
                if (vMean > 0) v[i] / vMean else 1.0,
                std(ret1, i, 20),
                m1,
                m5 - 1,
            )
            if (x.any { it.isNaN() || it.isInfinite() }) continue
            val y = if (i + 1 < n) (if (c[i + 1] > c[i]) 1.0 else 0.0) else Double.NaN
            rows.add(Row(symbol, bars[i].date, x, y, c[i]))
        }
        return rows
    }

    fun reasons(x: DoubleArray): List<String> {
        val r = ArrayList<String>()
        if (x[3] >= 70) r.add("RSI ${x[3].toInt()} overbought")
        else if (x[3] <= 30) r.add("RSI ${x[3].toInt()} oversold")
        r.add(if (x[4] > 0) "MACD bullish" else "MACD bearish")
        r.add(if (x[6] > 0) "above 50-day avg" else "below 50-day avg")
        if (x[1] > 0.04) r.add("+%.1f%% in 5 days".format(x[1] * 100))
        else if (x[1] < -0.04) r.add("%.1f%% in 5 days".format(x[1] * 100))
        if (x[8] > 1.8) r.add("volume spike")
        return r
    }

    // --------------------------------------------------------------- model --

    /** Logistic regression with standardised inputs and L2 regularisation. */
    class Model(rows: List<Row>) {
        private val mu = DoubleArray(N_FEAT)
        private val sd = DoubleArray(N_FEAT) { 1.0 }
        private val w = DoubleArray(N_FEAT)
        private var b = 0.0

        init {
            val m = rows.size.toDouble()
            for (j in 0 until N_FEAT) {
                var s = 0.0; for (r in rows) s += r.x[j]; mu[j] = s / m
                var q = 0.0; for (r in rows) q += (r.x[j] - mu[j]) * (r.x[j] - mu[j])
                sd[j] = sqrt(q / m).takeIf { it > 1e-12 } ?: 1.0
            }
            val z = Array(rows.size) { i -> DoubleArray(N_FEAT) { j -> clip((rows[i].x[j] - mu[j]) / sd[j]) } }
            val lr = 0.3; val lambda = 0.01
            val gw = DoubleArray(N_FEAT)
            repeat(300) {
                java.util.Arrays.fill(gw, 0.0)
                var gb = 0.0
                for (i in rows.indices) {
                    val err = sigmoid(dot(z[i])) - rows[i].y
                    for (j in 0 until N_FEAT) gw[j] += err * z[i][j]
                    gb += err
                }
                for (j in 0 until N_FEAT) w[j] -= lr * (gw[j] / m + lambda * w[j])
                b -= lr * gb / m
            }
        }

        private fun clip(v: Double) = v.coerceIn(-5.0, 5.0)
        private fun dot(z: DoubleArray): Double { var s = b; for (j in 0 until N_FEAT) s += w[j] * z[j]; return s }
        private fun sigmoid(t: Double) = 1.0 / (1.0 + exp(-t))
        fun prob(x: DoubleArray): Double = sigmoid(dot(DoubleArray(N_FEAT) { clip((x[it] - mu[it]) / sd[it]) }))
    }

    // ----------------------------------------------------------------- run --

    fun predict(prices: Map<String, List<Bar>>): Forecast {
        require(prices.size >= 5) { "Could only download ${prices.size} stocks. Check your internet and try again." }
        val mkt = marketReturns(prices)
        val all = prices.flatMap { (s, bars) -> buildRows(s, bars, mkt) }
        val labeled = all.filter { !it.y.isNaN() }
        val dates = labeled.map { it.date }.distinct().sorted()
        require(dates.size > 120) { "Not enough price history to train." }
        val cutoff = dates[dates.size - TEST_DAYS]

        // Honest backtest: train on older days, test on the latest 60 days
        val train = labeled.filter { it.date < cutoff }
        val test = labeled.filter { it.date >= cutoff }
        val bt = Model(train)
        val pTest = test.map { bt.prob(it.x) }
        var correct = 0
        test.forEachIndexed { i, r -> if ((pTest[i] >= 0.5) == (r.y == 1.0)) correct++ }
        var hits = 0; var total = 0
        test.indices.groupBy { test[it].date }.values.forEach { idx ->
            val sorted = idx.sortedByDescending { pTest[it] }
            sorted.take(TOP_N).forEach { if (test[it].y == 1.0) hits++; total++ }
            sorted.takeLast(TOP_N).forEach { if (test[it].y == 0.0) hits++; total++ }
        }

        // Final model on everything, predict each stock's latest day
        val model = Model(labeled)
        val latest = all.maxOf { it.date }
        val preds = all.filter { it.date == latest }.map { r ->
            StockPred(r.symbol, r.close, r.x[0] * 100, model.prob(r.x), reasons(r.x))
        }.sortedByDescending { it.probUp }

        return Forecast(
            generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")),
            dataDate = latest,
            predictionFor = nextTradingDay(latest),
            accAll = correct.toDouble() / test.size,
            accTop = if (total > 0) hits.toDouble() / total else 0.0,
            testDays = dates.size - dates.indexOf(cutoff),
            stocks = preds,
            failed = TICKERS.filter { it !in prices },
        )
    }
}
