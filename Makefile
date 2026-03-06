SHELL := /bin/bash

.PHONY: local-check local-up local-down run-api test verify

NAMESPACE ?= sie
RELEASE_EVENT_BUS ?= sie-event-bus-kafka
RELEASE_DEF_DB ?= sie-definition-database
RELEASE_SCHEMA_REG ?= sie-schema-registry

EVENT_BUS_CHART ?= ../sie-event-bus/deploy/helm/sie-event-bus-kafka
DEF_DB_CHART ?= ../sie-definition-database
SCHEMA_REG_CHART ?= ../sie-schema-registry/deploy/helm/sie-schema-registry

PORT_FORWARD_PID_FILE ?= .local-port-forwards.pids

local-check:
	@command -v kubectl >/dev/null 2>&1 || { echo "Missing required command: kubectl"; exit 1; }
	@command -v helm >/dev/null 2>&1 || { echo "Missing required command: helm"; exit 1; }
	@kubectl config current-context >/dev/null 2>&1 || { echo "No active Kubernetes context. Configure kubeconfig first."; exit 1; }
	@kubectl get ns >/dev/null 2>&1 || { echo "Cannot reach Kubernetes API with current context."; exit 1; }
	@test -d "$(EVENT_BUS_CHART)" || { echo "Missing chart directory: $(EVENT_BUS_CHART)"; exit 1; }
	@test -d "$(DEF_DB_CHART)" || { echo "Missing chart directory: $(DEF_DB_CHART)"; exit 1; }
	@test -d "$(SCHEMA_REG_CHART)" || { echo "Missing chart directory: $(SCHEMA_REG_CHART)"; exit 1; }
	@echo "local-check passed"

local-up:
	@$(MAKE) local-check
	kubectl get ns $(NAMESPACE) >/dev/null 2>&1 || kubectl create ns $(NAMESPACE) >/dev/null
	helm upgrade --install $(RELEASE_EVENT_BUS) $(EVENT_BUS_CHART) -n $(NAMESPACE) --create-namespace --wait --timeout 10m0s --set persistence.enabled=false
	helm upgrade --install $(RELEASE_SCHEMA_REG) $(SCHEMA_REG_CHART) -n $(NAMESPACE) --create-namespace --wait --timeout 10m0s \
		--set kafka.bootstrapServers[0]="PLAINTEXT://$(RELEASE_EVENT_BUS):9092"
	helm upgrade --install $(RELEASE_DEF_DB) $(DEF_DB_CHART) -n $(NAMESPACE) --create-namespace --wait --timeout 10m0s --set persistence.enabled=false
	@if [[ -f "$(PORT_FORWARD_PID_FILE)" ]]; then \
		xargs -r kill < "$(PORT_FORWARD_PID_FILE)" 2>/dev/null || true; \
		rm -f "$(PORT_FORWARD_PID_FILE)"; \
	fi
	nohup kubectl -n $(NAMESPACE) port-forward svc/$(RELEASE_DEF_DB) 5432:5432 >/tmp/sie-dm-pf-db.log 2>&1 & echo $$! >> "$(PORT_FORWARD_PID_FILE)"
	nohup kubectl -n $(NAMESPACE) port-forward svc/$(RELEASE_EVENT_BUS) 9092:9092 >/tmp/sie-dm-pf-kafka.log 2>&1 & echo $$! >> "$(PORT_FORWARD_PID_FILE)"
	nohup kubectl -n $(NAMESPACE) port-forward svc/$(RELEASE_SCHEMA_REG) 8081:8081 >/tmp/sie-dm-pf-schema.log 2>&1 & echo $$! >> "$(PORT_FORWARD_PID_FILE)"
	@echo "Dependencies are ready and port-forwards started."
	@echo "Now run: make run-api"

local-down:
	@if [[ -f "$(PORT_FORWARD_PID_FILE)" ]]; then \
		xargs -r kill < "$(PORT_FORWARD_PID_FILE)" 2>/dev/null || true; \
		rm -f "$(PORT_FORWARD_PID_FILE)"; \
	fi
	helm uninstall $(RELEASE_SCHEMA_REG) -n $(NAMESPACE) || true
	helm uninstall $(RELEASE_DEF_DB) -n $(NAMESPACE) || true
	helm uninstall $(RELEASE_EVENT_BUS) -n $(NAMESPACE) || true

run-api:
	mvn spring-boot:run

test:
	mvn test

verify:
	mvn verify
