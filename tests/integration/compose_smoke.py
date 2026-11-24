import json
import os
import time
import unittest
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


BASE_URL = os.environ.get("PROOFFLOW_SMOKE_URL", "http://127.0.0.1:8080").rstrip("/")


def get(path):
    deadline = time.monotonic() + 15
    while True:
        try:
            return urlopen(f"{BASE_URL}{path}", timeout=2)
        except HTTPError:
            raise
        except (URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.5)


class ComposeSmokeTest(unittest.TestCase):
    def test_frontend_serves_home_page(self):
        with get("/") as response:
            self.assertEqual(response.status, 200)
            self.assertIn(b"<title>ProofFlow Investigator</title>", response.read())

    def test_frontend_proxies_the_business_api(self):
        with get("/api/v1/health") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.load(response), {"service": "business-api", "status": "ok"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
