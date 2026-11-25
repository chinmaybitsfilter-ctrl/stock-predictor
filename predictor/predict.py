"""
Daily rise/fall predictor for the stocks you can buy on INDmoney:
  * India: every NSE-listed equity (EQ series)
  * US:    the S&P 500

Runs in GitHub Actions every weekday evening. For each market it
  1. downloads 2 years of daily prices (Yahoo Finance),
  2. builds 12 technical indicators per stock per day,
  3. trains a logistic-regression model on all stocks of that market to predict
     "will the next day's close be higher?",
  4. measures honest accuracy on the most recent 60 trading days,
  5. writes data/latest.json (read by the Android app) and data/history.json
     (past picks, scored against what actually happened).

Educational project: next-day moves are close to random. Not investment advice.
"""

from __future__ import annotations

import io
import json
import sys
import time
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
HERE = Path(__file__).resolve().parent
TOP_N = 10
TEST_DAYS = 60
IST = timezone(timedelta(hours=5, minutes=30))
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      "Accept": "*/*"}

FEATURES = ["ret_1", "ret_5", "ret_20", "rsi_14", "macd_hist", "sma20_gap", "sma50_gap",
            "bb_pctb", "vol_ratio", "volatility_20", "mkt_ret_1", "mkt_ret_5"]

MARKETS = {
    "IN": {"name": "India (NSE)", "currency": "₹", "suffix": ".NS",
           "min_value": 1e7},      # top picks need avg daily traded value ≥ ₹1 crore
    "US": {"name": "US (S&P 500)", "currency": "$", "suffix": "",
           "min_value": 5e6},      # ≥ $5M a day
}


# ---------------------------------------------------------- stock lists ----

def http_get(url: str) -> bytes:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def cached_list(name: str, fetch) -> list[str]:
    """Fetch a symbol list; on failure fall back to the last saved copy."""
    f = HERE / f"{name}.txt"
    try:
        syms = fetch()
        if len(syms) > 50:
            f.write_text("\n".join(syms) + "\n")
            print(f"{name}: {len(syms)} symbols (fresh)")
            return syms
        raise ValueError("list too short")
    except Exception as e:
        print(f"{name}: fetch failed ({e}); using saved list", file=sys.stderr)
        return [l.strip() for l in f.read_text().splitlines() if l.strip()]


def nse_symbols() -> list[str]:
    def fetch():
        for host in ("nsearchives.nseindia.com", "archives.nseindia.com"):
            try:
                raw = http_get(f"https://{host}/content/equities/EQUITY_L.csv")
                df = pd.read_csv(io.BytesIO(raw))
                df.columns = [c.strip().upper() for c in df.columns]
                return sorted(df.loc[df["SERIES"].str.strip() == "EQ", "SYMBOL"].str.strip().unique())
            except Exception as e:
                print(f"  NSE list from {host} failed: {e}", file=sys.stderr)
        raise RuntimeError("NSE list unavailable")
    return cached_list("nse_symbols", fetch)


def sp500_symbols() -> list[str]:
    def fetch():
        raw = http_get("https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv")
        df = pd.read_csv(io.BytesIO(raw))
        return sorted(df["Symbol"].astype(str).str.strip().unique())
    return cached_list("sp500_symbols", fetch)


# ------------------------------------------------------------- download ----

def download(symbols: list[str], suffix: str) -> dict[str, pd.DataFrame]:
    import yfinance as yf

    out: dict[str, pd.DataFrame] = {}
    yahoo = {s + suffix if suffix else s.replace(".", "-"): s for s in symbols}
    tickers = list(yahoo)
    for i in range(0, len(tickers), 100):
        chunk = tickers[i:i + 100]
        for attempt in range(3):
            try:
                df = yf.download(chunk, period="2y", interval="1d", group_by="ticker",
                                 auto_adjust=True, threads=True, progress=False)
                break
            except Exception as e:
                print(f"  chunk {i}: {e}; retrying", file=sys.stderr)
                time.sleep(10 * (attempt + 1))
        else:
            continue
        if df is None or df.empty:
            continue
        if not isinstance(df.columns, pd.MultiIndex):  # single ticker edge case
            df = pd.concat({chunk[0]: df}, axis=1)
        for t in df.columns.get_level_values(0).unique():
            d = df[t][["Close", "Volume"]].dropna()
            if len(d) < 120:
                continue
            d.index = pd.to_datetime(d.index).tz_localize(None).normalize()
            out[yahoo.get(t, t)] = d[~d.index.duplicated(keep="last")]
        print(f"  {min(i + 100, len(tickers))}/{len(tickers)} requested, {len(out)} usable")
        time.sleep(1)
    return out


# ------------------------------------------------------------- features ----

def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    return (100 - 100 / (1 + gain / loss.replace(0, np.nan))).fillna(50)


def build_features(df: pd.DataFrame, mkt: pd.Series) -> pd.DataFrame:
    c, v = df["Close"], df["Volume"]
    f = pd.DataFrame(index=df.index)
    f["close"] = c
    f["ret_1"] = c.pct_change()
    f["ret_5"] = c.pct_change(5)
    f["ret_20"] = c.pct_change(20)
    f["rsi_14"] = rsi(c)
    macd = c.ewm(span=12, adjust=False).mean() - c.ewm(span=26, adjust=False).mean()
    f["macd_hist"] = (macd - macd.ewm(span=9, adjust=False).mean()) / c
    sma20, sma50 = c.rolling(20).mean(), c.rolling(50).mean()
    f["sma20_gap"] = c / sma20 - 1
    f["sma50_gap"] = c / sma50 - 1
    std20 = c.rolling(20).std()
    f["bb_pctb"] = (c - (sma20 - 2 * std20)) / (4 * std20)
    f["vol_ratio"] = v / v.rolling(20).mean()
    f["volatility_20"] = f["ret_1"].rolling(20).std()
    mr = mkt.reindex(df.index)
    f["mkt_ret_1"] = mr
    f["mkt_ret_5"] = (1 + mr).rolling(5).apply(np.prod, raw=True) - 1
    f["value_20"] = (c * v).rolling(20).mean()      # avg daily traded value (liquidity)
    nxt = c.shift(-1) / c - 1
    f["target"] = np.where(nxt.isna(), np.nan, (nxt > 0).astype(float))
    return f.replace([np.inf, -np.inf], np.nan)


def reasons(row) -> str:
    r = []
    if row.rsi_14 >= 70: r.append(f"RSI {row.rsi_14:.0f} overbought")
    elif row.rsi_14 <= 30: r.append(f"RSI {row.rsi_14:.0f} oversold")
    r.append("MACD bullish" if row.macd_hist > 0 else "MACD bearish")
    r.append("above 50-day avg" if row.sma50_gap > 0 else "below 50-day avg")
    if row.ret_5 > 0.04: r.append(f"+{row.ret_5 * 100:.1f}% in 5 days")
    elif row.ret_5 < -0.04: r.append(f"{row.ret_5 * 100:.1f}% in 5 days")
    if row.vol_ratio > 1.8: r.append("volume spike")
    return " · ".join(r)


def next_trading_day(d: date) -> date:
    d += timedelta(days=1)
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def make_model():
    return make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=300))


# ----------------------------------------------------------------- run ----

def run_market(code: str, prices: dict[str, pd.DataFrame]) -> dict:
    cfg = MARKETS[code]
    if len(prices) < 20:
        raise RuntimeError(f"{code}: only {len(prices)} stocks downloaded")

    # Use only dates where most stocks traded (drops stray partial rows)
    latest_counts = pd.Series([d.index[-1] for d in prices.values()]).value_counts()
    latest = latest_counts.index[0]
    rets = pd.DataFrame({s: d["Close"].pct_change() for s, d in prices.items()})
    mkt = rets.clip(-0.2, 0.2).mean(axis=1)

    frames = []
    for s, d in prices.items():
        f = build_features(d.loc[:latest], mkt)
        f["symbol"] = s
        frames.append(f.iloc[55:])
    panel = pd.concat(frames)
    panel.index.name = "date"
    panel = panel.reset_index()
    for col in FEATURES:  # tame extreme penny-stock values
        lo, hi = panel[col].quantile([0.001, 0.999])
        panel[col] = panel[col].clip(lo, hi)

    labeled = panel.dropna(subset=FEATURES + ["target"])
    dates = np.sort(labeled["date"].unique())
    cutoff = dates[-TEST_DAYS]
    train, test = labeled[labeled["date"] < cutoff], labeled[labeled["date"] >= cutoff]

    m = make_model().fit(train[FEATURES], train["target"])
    t = test.assign(p=m.predict_proba(test[FEATURES])[:, 1])
    acc_all = float(((t["p"] > 0.5) == (t["target"] == 1)).mean())
    liquid_t = t[t["value_20"] >= cfg["min_value"]]
    hits = total = 0
    for _, day in liquid_t.groupby("date"):
        up, dn = day.nlargest(TOP_N, "p"), day.nsmallest(TOP_N, "p")
        hits += int((up["target"] == 1).sum() + (dn["target"] == 0).sum())
        total += len(up) + len(dn)

    model = make_model().fit(labeled[FEATURES], labeled["target"])
    now = panel[(panel["date"] == latest)].dropna(subset=FEATURES).copy()
    now["p"] = model.predict_proba(now[FEATURES])[:, 1]
    now = now.sort_values("p", ascending=False)

    stocks = [[r.symbol, round(float(r.close), 2), round(float(r.ret_1) * 100, 2),
               round(float(r.p), 4), int(r.value_20 >= cfg["min_value"]), reasons(r)]
              for r in now.itertuples()]
    liquid = [s for s in stocks if s[4]]
    data_day = pd.Timestamp(latest).date()
    print(f"{code}: {len(stocks)} stocks predicted ({len(liquid)} liquid); "
          f"backtest {acc_all:.1%} all, {hits / max(total, 1):.1%} top picks")
    return {
        "name": cfg["name"], "currency": cfg["currency"],
        "data_date": data_day.isoformat(),
        "prediction_for": next_trading_day(data_day).isoformat(),
        "accuracy_all": round(acc_all, 4),
        "accuracy_top": round(hits / max(total, 1), 4),
        "test_days": TEST_DAYS,
        "count": len(stocks),
        "top_rise": [s[0] for s in liquid[:TOP_N]],
        "top_fall": [s[0] for s in liquid[::-1][:TOP_N]],
        # [symbol, close, change%, probUp, liquid(1/0), reasons] sorted by probUp desc
        "stocks": stocks,
    }


def score_history(history: list[dict], code: str, prices: dict[str, pd.DataFrame]) -> None:
    for h in history:
        if h["market"] != code or h.get("correct") is not None:
            continue
        day = pd.Timestamp(h["for"])
        ok = n = 0
        for side, want_up in (("rise", True), ("fall", False)):
            for s in h[side]:
                d = prices.get(s)
                if d is None or day not in d.index:
                    continue
                i = d.index.get_loc(day)
                if i == 0:
                    continue
                ok += int((d["Close"].iloc[i] > d["Close"].iloc[i - 1]) == want_up)
                n += 1
        if n >= TOP_N:
            h["correct"], h["total"] = ok, n


def main():
    DATA.mkdir(exist_ok=True)
    latest_file, hist_file = DATA / "latest.json", DATA / "history.json"
    old = json.loads(latest_file.read_text()) if latest_file.exists() else {"markets": {}}
    history = json.loads(hist_file.read_text()) if hist_file.exists() else []
    markets = dict(old.get("markets", {}))
    lists = {"IN": nse_symbols, "US": sp500_symbols}
    ok_any = False

    for code, get_list in lists.items():
        try:
            syms = get_list()
            print(f"Downloading {code}: {len(syms)} symbols…")
            prices = download(syms, MARKETS[code]["suffix"])
            res = run_market(code, prices)
        except Exception as e:  # keep the other market's update even if one fails
            print(f"!! {code} failed: {e}", file=sys.stderr)
            continue
        ok_any = True
        markets[code] = res
        history = [h for h in history if not (h["market"] == code and h["for"] == res["prediction_for"])]
        history.append({"market": code, "for": res["prediction_for"],
                        "rise": res["top_rise"], "fall": res["top_fall"]})
        score_history(history, code, prices)

    if not ok_any:
        raise SystemExit("Both markets failed; nothing written.")
    history = sorted(history, key=lambda h: (h["for"], h["market"]))[-500:]
    out = {"generated_at": datetime.now(IST).strftime("%d %b %Y, %I:%M %p IST"), "markets": markets}
    latest_file.write_text(json.dumps(out, separators=(",", ":"), ensure_ascii=False))
    hist_file.write_text(json.dumps(history, separators=(",", ":")))
    print("Done.")


if __name__ == "__main__":
    main()
