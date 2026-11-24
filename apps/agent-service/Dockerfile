FROM python:3.12.12-slim AS base
WORKDIR /app

FROM base AS dependencies
COPY --from=ghcr.io/astral-sh/uv:0.13.8 /uv /usr/local/bin/uv
ENV UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev

FROM base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV PATH="/app/.venv/bin:$PATH"
COPY --from=dependencies /app/.venv ./.venv
COPY proofflow_agent ./proofflow_agent
USER 10001:10001
CMD ["uvicorn", "proofflow_agent.main:app", "--host", "0.0.0.0", "--port", "8000"]
