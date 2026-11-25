# 📈 Stock Predictor (Android)

An Android app that shows, with the date, which stocks on INDmoney are most likely to **rise** or
**fall** on the next trading day. It covers **every NSE-listed stock** (India) and the **S&P 500** (US).

## How it works
- **GitHub does the work.** `.github/workflows/predict.yml` runs every weekday after the Indian
  market closes (5:15 PM IST) and again after the US market closes. `predictor/predict.py` downloads 2 years of
  prices for every stock, computes 12 technical indicators (RSI, MACD, moving averages, Bollinger %B,
  momentum, volume, market trend) and trains a logistic-regression model per market. Results are
  saved to `data/latest.json`, and past picks are scored against what really happened in `data/history.json`.
- **The app just reads the results** from the repo, so it opens in seconds and works offline
  with the last saved predictions.

## App features
- India 🇮🇳 / US 🇺🇸 tabs, each with **"Prediction for <date>"**
- Top 10 likely to rise and top 10 likely to fall (only actively traded stocks, no penny stocks)
- Search any of the ~2,500 stocks to see its chance of rising and the reasons
- Backtest accuracy (last 60 trading days) and a live track record
- Tap a stock to copy its symbol and open INDmoney

## Get the APK
Every push builds it: **Releases → latest → StockPredictor.apk**. Builds use a fixed signing key,
so new versions install as updates.

## Honest limitations
Next-day moves are close to random. Expect accuracy around 50–55%. News, results and market
holidays aren't considered. BSE-only stocks and US stocks outside the S&P 500 aren't covered.
Learning project only, not investment advice.
