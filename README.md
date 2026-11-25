# 📈 Daily NSE Rise / Fall Predictor

A self-updating website that, every trading evening, shows which NIFTY 50 stocks a simple
machine-learning model thinks are most likely to **rise** or **fall** on the next trading day,
with the date clearly shown. Look the stocks up in INDmoney (or any broker app) by their NSE symbol.

## How it works
1. **GitHub Actions** runs `predictor/predict.py` at 5:15 PM IST, Monday–Friday.
2. The script downloads 2 years of daily prices from Yahoo Finance for every stock in
   `predictor/tickers.txt`, computes 12 technical indicators (RSI, MACD, 20/50-day averages,
   Bollinger %B, momentum, volume, market trend) and trains a logistic-regression model.
3. It saves `docs/data/<date>.json` and commits it, so every day's prediction is kept.
4. **GitHub Pages** serves `docs/index.html`, which shows today's top-10 rise / fall picks,
   all stocks, a date picker for past days, and a **track record** that checks each past
   prediction against what actually happened.

## One-time setup
1. **Settings → Pages** → Source: *Deploy from a branch* → Branch `main`, folder `/docs` → Save.
2. **Settings → Actions → General → Workflow permissions** → *Read and write permissions* → Save.
3. **Actions tab → Daily prediction → Run workflow** (to get the first prediction right away).
4. Open `https://<your-username>.github.io/stock-predictor/`.

## Customise
- Add/remove stocks: edit `predictor/tickers.txt` (NSE symbols, without `.NS`).
- Change run time: edit the `cron` line in `.github/workflows/daily.yml` (times are UTC).

## Honest limitations
- Next-day stock moves are close to random. Expect accuracy only around 50–55%; the site shows
  both a backtest and a live track record so you can see how it really does.
- It ignores news, earnings and global events. NSE holidays aren't skipped in the "prediction for" date.
- Educational project only — not investment advice.
