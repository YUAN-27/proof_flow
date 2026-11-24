from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="ProofFlow Agent API", version="0.1.0")


class HealthResponse(BaseModel):
    service: str
    status: str


@app.get("/health")
def health() -> HealthResponse:
    return HealthResponse(service="agent-api", status="ok")
