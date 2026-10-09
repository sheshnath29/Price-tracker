# Flipkart MacBook M5 price tracker

Checks Flipkart every 15 minutes for MacBook M5 laptops, keeps a price history,
and alerts you when a price drops.

## How it works

1. `tracker.py` searches Flipkart for `search_query` from `config.json`. It keeps
   only results whose title contains all of `required_keywords` and none of
   `excluded_keywords`, which filters out cases, covers and M4 models.
2. It also checks any product page URL you add to `product_urls`. Those prices
   come from the page's structured data, which makes them the most reliable.
3. Each check appends to `data/price_history.csv` and overwrites `data/latest.json`.
4. Alerts are printed, added to the GitHub Actions run summary, and sent to Telegram
   when it's configured:
   - 📉 a price fell by at least `drop_alert_percent` %
   - 🎯 a price went at or below `target_price`
   - 🆕 a new M5 listing appeared

## Running every 15 minutes

**On GitHub (no computer needed):** `.github/workflows/price-tracker.yml`
runs on a `*/15 * * * *` cron and commits the updated history back to the repo.
GitHub only runs scheduled workflows on the **default branch**, so it starts once this
is pushed to `main`. You can also start a run by hand from the Actions tab
("Run workflow"). Scheduled runs can be delayed a few minutes when GitHub is busy.

**On your own machine:**

```bash
pip install -r requirements.txt
python tracker.py --loop            # checks now, then every 15 min
python tracker.py                   # single check
```

## Telegram alerts (optional)

1. Message [@BotFather](https://t.me/BotFather) → `/newbot` → copy the bot token.
2. Send your new bot any message, then open
   `https://api.telegram.org/bot<TOKEN>/getUpdates` and copy `chat.id`.
3. In the repo go to **Settings → Secrets and variables → Actions** and add
   `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`. To run it locally, export
   them as environment variables instead.

## Tuning

Edit `config.json`:

| Key | Meaning |
|---|---|
| `search_query` | What to search on Flipkart |
| `required_keywords` / `excluded_keywords` | Title filters (case-insensitive) |
| `product_urls` | Specific product pages to track, e.g. the exact variant you want |
| `target_price` | Alert when any listing is at or below this price (₹) |
| `drop_alert_percent` | Ignore drops smaller than this percentage |

## Limitations

Flipkart has no public price API, so this tool reads the HTML page. If Flipkart
changes its layout or blocks the request, the run fails with "No matching listings
found". GitHub's datacenter IPs are the most likely to get blocked. If that happens,
use `--loop` on your own machine, or add `product_urls`, which use the more stable
structured data.

Tests run offline against saved sample pages:
`python -m unittest discover -s tests`.
