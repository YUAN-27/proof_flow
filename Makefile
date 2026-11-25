DOCKER ?= docker
MVN ?= mvn
NPM ?= npm
NODE ?= node
UV ?= uv
PYTHON ?= python
MVN_OPTS ?= -Dmaven.wagon.http.retryHandler.count=5

.PHONY: all help config build up stop dev test test-backend test-frontend test-agent test-integration test-case-integration

all: build

help:
	@echo make test            Run module tests and frontend checks
	@echo make config          Validate the Compose configuration
	@echo make build           Build the Compose images
	@echo make up              Build and start the Compose services
	@echo make stop            Stop the Compose services and keep their volumes
	@echo make dev             Start Java and Vite for local development
	@echo make test-backend    Run business API tests
	@echo make test-frontend   Lint and build the frontend
	@echo make test-agent      Run agent service tests
	@echo make test-integration  Smoke test the running Compose stack
	@echo make test-case-integration  Verify the case API on a disposable stack

config:
	$(DOCKER) compose config --quiet

build:
	$(DOCKER) compose build

up:
	$(DOCKER) compose up --build --wait

stop:
	$(DOCKER) compose stop

dev:
	$(NODE) scripts/dev.mjs

test: test-backend test-frontend test-agent

test-backend:
	$(MVN) $(MVN_OPTS) -f apps/business-api/pom.xml test

test-frontend:
	$(NPM) --prefix apps/frontend run lint
	$(NPM) --prefix apps/frontend run build

test-agent:
	$(UV) run --project apps/agent-service --locked pytest --rootdir=apps/agent-service apps/agent-service/tests

test-integration:
	$(PYTHON) tests/integration/compose_smoke.py

test-case-integration:
	$(PYTHON) tests/integration/case_smoke.py
