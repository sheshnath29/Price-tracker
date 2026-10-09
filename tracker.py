#!/usr/bin/env python3
"""Flipkart price tracker for MacBook (M5) laptops.

Fetches Flipkart search results (and any product pages listed in config.json),
records every listing's price in data/price_history.csv, and sends an alert when
a price drops or falls below the target price.

Usage:
    python tracker.py              # check once (what the GitHub Action runs)
    python tracker.py --loop       # check every 15 minutes until stopped
    python tracker.py --loop --interval 30
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import random
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, quote_plus, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

BASE_URL = "https://www.flipkart.com"
ROOT = Path(__file__).resolve().parent
CONFIG_PATH = ROOT / "config.json"
DATA_DIR = ROOT / "data"
HISTORY_CSV = DATA_DIR / "price_history.csv"
LATEST_JSON = DATA_DIR / "latest.json"

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/18.0 Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
]

PRICE_RE = re.compile(r"₹\s?([\d,]+)")


@dataclass
class Listing:
    product_id: str
    title: str
    price: int
    url: str


# ---------------------------------------------------------------- fetching

def fetch(url: str, retries: int = 3) -> str:
    last_error: Exception | None = None
    for attempt in range(retries):
        headers = {
            "User-Agent": random.choice(USER_AGENTS),
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-IN,en;q=0.9",
        }
        try:
            resp = requests.get(url, headers=headers, timeout=30)
            if resp.status_code == 200:
                return resp.text
            last_error = RuntimeError(f"HTTP {resp.status_code} for {url}")
        except requests.RequestException as exc:
            last_error = exc
        time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"Could not fetch {url}: {last_error}")


# ---------------------------------------------------------------- parsing

def parse_price(text: str) -> int | None:
    match = PRICE_RE.search(text)
    return int(match.group(1).replace(",", "")) if match else None


def product_id_from_url(url: str) -> str:
    parsed = urlparse(url)
    pid = parse_qs(parsed.query).get("pid")
    if pid:
        return pid[0]
    match = re.search(r"/p/(itm[0-9a-z]+)", parsed.path)
    return match.group(1) if match else parsed.path


def clean_url(href: str) -> str:
    """Absolute product URL without tracking params (keeps pid)."""
    parsed = urlparse(urljoin(BASE_URL, href))
    pid = parse_qs(parsed.query).get("pid")
    query = f"?pid={pid[0]}" if pid else ""
    return f"{BASE_URL}{parsed.path}{query}"


def _card_for(anchor):
    """Climb from a product link to its result card (Flipkart marks cards with data-id)."""
    node = anchor
    for _ in range(12):
        if node.parent is None:
            break
        node = node.parent
        if node.has_attr("data-id"):
            return node
    return anchor.parent or anchor


def _card_title(card, anchor) -> str:
    candidates = [anchor.get("title"), *(a.get("title") for a in card.select("a[title]"))]
    candidates += [img.get("alt") for img in card.select("img[alt]")]
    candidates += [anchor.get_text(" ", strip=True)]
    # The longest "MacBook ..." style string is the full product name.
    candidates = [c.strip() for c in candidates if c and c.strip() and "₹" not in c]
    return max(candidates, key=len) if candidates else ""


def _card_price(card) -> int | None:
    """Selling price = first ₹ amount on the card that isn't struck through (MRP)."""
    for node in card.find_all(string=PRICE_RE):
        parent = node.parent
        struck = parent.name in ("s", "del", "strike") or "line-through" in (parent.get("style") or "")
        if not struck:
            price = parse_price(str(node))
            if price:
                return price
    return None


def parse_search_page(html: str) -> list[Listing]:
    soup = BeautifulSoup(html, "lxml")
    listings: dict[str, Listing] = {}
    for anchor in soup.select('a[href*="/p/itm"]'):
        url = clean_url(anchor["href"])
        pid = product_id_from_url(url)
        card = _card_for(anchor)
        title = _card_title(card, anchor)
        price = _card_price(card)
        if not title or not price:
            continue
        existing = listings.get(pid)
        if existing is None or len(title) > len(existing.title):
            listings[pid] = Listing(pid, title, price, url)
    return list(listings.values())


def parse_product_page(html: str, url: str) -> Listing | None:
    soup = BeautifulSoup(html, "lxml")
    # Preferred: structured data (JSON-LD) that Flipkart ships for SEO.
    for script in soup.select('script[type="application/ld+json"]'):
        try:
            data = json.loads(script.string or "")
        except json.JSONDecodeError:
            continue
        for item in data if isinstance(data, list) else [data]:
            if isinstance(item, dict) and item.get("@type") == "Product":
                offers = item.get("offers") or {}
                if isinstance(offers, list):
                    offers = offers[0] if offers else {}
                price = offers.get("price")
                if price:
                    return Listing(product_id_from_url(url), item.get("name", ""),
                                   int(float(price)), clean_url(url))
    # Fallback: page title + first ₹ amount.
    title_tag = soup.find("h1") or soup.find("title")
    price = parse_price(soup.get_text(" "))
    if title_tag and price:
        return Listing(product_id_from_url(url), title_tag.get_text(strip=True), price, clean_url(url))
    return None


def matches(listing: Listing, config: dict) -> bool:
    title = listing.title.lower()
    required = [k.lower() for k in config.get("required_keywords", [])]
    excluded = [k.lower() for k in config.get("excluded_keywords", [])]
    return all(k in title for k in required) and not any(k in title for k in excluded)


# ---------------------------------------------------------------- storage

def load_json(path: Path, default):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def append_history(listings: list[Listing], checked_at: str) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    new_file = not HISTORY_CSV.exists()
    with HISTORY_CSV.open("a", newline="") as fh:
        writer = csv.writer(fh)
        if new_file:
            writer.writerow(["checked_at", "product_id", "title", "price", "url"])
        for item in listings:
            writer.writerow([checked_at, item.product_id, item.title, item.price, item.url])


# ---------------------------------------------------------------- alerts

def build_alerts(listings: list[Listing], previous: dict, config: dict) -> list[str]:
    target = config.get("target_price")
    drop_pct = float(config.get("drop_alert_percent", 0))
    alerts = []
    for item in listings:
        old = previous.get(item.product_id, {}).get("price")
        line = f"{item.title}\n₹{item.price:,}"
        if old and item.price < old and (old - item.price) / old * 100 >= drop_pct:
            alerts.append(f"📉 Price drop: {line} (was ₹{old:,}, -₹{old - item.price:,})\n{item.url}")
        elif target and item.price <= target and (old is None or old > target):
            alerts.append(f"🎯 Below target ₹{target:,}: {line}\n{item.url}")
        elif old is None and previous:
            alerts.append(f"🆕 New listing: {line}\n{item.url}")
    return alerts


def send_telegram(message: str) -> None:
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat_id = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat_id:
        return
    try:
        requests.post(
            f"https://api.telegram.org/bot{token}/sendMessage",
            json={"chat_id": chat_id, "text": message, "disable_web_page_preview": True},
            timeout=20,
        ).raise_for_status()
    except requests.RequestException as exc:
        print(f"Telegram notification failed: {exc}", file=sys.stderr)


def write_job_summary(listings: list[Listing], alerts: list[str], checked_at: str) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    lines = [f"## Flipkart MacBook M5 prices — {checked_at}", ""]
    lines += ["| Product | Price |", "|---|---|"]
    for item in sorted(listings, key=lambda l: l.price):
        lines.append(f"| [{item.title}]({item.url}) | ₹{item.price:,} |")
    if alerts:
        lines += ["", "### Alerts", *(f"- {a.splitlines()[0]}" for a in alerts)]
    with open(summary_path, "a") as fh:
        fh.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------- main

def check_once(config: dict) -> int:
    checked_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    found: dict[str, Listing] = {}

    query = config.get("search_query")
    if query:
        html = fetch(f"{BASE_URL}/search?q={quote_plus(query)}")
        for item in parse_search_page(html):
            if matches(item, config):
                found[item.product_id] = item

    for url in config.get("product_urls", []):
        item = parse_product_page(fetch(url), url)
        if item:
            found[item.product_id] = item

    listings = sorted(found.values(), key=lambda l: l.price)
    if not listings:
        print("No matching listings found — Flipkart may have blocked the request "
              "or changed its page layout.", file=sys.stderr)
        return 1

    previous = load_json(LATEST_JSON, {})
    alerts = build_alerts(listings, previous, config)

    append_history(listings, checked_at)
    LATEST_JSON.write_text(json.dumps(
        {l.product_id: {"title": l.title, "price": l.price, "url": l.url, "checked_at": checked_at}
         for l in listings}, indent=2, ensure_ascii=False) + "\n")

    print(f"[{checked_at}] {len(listings)} listing(s):")
    for item in listings:
        print(f"  ₹{item.price:>9,}  {item.title}")
    for alert in alerts:
        print("\n" + alert)
        send_telegram(alert)
    write_job_summary(listings, alerts, checked_at)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--loop", action="store_true", help="keep checking on an interval")
    parser.add_argument("--interval", type=float, default=15, help="minutes between checks (default 15)")
    args = parser.parse_args()

    config = load_json(CONFIG_PATH, {})
    if not args.loop:
        return check_once(config)
    while True:
        try:
            check_once(config)
        except Exception as exc:  # keep the loop alive through transient failures
            print(f"Check failed: {exc}", file=sys.stderr)
        time.sleep(args.interval * 60)


if __name__ == "__main__":
    sys.exit(main())
