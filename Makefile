-include .env.make

ifneq ($(strip $(JAVA_HOME)),)
export JAVA_HOME
endif

DOCKER ?= docker
ifneq ($(filter Windows32 Windows_NT %mingw32 %mingw64,$(MAKE_HOST) $(OS)),)
MVN ?= mvn.cmd
NPM ?= npm.cmd
else
MVN ?= mvn
NPM ?= npm
endif
NODE ?= node
UV ?= uv
PYTHON ?= python
MVN_OPTS ?= -Dmaven.wagon.http.retryHandler.count=5

.PHONY: all help config build up stop dev test test-backend test-frontend test-agent test-integration test-case-integration

all: build

help:
	@echo make build           Build the Compose images
	@echo make test            Run local Java and Python tests
	@echo make up              Start the Compose stack at http://127.0.0.1:8080/ by default
	@echo make help            List the main commands

config:
	$(DOCKER) compose config --quiet

build:
	$(DOCKER) compose build

up:
	$(DOCKER) compose up --build --wait

stop:
	$(DOCKER) compose stop

dev: export PROOFFLOW_MAKE_MVN = $(MVN)
dev:
	$(NODE) scripts/dev.mjs

test: test-backend test-agent

ifeq ($(CI),true)
test: test-frontend
endif

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
