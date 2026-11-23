FROM python:3.12.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY proofflow_agent ./proofflow_agent
USER 10001:10001
CMD ["uvicorn", "proofflow_agent.main:app", "--host", "0.0.0.0", "--port", "8000"]
