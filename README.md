# 📈 Stock Predictor (Android)

An Android app that shows, with the date, which NIFTY 50 stocks are most likely to **rise** or
**fall** on the next trading day. Everything runs on your phone, so there's no server or website.

## Features
- Opens straight to **"Prediction for <date>"**, plus the date of the closing prices it used
- **Top 10 likely to rise** and **top 10 likely to fall**, each with a chance % and reasons
  (RSI, MACD, 50-day average, 5-day move, volume spike)
- Searchable list of all 50 stocks
- **Track record**: remembers each day's picks and checks them against what really happened
- Backtest accuracy on the most recent 60 trading days, so you can see how good (or not) it is
- Tap a stock to copy its symbol and open **INDmoney** (if installed)
- Works offline with the last saved prediction; tap **Refresh** for new data

## How it predicts
1. Downloads 2 years of daily prices for 50 NSE stocks from Yahoo Finance.
2. Computes 12 indicators per stock per day (returns, RSI, MACD, moving-average gaps,
   Bollinger %B, volume ratio, volatility, market trend).
3. Trains a logistic-regression model on all stocks' history to predict
   "will tomorrow close higher than today?".

## Get the APK
GitHub Actions builds it on every push: open **Releases → latest** and download
`StockPredictor.apk` on your phone, then install it (allow "Install unknown apps" when asked).

## Honest limitations
Next-day stock moves are close to random. Expect accuracy around 50–55%. News, earnings and
NSE holidays aren't taken into account. Learning project only, not investment advice.
