import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import tracker  # noqa: E402
from tracker import Listing  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
CONFIG = {
    "required_keywords": ["macbook", "m5"],
    "excluded_keywords": ["case", "cover"],
    "target_price": 1_10_000,
    "drop_alert_percent": 1.0,
}


class ParseSearchPageTest(unittest.TestCase):
    def setUp(self):
        self.listings = {
            l.product_id: l for l in tracker.parse_search_page((FIXTURES / "search.html").read_text())
        }

    def test_extracts_selling_price_not_mrp_or_offer(self):
        air = self.listings["COMGZ1MBM5AIR13"]
        self.assertEqual(air.price, 114900)
        self.assertIn("MacBook Air M5", air.title)
        self.assertEqual(
            air.url, "https://www.flipkart.com/apple-macbook-air-m5-16gb-512gb/p/itmabc123?pid=COMGZ1MBM5AIR13"
        )
        self.assertEqual(self.listings["COMGZ1MBPM5PRO14"].price, 189900)

    def test_keyword_filter_keeps_only_m5_laptops(self):
        kept = sorted(pid for pid, l in self.listings.items() if tracker.matches(l, CONFIG))
        self.assertEqual(kept, ["COMGZ1MBM5AIR13", "COMGZ1MBPM5PRO14"])


class ParseProductPageTest(unittest.TestCase):
    def test_reads_json_ld_price(self):
        url = "https://www.flipkart.com/apple-macbook-pro-m5/p/itmdef456?pid=COMGZ1MBPM5PRO14&lid=X"
        item = tracker.parse_product_page((FIXTURES / "product.html").read_text(), url)
        self.assertEqual(item.price, 184990)
        self.assertEqual(item.product_id, "COMGZ1MBPM5PRO14")


class AlertsTest(unittest.TestCase):
    def item(self, price, pid="P1"):
        return Listing(pid, "Apple MacBook Air M5", price, "https://www.flipkart.com/x/p/itm1")

    def test_price_drop(self):
        alerts = tracker.build_alerts([self.item(1_12_000)], {"P1": {"price": 1_14_900}}, CONFIG)
        self.assertEqual(len(alerts), 1)
        self.assertIn("Price drop", alerts[0])

    def test_small_drop_below_threshold_is_ignored(self):
        self.assertEqual(tracker.build_alerts([self.item(1_14_800)], {"P1": {"price": 1_14_900}}, CONFIG), [])

    def test_crossing_target(self):
        # A rise never alerts; a first sighting at or under the target does.
        self.assertEqual(tracker.build_alerts([self.item(1_20_000)], {"P1": {"price": 1_14_900}}, CONFIG), [])
        alerts = tracker.build_alerts([self.item(1_05_000)], {}, CONFIG)
        self.assertIn("Below target", alerts[0])

    def test_first_run_does_not_spam_new_listings(self):
        self.assertEqual(tracker.build_alerts([self.item(1_14_900)], {}, CONFIG), [])

    def test_new_listing_after_first_run(self):
        alerts = tracker.build_alerts([self.item(1_14_900, "P2")], {"P1": {"price": 1}}, CONFIG)
        self.assertIn("New listing", alerts[0])


if __name__ == "__main__":
    unittest.main()
