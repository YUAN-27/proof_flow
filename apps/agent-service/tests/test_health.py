from unittest import TestCase

from fastapi.testclient import TestClient

from proofflow_agent.main import app


class HealthTest(TestCase):
    def test_health(self) -> None:
        with TestClient(app) as client:
            response = client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"service": "agent-api", "status": "ok"})
