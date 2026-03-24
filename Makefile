SHELL := /bin/bash
.EXPORT_ALL_VARIABLES:

.PHONY: dev-check dev-up dev-down deploy-check prod-deploy package-helm run-api test verify

-include .env
-include .env.dev

NAMESPACE ?= sie
DEPLOY_ENV ?= preprod
RELEASE_EVENT_BUS ?= sie-definition-manager-eventbus
RELEASE_DEF_DB ?= definitiondatabase
RELEASE_SCHEMA_REG ?= schemaregistry

DM_CHART ?= ./ops/helm
DM_ENV_FILE ?= $(DM_CHART)/environments/$(DEPLOY_ENV)/values.yaml

PORT_FORWARD_PID_FILE ?= .dev-port-forwards.pids

define kill_port_listener
	@ss -ltnp '( sport = :$(1) )' 2>/dev/null | awk -F'pid=' '/kubectl/ {split($$2, parts, /[,)]/); print parts[1]}' | xargs -r kill
endef

dev-check:
	@command -v kubectl >/dev/null 2>&1 || { echo "Missing required command: kubectl"; exit 1; }
	@command -v helm >/dev/null 2>&1 || { echo "Missing required command: helm"; exit 1; }
	@kubectl config current-context >/dev/null 2>&1 || { echo "No active Kubernetes context. Configure kubeconfig first."; exit 1; }
	@kubectl get ns >/dev/null 2>&1 || { echo "Cannot reach Kubernetes API with current context."; exit 1; }
	@test -d "$(DM_CHART)" || { echo "Missing chart directory: $(DM_CHART)"; exit 1; }
	@test -f "$(DM_CHART)/environments/dev/values.yaml" || { echo "Missing dev values file: $(DM_CHART)/environments/dev/values.yaml"; exit 1; }
	@echo "dev-check passed"

deploy-check:
	@command -v kubectl >/dev/null 2>&1 || { echo "Missing required command: kubectl"; exit 1; }
	@command -v helm >/dev/null 2>&1 || { echo "Missing required command: helm"; exit 1; }
	@kubectl config current-context >/dev/null 2>&1 || { echo "No active Kubernetes context. Configure kubeconfig first."; exit 1; }
	@kubectl get ns >/dev/null 2>&1 || { echo "Cannot reach Kubernetes API with current context."; exit 1; }
	@test -d "$(DM_CHART)" || { echo "Missing chart directory: $(DM_CHART)"; exit 1; }
	@test -f "$(DM_ENV_FILE)" || { echo "Missing environment values file: $(DM_ENV_FILE)"; exit 1; }
	@: "$${DM_DB_PASSWORD:?Missing DM_DB_PASSWORD in environment}"
	@: "$${DEF_DB_ADMIN_PASSWORD:?Missing DEF_DB_ADMIN_PASSWORD in environment}"
	@: "$${IMAGE_REPOSITORY:?Missing IMAGE_REPOSITORY in environment}"
	@: "$${IMAGE_TAG:?Missing IMAGE_TAG in environment}"
	@echo "deploy-check passed"

dev-up:
	@$(MAKE) dev-check
	@: "$${DB_USER:?Missing DB_USER in .env.dev}"
	@: "$${DB_PASSWORD:?Missing DB_PASSWORD in .env.dev}"
	@: "$${DEF_DB_ADMIN_PASSWORD:?Missing DEF_DB_ADMIN_PASSWORD in .env.dev}"
	kubectl get ns $(NAMESPACE) >/dev/null 2>&1 || kubectl create ns $(NAMESPACE) >/dev/null
	helm upgrade --install sie-definition-manager $(DM_CHART) -n $(NAMESPACE) --create-namespace --wait --timeout 5m0s \
		-f $(DM_CHART)/environments/dev/values.yaml \
		--set-string definitiondatabase.secrets.DB_PASSWORD="$${DEF_DB_ADMIN_PASSWORD}" \
		--set-string dbBootstrap.adminPassword="$${DEF_DB_ADMIN_PASSWORD}" \
		--set-string dbBootstrap.dbUser="$${DB_USER}" \
		--set-string dbBootstrap.dbPassword="$${DB_PASSWORD}"
	@if [[ -f "$(PORT_FORWARD_PID_FILE)" ]]; then \
		xargs -r kill < "$(PORT_FORWARD_PID_FILE)" 2>/dev/null || true; \
		rm -f "$(PORT_FORWARD_PID_FILE)"; \
	fi
	$(call kill_port_listener,5432)
	$(call kill_port_listener,9092)
	$(call kill_port_listener,8081)
	nohup kubectl -n $(NAMESPACE) port-forward svc/$(RELEASE_DEF_DB) 5432:5432 >/tmp/sie-dm-pf-db.log 2>&1 & echo $$! >> "$(PORT_FORWARD_PID_FILE)"
	nohup kubectl -n $(NAMESPACE) port-forward svc/$(RELEASE_EVENT_BUS) 9092:9092 >/tmp/sie-dm-pf-kafka.log 2>&1 & echo $$! >> "$(PORT_FORWARD_PID_FILE)"
	nohup kubectl -n $(NAMESPACE) port-forward svc/$(RELEASE_SCHEMA_REG) 8081:8081 >/tmp/sie-dm-pf-schema.log 2>&1 & echo $$! >> "$(PORT_FORWARD_PID_FILE)"
	@sleep 2
	@grep -q "Forwarding from 127.0.0.1:5432" /tmp/sie-dm-pf-db.log || { echo "Database port-forward failed. See /tmp/sie-dm-pf-db.log"; exit 1; }
	@grep -q "Forwarding from 127.0.0.1:9092" /tmp/sie-dm-pf-kafka.log || { echo "Kafka port-forward failed. See /tmp/sie-dm-pf-kafka.log"; exit 1; }
	@grep -q "Forwarding from 127.0.0.1:8081" /tmp/sie-dm-pf-schema.log || { echo "Schema Registry port-forward failed. See /tmp/sie-dm-pf-schema.log"; exit 1; }
	@echo "Dependencies are ready and port-forwards started."
	@echo "Now run: make run-api"

dev-down:
	@PID=$$(lsof -ti :8080 2>/dev/null); \
	if [[ -n "$$PID" ]]; then \
		kill $$PID 2>/dev/null || true; \
		echo "Stopped process on port 8080 (PID $$PID)"; \
	fi
	@if [[ -f "$(PORT_FORWARD_PID_FILE)" ]]; then \
		xargs -r kill < "$(PORT_FORWARD_PID_FILE)" 2>/dev/null || true; \
		rm -f "$(PORT_FORWARD_PID_FILE)"; \
	fi
	helm uninstall sie-definition-manager -n $(NAMESPACE) || true
	kubectl -n $(NAMESPACE) delete secret/$(RELEASE_EVENT_BUS)-kraft --ignore-not-found=true
	kubectl -n $(NAMESPACE) delete pvc/data-$(RELEASE_EVENT_BUS)-0 --ignore-not-found=true
	kubectl -n $(NAMESPACE) delete pvc/data-$(RELEASE_DEF_DB)-0 --ignore-not-found=true

run-api:
	DB_URL="$(DB_URL)" \
	DB_USER="$(DB_USER)" \
	DB_PASSWORD="$(DB_PASSWORD)" \
	mvn spring-boot:run

prod-deploy:
	@$(MAKE) deploy-check
	helm upgrade --install sie-definition-manager $(DM_CHART) -n $(NAMESPACE) --create-namespace --wait --timeout 10m0s \
		-f $(DM_ENV_FILE) \
		--set image.repository="$${IMAGE_REPOSITORY}" \
		--set image.tag="$${IMAGE_TAG}" \
		--set-string secrets.DB_PASSWORD="$${DM_DB_PASSWORD}" \
		--set-string definitiondatabase.secrets.DB_PASSWORD="$${DEF_DB_ADMIN_PASSWORD}" \
		--set-string dbBootstrap.adminPassword="$${DEF_DB_ADMIN_PASSWORD}" \
		--set-string dbBootstrap.dbPassword="$${DM_DB_PASSWORD}"

package-helm:
	@command -v helm >/dev/null 2>&1 || { echo "Missing required command: helm"; exit 1; }
	@test -d "$(DM_CHART)" || { echo "Missing chart directory: $(DM_CHART)"; exit 1; }
	helm package $(DM_CHART)

test:
	mvn test

verify:
	mvn verify
