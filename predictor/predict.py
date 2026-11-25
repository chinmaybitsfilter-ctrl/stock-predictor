"""
Daily NSE stock rise/fall predictor.

Runs once a day (via GitHub Actions) after Indian market close:
  1. Downloads ~2 years of daily prices for the stocks in tickers.txt (Yahoo Finance).
  2. Builds technical features (RSI, MACD, moving averages, momentum, volume...).
  3. Trains a logistic-regression model on ALL stocks' history to predict
     "will tomorrow's close be higher than today's?".
  4. Measures honest out-of-sample accuracy on the most recent 60 trading days.
  5. Writes docs/data/<date>.json, latest.json, index.json and history.json
     (history also scores PAST predictions against what actually happened).

This is an educational project. Short-term price moves are close to random;
expect accuracy only slightly above 50%. Not investment advice.
"""

from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parent.parent
TICKERS_FILE = Path(__file__).resolve().parent / "tickers.txt"
DATA_DIR = ROOT / "docs" / "data"
TOP_N = 10
TEST_DAYS = 60  # most recent trading days held out for accuracy check
IST = timezone(timedelta(hours=5, minutes=30))

FEATURES = [
    "ret_1", "ret_5", "ret_20", "rsi_14", "macd_hist", "sma20_gap",
    "sma50_gap", "bb_pctb", "vol_ratio", "volatility_20", "mkt_ret_1", "mkt_ret_5",
]


# ----------------------------------------------------------------- data ----

def load_tickers() -> list[str]:
    lines = TICKERS_FILE.read_text().splitlines()
    return [l.strip() for l in lines if l.strip() and not l.strip().startswith("#")]


def fetch_prices(symbols: list[str]) -> dict[str, pd.DataFrame]:
    """Return {symbol: DataFrame[Open, High, Low, Close, Volume]} indexed by date."""
    import yfinance as yf  # imported here so tests can run without it

    out: dict[str, pd.DataFrame] = {}
    for sym in symbols:
        try:
            df = yf.Ticker(f"{sym}.NS").history(period="2y", interval="1d", auto_adjust=True)
        except Exception as e:  # network hiccup, delisted symbol, etc.
            print(f"  ! {sym}: {e}", file=sys.stderr)
            continue
        if df is None or len(df) < 120:
            print(f"  ! {sym}: not enough data, skipped", file=sys.stderr)
            continue
        df = df[["Open", "High", "Low", "Close", "Volume"]].dropna()
        df.index = pd.to_datetime(df.index).tz_localize(None).normalize()
        df = df[~df.index.duplicated(keep="last")]
        out[sym] = df
        print(f"  ok {sym}: {len(df)} days, last {df.index[-1].date()}")
    return out


# ------------------------------------------------------------- features ----

def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    rs = gain / loss.replace(0, np.nan)
    return (100 - 100 / (1 + rs)).fillna(50)


def build_features(df: pd.DataFrame, market_ret: pd.Series) -> pd.DataFrame:
    c, v = df["Close"], df["Volume"]
    f = pd.DataFrame(index=df.index)
    f["close"] = c
    f["ret_1"] = c.pct_change()
    f["ret_5"] = c.pct_change(5)
    f["ret_20"] = c.pct_change(20)
    f["rsi_14"] = rsi(c)
    ema12, ema26 = c.ewm(span=12, adjust=False).mean(), c.ewm(span=26, adjust=False).mean()
    macd = ema12 - ema26
    f["macd_hist"] = (macd - macd.ewm(span=9, adjust=False).mean()) / c
    sma20, sma50 = c.rolling(20).mean(), c.rolling(50).mean()
    f["sma20_gap"] = c / sma20 - 1
    f["sma50_gap"] = c / sma50 - 1
    std20 = c.rolling(20).std()
    f["bb_pctb"] = (c - (sma20 - 2 * std20)) / (4 * std20)
    f["vol_ratio"] = v / v.rolling(20).mean()
    f["volatility_20"] = f["ret_1"].rolling(20).std()
    mr = market_ret.reindex(df.index)
    f["mkt_ret_1"] = mr
    f["mkt_ret_5"] = (1 + mr).rolling(5).apply(np.prod, raw=True) - 1
    # Target: did the NEXT day's close end higher?
    f["next_ret"] = c.shift(-1) / c - 1
    f["target"] = (f["next_ret"] > 0).astype(float)
    f.loc[f["next_ret"].isna(), "target"] = np.nan
    return f.replace([np.inf, -np.inf], np.nan)


def market_returns(prices: dict[str, pd.DataFrame]) -> pd.Series:
    """Equal-weight average daily return of all stocks = a simple 'market' proxy."""
    rets = pd.DataFrame({s: d["Close"].pct_change() for s, d in prices.items()})
    return rets.mean(axis=1)


def reasons(row: pd.Series) -> list[str]:
    r = []
    if row["rsi_14"] >= 70: r.append(f"RSI {row['rsi_14']:.0f} (overbought)")
    elif row["rsi_14"] <= 30: r.append(f"RSI {row['rsi_14']:.0f} (oversold)")
    if row["macd_hist"] > 0: r.append("MACD bullish")
    else: r.append("MACD bearish")
    r.append("above 50-day avg" if row["sma50_gap"] > 0 else "below 50-day avg")
    if row["ret_5"] > 0.04: r.append(f"+{row['ret_5']*100:.1f}% in 5 days")
    elif row["ret_5"] < -0.04: r.append(f"{row['ret_5']*100:.1f}% in 5 days")
    if row["vol_ratio"] > 1.8: r.append("volume spike")
    return r


# ---------------------------------------------------------------- model ----

def make_model():
    return make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=500))


def next_trading_day(d: date) -> date:
    # Skips weekends only; NSE holidays are not handled.
    d += timedelta(days=1)
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def run(prices: dict[str, pd.DataFrame]) -> dict:
    if len(prices) < 5:
        raise SystemExit("Too few stocks downloaded — aborting so we don't publish junk.")

    mkt = market_returns(prices)
    feats = {s: build_features(d, mkt) for s, d in prices.items()}
    panel = pd.concat([f.assign(symbol=s) for s, f in feats.items()])
    panel.index.name = "date"
    panel = panel.reset_index()

    labeled = panel.dropna(subset=FEATURES + ["target"])
    dates = np.sort(labeled["date"].unique())
    cutoff = dates[-TEST_DAYS] if len(dates) > TEST_DAYS + 100 else dates[len(dates) // 2]
    train, test = labeled[labeled["date"] < cutoff], labeled[labeled["date"] >= cutoff]

    # Honest out-of-sample check
    m = make_model().fit(train[FEATURES], train["target"])
    p = m.predict_proba(test[FEATURES])[:, 1]
    acc_all = float(((p > 0.5) == (test["target"] == 1)).mean())
    # Accuracy of only the daily top-N "rise" and "fall" picks
    t = test.assign(p=p)
    hits = total = 0
    for _, day in t.groupby("date"):
        up = day.nlargest(TOP_N, "p"); dn = day.nsmallest(TOP_N, "p")
        hits += int((up["target"] == 1).sum() + (dn["target"] == 0).sum())
        total += len(up) + len(dn)
    acc_top = hits / total if total else float("nan")
    base_up = float(test["target"].mean())

    # Retrain on everything, predict the latest day for each stock
    model = make_model().fit(labeled[FEATURES], labeled["target"])
    latest_date = panel["date"].max()
    rows = []
    for s, f in feats.items():
        last = f.dropna(subset=FEATURES)
        if last.empty or last.index[-1] != latest_date:
            continue  # stale data for this stock
        row = last.iloc[-1]
        prob = float(model.predict_proba(row[FEATURES].to_frame().T.astype(float))[:, 1][0])
        rows.append({
            "symbol": s,
            "close": round(float(row["close"]), 2),
            "change_pct": round(float(row["ret_1"]) * 100, 2),
            "prob_up": round(prob, 4),
            "signal": "RISE" if prob >= 0.5 else "FALL",
            "reasons": reasons(row),
        })
    rows.sort(key=lambda r: r["prob_up"], reverse=True)

    data_day = pd.Timestamp(latest_date).date()
    return {
        "generated_at": datetime.now(IST).strftime("%Y-%m-%d %H:%M IST"),
        "data_date": data_day.isoformat(),
        "prediction_for": next_trading_day(data_day).isoformat(),
        "model": {
            "type": "Logistic regression on 12 technical indicators (pooled across all stocks)",
            "stocks_used": len(rows),
            "test_days": int(t["date"].nunique()),
            "accuracy_all": round(acc_all, 4),
            "accuracy_top_picks": round(acc_top, 4),
            "baseline_up_rate": round(base_up, 4),
        },
        "top_rise": rows[:TOP_N],
        "top_fall": sorted(rows, key=lambda r: r["prob_up"])[:TOP_N],
        "all": rows,
    }


# ------------------------------------------------------------- tracking ----

def score_history(history: list[dict], prices: dict[str, pd.DataFrame]) -> list[dict]:
    """Fill in actual results for past predictions once their day has data."""
    for h in history:
        if h.get("result"):
            continue
        day = pd.Timestamp(h["prediction_for"])
        hits = n = 0
        for side, want_up in (("rise", True), ("fall", False)):
            for sym in h.get(side, []):
                d = prices.get(sym)
                if d is None or day not in d.index:
                    continue
                i = d.index.get_loc(day)
                if i == 0:
                    continue
                went_up = d["Close"].iloc[i] > d["Close"].iloc[i - 1]
                hits += int(went_up == want_up); n += 1
        if n >= TOP_N:  # enough stocks traded that day to score it
            h["result"] = {"correct": hits, "total": n, "accuracy": round(hits / n, 4)}
    return history


def write_outputs(result: dict, prices: dict[str, pd.DataFrame]) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    day = result["prediction_for"]

    hist_file = DATA_DIR / "history.json"
    history = json.loads(hist_file.read_text()) if hist_file.exists() else []
    history = [h for h in history if h["prediction_for"] != day]  # re-run same day = replace
    history.append({
        "prediction_for": day,
        "rise": [r["symbol"] for r in result["top_rise"]],
        "fall": [r["symbol"] for r in result["top_fall"]],
    })
    history = score_history(sorted(history, key=lambda h: h["prediction_for"]), prices)
    scored = [h["result"] for h in history if h.get("result")]
    result["live_track_record"] = {
        "days_scored": len(scored),
        "correct": sum(s["correct"] for s in scored),
        "total": sum(s["total"] for s in scored),
    }

    for name in (f"{day}.json", "latest.json"):
        (DATA_DIR / name).write_text(json.dumps(result, indent=1))
    hist_file.write_text(json.dumps(history, indent=1))
    dates = sorted({p.stem for p in DATA_DIR.glob("????-??-??.json")}, reverse=True)
    (DATA_DIR / "index.json").write_text(json.dumps(dates))
    print(f"Wrote predictions for {day} ({len(result['all'])} stocks). "
          f"Out-of-sample accuracy: {result['model']['accuracy_all']:.1%} all, "
          f"{result['model']['accuracy_top_picks']:.1%} top picks.")


if __name__ == "__main__":
    syms = load_tickers()
    print(f"Downloading {len(syms)} stocks…")
    px = fetch_prices(syms)
    res = run(px)
    write_outputs(res, px)
