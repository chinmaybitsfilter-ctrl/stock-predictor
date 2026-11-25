package com.chinmay.stockpredictor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import java.time.ZoneId
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
    private val accent = Color.parseColor("#2F6FEB")

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refreshBtn: Button
    private lateinit var tabIn: Button
    private lateinit var tabUs: Button
    private var snapshot: Snapshot? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(tv("📈 Stock Predictor", 24f, fg, bold = true))
        root.addView(tv("Stocks on INDmoney · Today is " +
            LocalDate.now(ZoneId.of("Asia/Kolkata")).format(dayFmt), 14f, dim))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        tabIn = Button(this).apply { text = "🇮🇳 India"; setOnClickListener { selectMarket("IN") } }
        tabUs = Button(this).apply { text = "🇺🇸 US"; setOnClickListener { selectMarket("US") } }
        tabIn.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        tabUs.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        tabs.addView(tabIn); tabs.addView(tabUs)
        root.addView(tabs)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        status = tv("", 13f, dim).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        refreshBtn = Button(this).apply { text = "Refresh"; setOnClickListener { refresh() } }
        bar.addView(status); bar.addView(refreshBtn)
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
        }
        root.addView(progress)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })

        snapshot = store.load()
        render()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun selectMarket(code: String) {
        store.market = code
        render()
    }

    private fun refresh() {
        if (running) return
        running = true
        refreshBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        status.setTextColor(dim)
        status.text = "Getting today's predictions…"
        worker.execute {
            try {
                val latest = Remote.download("latest.json")
                val history = try { Remote.download("history.json") } catch (_: Exception) { null }
                val snap = Remote.parse(latest, history)
                store.save(latest, history)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    snapshot = snap
                    status.text = "Updated ${snap.generatedAt}"
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val msg = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException)
                        "No internet connection." else (e.message ?: "Something went wrong.")
                    status.text = msg + if (snapshot != null) "\nShowing last saved predictions." else ""
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
    }

    // --------------------------------------------------------------- UI ----

    private fun render() {
        val code = store.market
        tabIn.alpha = if (code == "IN") 1f else 0.5f
        tabUs.alpha = if (code == "US") 1f else 0.5f
        content.removeAllViews()
        val snap = snapshot ?: return
        val m = snap.markets[code]
        if (m == null) {
            content.addView(tv("No predictions for this market yet.", 15f, dim).apply { setPadding(0, dp(20), 0, 0) })
            return
        }

        content.addView(tv("Prediction for", 13f, dim).apply { setPadding(0, dp(14), 0, 0) })
        content.addView(tv(m.predictionFor.format(dayFmt), 26f, fg, bold = true))
        content.addView(tv("${m.name} · ${m.stocks.size} stocks · using closing prices of ${m.dataDate.format(dayFmt)}", 13f, dim))

        val hist = snap.history.filter { it.market == code && it.correct != null }
        val ok = hist.sumOf { it.correct ?: 0 }
        val total = hist.sumOf { it.total ?: 0 }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        stats.addView(statCard("Backtest\n(top picks)", pct(m.accTop)))
        stats.addView(statCard("Backtest\n(all stocks)", pct(m.accAll)))
        stats.addView(statCard("Live record\n(${hist.size} days)", if (total > 0) pct(ok.toDouble() / total) else "—"))
        content.addView(stats)

        content.addView(section("▲ Most likely to RISE", up))
        m.topRise.mapNotNull { m.bySymbol[it] }.forEach { content.addView(stockRow(it, m.currency, rising = true)) }
        content.addView(section("▼ Most likely to FALL", down))
        m.topFall.mapNotNull { m.bySymbol[it] }.forEach { content.addView(stockRow(it, m.currency, rising = false)) }
        content.addView(tv("Top picks only include actively traded stocks (no penny stocks).", 12f, dim).apply {
            setPadding(0, dp(6), 0, 0)
        })

        // Search every stock
        content.addView(section("🔍 Find any stock (${m.stocks.size})", fg))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "Type a symbol, e.g. ${if (code == "IN") "IRFC" else "AAPL"}"
            setTextColor(fg); setHintTextColor(dim); isSingleLine = true
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                results.removeAllViews()
                val q = s?.toString()?.trim()?.uppercase() ?: ""
                if (q.isEmpty()) return
                val starts = m.stocks.filter { it.symbol.startsWith(q) }
                val contains = m.stocks.filter { !it.symbol.startsWith(q) && it.symbol.contains(q) }
                val hits = (starts.sortedBy { it.symbol.length } + contains).take(30)
                if (hits.isEmpty()) results.addView(tv("No stock found for \"$q\".", 13f, dim))
                hits.forEach { results.addView(stockRow(it, m.currency, rising = it.probUp >= 0.5)) }
            }
        })
        content.addView(search); content.addView(results)

        // Track record
        content.addView(section("Track record (were past picks right?)", fg))
        if (hist.isEmpty()) {
            content.addView(tv("Nothing scored yet. After each prediction day closes, the picks are checked " +
                "against what really happened and shown here.", 13f, dim))
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

    private fun stockRow(s: Stock, currency: String, rising: Boolean): View {
        val chance = if (rising) s.probUp else 1 - s.probUp
        val color = if (rising) up else down
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            setOnClickListener { openInIndmoney(s.symbol) }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(tv(s.symbol, 16f, fg, bold = true))
            addView(tv(s.reasons, 12f, dim))
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(tv((if (rising) "▲ " else "▼ ") + pct(chance), 16f, color, bold = true).apply { gravity = Gravity.END })
            addView(tv(currency + "%,.2f".format(s.close), 12f, fg).apply { gravity = Gravity.END })
            addView(tv("%+.2f%% today".format(s.changePct), 12f, if (s.changePct >= 0) up else down).apply { gravity = Gravity.END })
        })
        return row
    }

    private fun openInIndmoney(symbol: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
        val launch = packageManager.getLaunchIntentForPackage("in.indwealth")
        if (launch != null) {
            Toast.makeText(this, "$symbol copied. Paste it in INDmoney search.", Toast.LENGTH_LONG).show()
            startActivity(launch)
        } else {
            Toast.makeText(this, "$symbol copied. Search it in INDmoney.", Toast.LENGTH_LONG).show()
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
