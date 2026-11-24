DOCKER ?= docker
MVN ?= mvn
NPM ?= npm
UV ?= uv
PYTHON ?= python
MVN_OPTS ?= -Dmaven.wagon.http.retryHandler.count=5

.DEFAULT_GOAL := build

.PHONY: all help config build up test test-backend test-frontend test-agent test-integration

all: build

help:
	@echo make test            Run module tests and frontend checks
	@echo make config          Validate the Compose configuration
	@echo make build           Build the Compose images
	@echo make up              Build and start the Compose services
	@echo make test-backend    Run business API tests
	@echo make test-frontend   Lint and build the frontend
	@echo make test-agent      Run agent service tests
	@echo make test-integration  Smoke test the running Compose stack

config:
	$(DOCKER) compose config --quiet

build:
	$(DOCKER) compose build

up:
	$(DOCKER) compose up --build --wait

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
