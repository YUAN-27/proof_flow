DOCKER ?= docker
MVN ?= mvn
NPM ?= npm
PYTHON ?= python

.DEFAULT_GOAL := all

.PHONY: all help config build up test test-backend test-frontend test-agent

all: help

help:
	@echo make test            Run all project checks
	@echo make config          Validate the Compose configuration
	@echo make build           Build the Compose images
	@echo make up              Build and start the Compose services
	@echo make test-backend    Run business API tests
	@echo make test-frontend   Lint and build the frontend
	@echo make test-agent      Run agent service tests

config:
	$(DOCKER) compose config --quiet

build:
	$(DOCKER) compose build

up:
	$(DOCKER) compose up --build --wait

test: test-backend test-frontend test-agent

test-backend:
	$(MVN) -f business-api/pom.xml test

test-frontend:
	$(NPM) --prefix frontend run lint
	$(NPM) --prefix frontend run build

test-agent:
	$(PYTHON) -m pytest --rootdir=agent-service agent-service/tests
