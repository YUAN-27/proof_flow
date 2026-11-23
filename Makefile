DOCKER ?= docker
MVN ?= mvn
NPM ?= npm
PYTHON ?= python

.DEFAULT_GOAL := help

.PHONY: help config build up ps test-java check-frontend test-agent

help:
	@echo make config          Validate the Compose configuration
	@echo make build           Build the Compose images
	@echo make up              Build and start the Compose services
	@echo make ps              Show Compose service status
	@echo make test-java       Run business API tests
	@echo make check-frontend  Lint and build the frontend
	@echo make test-agent      Run agent service tests

config:
	$(DOCKER) compose config --quiet

build:
	$(DOCKER) compose build

up:
	$(DOCKER) compose up --build --wait

ps:
	$(DOCKER) compose ps

test-java:
	$(MVN) -f business-api/pom.xml test

check-frontend:
	$(NPM) --prefix frontend run lint
	$(NPM) --prefix frontend run build

test-agent:
	cd agent-service && $(PYTHON) -m pytest
