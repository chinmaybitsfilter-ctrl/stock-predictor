package com.chinmay.stockpredictor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0F1115")
    private val card = Color.parseColor("#171A21")
    private val line = Color.parseColor("#262B35")
    private val fg = Color.parseColor("#E8EAEE")
    private val dim = Color.parseColor("#8B93A3")
    private val up = Color.parseColor("#22C55E")
    private val down = Color.parseColor("#EF4444")

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refreshBtn: Button
    private var running = false
    private var showAll = false
    private var current: Forecast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(tv("📈 Stock Predictor", 24f, fg, bold = true))
        root.addView(tv("NIFTY 50 · Today is " + LocalDate.now(IST).format(dayFmt), 14f, dim))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        status = tv("", 13f, dim).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        refreshBtn = Button(this).apply { text = "Refresh"; setOnClickListener { refresh() } }
        bar.addView(status); bar.addView(refreshBtn)
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = TICKERS.size; visibility = View.GONE
        }
        root.addView(progress)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })

        store.loadResult()?.let { current = it; render(it) }
        refresh()   // always fetch fresh data when the app opens
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ------------------------------------------------------------- work ----

    private fun refresh() {
        if (running) return
        running = true
        refreshBtn.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "Downloading latest prices…"
        worker.execute {
            try {
                val prices = Predictor.download { done, total ->
                    runOnUiThread { progress.progress = done; status.text = "Downloading prices $done/$total…" }
                }
                runOnUiThread { status.text = "Training model…" }
                val result = Predictor.predict(prices)
                store.saveResult(result)
                store.update(result, prices)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    current = result
                    render(result)
                    status.text = "Updated ${result.generatedAt}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    status.text = (e.message ?: "Something went wrong") +
                        if (current != null) "\nShowing last saved prediction." else ""
                    status.setTextColor(down)
                }
            } finally {
                runOnUiThread {
                    running = false
                    if (!isDestroyed) {
                        refreshBtn.isEnabled = true
                        progress.visibility = View.GONE
                    }
                }
            }
        }
        status.setTextColor(dim)
    }

    // --------------------------------------------------------------- UI ----

    private fun render(r: Forecast) {
        content.removeAllViews()

        // Date header
        content.addView(tv("Prediction for", 13f, dim).apply { setPadding(0, dp(14), 0, 0) })
        content.addView(tv(r.predictionFor.format(dayFmt), 26f, fg, bold = true))
        content.addView(tv("Using closing prices of ${r.dataDate.format(dayFmt)}", 13f, dim))

        // Accuracy cards
        val hist = store.loadHistory().filter { it.correct != null }
        val liveOk = hist.sumOf { it.correct ?: 0 }
        val liveTotal = hist.sumOf { it.total ?: 0 }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        stats.addView(statCard("Backtest\n(top picks)", pct(r.accTop)))
        stats.addView(statCard("Backtest\n(all stocks)", pct(r.accAll)))
        stats.addView(statCard("Live record\n(${hist.size} days)", if (liveTotal > 0) pct(liveOk.toDouble() / liveTotal) else "—"))
        content.addView(stats)

        // Top rise / fall
        content.addView(section("▲ Most likely to RISE", up))
        r.stocks.take(10).forEach { content.addView(stockRow(it, rising = true)) }
        content.addView(section("▼ Most likely to FALL", down))
        r.stocks.takeLast(10).reversed().forEach { content.addView(stockRow(it, rising = false)) }

        // All stocks (search)
        content.addView(section("All ${r.stocks.size} stocks", fg))
        val allBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "Search symbol, e.g. TCS"; setTextColor(fg); setHintTextColor(dim)
            isSingleLine = true
        }
        val toggle = Button(this).apply { text = if (showAll) "Hide list" else "Show all stocks" }
        fun fill() {
            allBox.removeAllViews()
            if (!showAll) return
            val q = search.text.toString().trim().uppercase()
            r.stocks.filter { it.symbol.contains(q) }.forEach { allBox.addView(stockRow(it, rising = it.probUp >= 0.5)) }
        }
        toggle.setOnClickListener { showAll = !showAll; toggle.text = if (showAll) "Hide list" else "Show all stocks"; fill() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (!showAll && s?.isNotEmpty() == true) { showAll = true; toggle.text = "Hide list" }; fill() }
        })
        content.addView(search); content.addView(toggle); content.addView(allBox)
        fill()

        // Track record
        content.addView(section("Track record (were past picks right?)", fg))
        if (hist.isEmpty()) {
            content.addView(tv("Nothing scored yet. Open the app again after the next trading day closes " +
                "and it will check today's picks against what really happened.", 13f, dim))
        } else {
            hist.sortedByDescending { it.forDate }.take(30).forEach { p ->
                val acc = (p.correct ?: 0).toDouble() / (p.total ?: 1)
                content.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(tv(p.forDate.format(dayFmt), 14f, fg).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                    addView(tv("${p.correct}/${p.total}   ${pct(acc)}", 14f, if (acc >= 0.5) up else down))
                })
            }
        }

        if (r.failed.isNotEmpty()) {
            content.addView(tv("Couldn't download: " + r.failed.joinToString(", "), 12f, dim).apply { setPadding(0, dp(14), 0, 0) })
        }

        // Disclaimer
        content.addView(tv(
            "⚠️ These are statistical guesses from past price patterns (RSI, MACD, moving averages, momentum, volume), " +
                "not knowledge of the future. Next-day moves are close to random, so even a good model is right only a bit " +
                "more than half the time — check the accuracy numbers above. News and results are ignored. " +
                "Learning project only, not investment advice.\n\nTap a stock to copy its symbol and open INDmoney.",
            12f, Color.parseColor("#F5D08A")
        ).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.parseColor("#2A1F0F"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        })
    }

    private fun stockRow(s: StockPred, rising: Boolean): View {
        val chance = if (rising) s.probUp else 1 - s.probUp
        val color = if (rising) up else down
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            setOnClickListener { openInIndmoney(s.symbol) }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(tv(s.symbol, 16f, fg, bold = true))
            addView(tv(s.reasons.joinToString(" · "), 12f, dim))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(tv((if (rising) "▲ " else "▼ ") + pct(chance), 16f, color, bold = true).apply { gravity = Gravity.END })
            addView(tv("₹%,.2f".format(s.close), 12f, fg).apply { gravity = Gravity.END })
            addView(tv("%+.2f%% today".format(s.changePct), 12f, if (s.changePct >= 0) up else down).apply { gravity = Gravity.END })
        }
        row.addView(left); row.addView(right)
        return row
    }

    private fun openInIndmoney(symbol: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
        val launch = packageManager.getLaunchIntentForPackage("in.indwealth")
        if (launch != null) {
            Toast.makeText(this, "$symbol copied — paste it in INDmoney search", Toast.LENGTH_LONG).show()
            startActivity(launch)
        } else {
            Toast.makeText(this, "$symbol copied. Search it in INDmoney or any broker app.", Toast.LENGTH_LONG).show()
        }
    }

    private fun statCard(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(card)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
        addView(tv(label, 11f, dim))
        addView(tv(value, 20f, fg, bold = true))
    }

    private fun section(title: String, color: Int) = tv(title, 17f, color, bold = true).apply {
        setPadding(0, dp(20), 0, dp(2))
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), line)
    }

    private fun pct(x: Double) = "%.1f%%".format(x * 100)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
