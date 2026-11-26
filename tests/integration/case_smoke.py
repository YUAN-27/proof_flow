import json
import os
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from compose_smoke import BASE_URL


class CaseSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.token = os.environ.get("PROOFFLOW_CASES_TOKEN")
        if not cls.token:
            raise RuntimeError("Set PROOFFLOW_CASES_TOKEN for the disposable test stack")

    def request(self, path, *, method="GET", body=None, authenticated=True):
        headers = {"Authorization": f"Bearer {self.token}"} if authenticated else {}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        return urlopen(Request(f"{BASE_URL}{path}", data=data, headers=headers, method=method), timeout=5)

    def test_cases_require_credentials(self):
        with self.assertRaises(HTTPError) as error:
            self.request("/api/v1/cases", method="POST", body={"title": "CI smoke case"}, authenticated=False)
        with error.exception as response:
            self.assertEqual(response.code, 401)
            self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")

    def test_case_lifecycle_and_stale_version_through_proxy(self):
        with self.request("/api/v1/cases", method="POST", body={"title": "CI smoke case"}) as response:
            self.assertEqual(response.status, 201)
            created = json.load(response)
            case_path = f"/api/v1/cases/{created['id']}"
            self.assertEqual(response.headers["Location"], case_path)
            self.assertEqual((created["status"], created["version"]), ("OPEN", 1))

        with self.request(case_path) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.load(response), created)

        status_path = f"{case_path}/status"
        with self.request(status_path, method="PATCH", body={"expectedVersion": 1, "status": "IN_REVIEW"}) as response:
            updated = json.load(response)
            self.assertEqual((updated["status"], updated["version"]), ("IN_REVIEW", 2))

        with self.assertRaises(HTTPError) as error:
            self.request(status_path, method="PATCH", body={"expectedVersion": 1, "status": "CLOSED"})
        with error.exception as response:
            self.assertEqual(response.code, 409)
            self.assertEqual(json.load(response)["code"], "VERSION_CONFLICT")

        with self.request(status_path, method="PATCH", body={"expectedVersion": 2, "status": "CLOSED"}) as response:
            closed = json.load(response)
            self.assertEqual((closed["status"], closed["version"]), ("CLOSED", 3))


if __name__ == "__main__":
    unittest.main(verbosity=2)
