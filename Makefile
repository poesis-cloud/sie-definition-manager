SHELL := /bin/bash
.EXPORT_ALL_VARIABLES:

.PHONY: dev-check dev-up dev-down deploy-check prod-deploy package-helm run-api test verify ensure-runtime helm-template-matrix

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

ensure-runtime:
	@if [[ -n "$$XDG_RUNTIME_DIR" ]] && [[ ! -d "$$XDG_RUNTIME_DIR" ]]; then \
		echo "Creating missing XDG_RUNTIME_DIR ($$XDG_RUNTIME_DIR)..."; \
		sudo mkdir -p "$$XDG_RUNTIME_DIR" && sudo chown $$(id -u):$$(id -g) "$$XDG_RUNTIME_DIR" && chmod 700 "$$XDG_RUNTIME_DIR"; \
	fi

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
	@$(MAKE) ensure-runtime
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

# ---------------------------------------------------------------------------
# helm-template-matrix — S-006 / ADR-003 D-2 (AC-1) Helm sink switch validator.
#
# Renders the chart with observability.logs.sink ∈ {otlp, stdout, both} and
# asserts that:
#   - render exits 0
#   - OBSERVABILITY_LOGS_SINK env carries the expected value
#   - LOGGING_THRESHOLD_CONSOLE env follows the sink→threshold mapping
#       sink=otlp   → LOGGING_THRESHOLD_CONSOLE=OFF
#       sink=stdout → LOGGING_THRESHOLD_CONSOLE=TRACE
#       sink=both   → LOGGING_THRESHOLD_CONSOLE=TRACE
# Then runs the negative case (sink=bogus) and asserts:
#   - render exits non-zero
#   - stderr contains the enum-guard message from _helpers.tpl
#
# HELM_TEMPLATE_DUMMY_ARGS supplies sentinel values for umbrella + subchart
# secrets so `helm template` can render in CI without any real credentials.
# These sentinels are NEVER applied to a cluster — only consumed by `helm
# template`. The string `ci-template-validation-not-a-real-secret` makes that
# intent explicit on the off chance a sentinel leaks into a log line.
# ---------------------------------------------------------------------------
HELM_TEMPLATE_DUMMY_ARGS := \
	--set deployApp=true \
	--set secrets.DB_PASSWORD=ci-template-validation-not-a-real-secret \
	--set dbBootstrap.adminPassword=ci-template-validation-not-a-real-secret \
	--set dbBootstrap.dbPassword=ci-template-validation-not-a-real-secret \
	--set definitiondatabase.secrets.DB_PASSWORD=ci-template-validation-not-a-real-secret \
	--set definitiondatabase.postgres.dbPassword=ci-template-validation-not-a-real-secret

helm-template-matrix:
	@command -v helm >/dev/null 2>&1 || { echo "Missing required command: helm"; exit 1; }
	@test -d "$(DM_CHART)" || { echo "Missing chart directory: $(DM_CHART)"; exit 1; }
	@echo "==> helm lint (dev / preprod / prod)"
	helm lint $(DM_CHART) -f $(DM_CHART)/environments/dev/values.yaml     $(HELM_TEMPLATE_DUMMY_ARGS)
	helm lint $(DM_CHART) -f $(DM_CHART)/environments/preprod/values.yaml $(HELM_TEMPLATE_DUMMY_ARGS)
	helm lint $(DM_CHART) -f $(DM_CHART)/environments/prod/values.yaml    $(HELM_TEMPLATE_DUMMY_ARGS)
	@echo
	@echo "==> Positive matrix: helm template with sink ∈ {otlp, stdout, both}"
	@for sink in otlp stdout both; do \
		echo "    -- sink=$$sink"; \
		out=$$(helm template t $(DM_CHART) -f $(DM_CHART)/environments/dev/values.yaml \
			$(HELM_TEMPLATE_DUMMY_ARGS) \
			--set observability.logs.sink=$$sink) || { echo "FAIL: render exited non-zero for sink=$$sink"; exit 1; }; \
		expected_threshold=$$([ "$$sink" = "otlp" ] && echo OFF || echo TRACE); \
		echo "$$out" | grep -q "name: OBSERVABILITY_LOGS_SINK" \
			|| { echo "FAIL: OBSERVABILITY_LOGS_SINK env missing for sink=$$sink"; exit 1; }; \
		echo "$$out" | grep -A1 "name: OBSERVABILITY_LOGS_SINK" | grep -q "value: \"$$sink\"" \
			|| { echo "FAIL: OBSERVABILITY_LOGS_SINK value != $$sink"; exit 1; }; \
		echo "$$out" | grep -A1 "name: LOGGING_THRESHOLD_CONSOLE" | grep -q "value: \"$$expected_threshold\"" \
			|| { echo "FAIL: LOGGING_THRESHOLD_CONSOLE value != $$expected_threshold for sink=$$sink"; exit 1; }; \
		echo "       PASS  OBSERVABILITY_LOGS_SINK=$$sink  LOGGING_THRESHOLD_CONSOLE=$$expected_threshold"; \
	done
	@echo
	@echo "==> Negative case: sink=bogus must FAIL with enum-guard message"
	@err=$$(helm template t $(DM_CHART) -f $(DM_CHART)/environments/dev/values.yaml \
		$(HELM_TEMPLATE_DUMMY_ARGS) \
		--set observability.logs.sink=bogus 2>&1); rc=$$?; \
		if [ "$$rc" = "0" ]; then \
			echo "FAIL: render unexpectedly succeeded for sink=bogus"; exit 1; \
		fi; \
		echo "$$err" | grep -q "observability.logs.sink must be one of: otlp, stdout, both" \
			|| { echo "FAIL: enum-guard message not found in stderr. Got:"; echo "$$err"; exit 1; }; \
		echo "       PASS  render exited rc=$$rc and stderr carries enum-guard message"
	@echo
	@echo "helm-template-matrix: all matrix cells + negative case PASS"

test:
	mvn test

verify:
	mvn verify
