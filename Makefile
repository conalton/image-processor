DOCKER ?= docker
export HOST_UID := $(shell id -u)
export HOST_GID := $(shell id -g)

COMPOSE = $(DOCKER) compose --env-file versions.env
RUN = $(COMPOSE) run --rm -T

# Upper bounds in seconds: a first run downloads the tool distribution and, on LocalStack, the Lambda runtime image.
TF_TIMEOUT = 1200
GRADLE_TIMEOUT = 900
NPM_TIMEOUT = 600
CLI_TIMEOUT = 300
SMOKE_TIMEOUT = 900

TF = $(RUN) --entrypoint timeout terraform $(TF_TIMEOUT) terraform
TF_AWS = $(RUN) --entrypoint timeout terraform-aws $(TF_TIMEOUT) terraform
TF_AWS_QUIET = $(COMPOSE) --progress quiet run --rm -T --entrypoint timeout terraform-aws $(TF_TIMEOUT) terraform
# An IDE on the host sees the project under another path; its own cache keeps Gradle's task history apart.
GRADLE = $(RUN) --entrypoint timeout jdk $(GRADLE_TIMEOUT) ./gradlew --project-cache-dir .gradle-container
CURL = $(RUN) --entrypoint curl aws-cli
# Inside a container *.localhost does not reach LocalStack: curl and the smoke test connect to this service instead.
LOCALSTACK_SERVICE = localstack
LOCALSTACK_CURL = --connect-to ::$(LOCALSTACK_SERVICE):4566
# Terraform needs a value for the required variable even to destroy; the architecture does not matter there.
DESTROY_ARCH = x86_64
# The contract's 404 TASK_NOT_FOUND shows the API works: no curl -f. The loop repeats any other answer (a fresh
# Function URL on AWS may answer 403 at first), at most about 35 minutes.
READINESS_CURL = -sS --max-time 60
READINESS_ATTEMPTS = 30
READINESS_DELAY = 10
READINESS_TASK_ID = 00000000-0000-4000-8000-000000000000

# $(1) Terraform service (its workspace is set in compose.yaml), $(2) variable file
define apply
	$(1) init -input=false
	$(1) apply -auto-approve -var-file=$(2) -var lambda_architecture=$(ARCH)
endef

define destroy
	$(1) init -input=false
	$(1) destroy -auto-approve -var-file=$(2) -var lambda_architecture=$(DESTROY_ARCH)
endef

# $(1) Terraform service, $(2) extra curl options
define readiness
	@start=$$(date +%s); \
	url=$$($(1) output -raw function_url) || exit 1; \
	echo "readiness check: $$url"; \
	for attempt in $$(seq 1 $(READINESS_ATTEMPTS)); do \
		$(CURL) $(2) $(READINESS_CURL) "$${url%/}/api/tasks/$(READINESS_TASK_ID)" | grep TASK_NOT_FOUND > /dev/null && break; \
		if [ "$$attempt" = $(READINESS_ATTEMPTS) ]; then \
			echo "readiness check failed: $$url did not answer with TASK_NOT_FOUND in $(READINESS_ATTEMPTS) attempts"; exit 1; \
		fi; \
		sleep $(READINESS_DELAY); \
	done; \
	echo "ready after $$(( $$(date +%s) - start )) s"
endef

# $(1) Terraform service, $(2) host to send every request to (LocalStack only), $(3) 1 to run the checks only AWS can answer
define smoke
	@url=$$($(1) output -raw function_url) || exit 1; test -n "$$url" || { echo "no function_url output: deploy the stack first"; exit 1; }; \
	page=$$($(1) output -raw page_url) || exit 1; test -n "$$page" || { echo "no page_url output: deploy the stack first"; exit 1; }; \
	$(RUN) -w /workspace/smoke --entrypoint timeout -e FUNCTION_URL="$$url" -e PAGE_URL="$$page" -e CONNECT_HOST="$(2)" -e AWS_ONLY_CHECKS=$(3) node $(SMOKE_TIMEOUT) sh -c 'npm ci --omit=dev --no-audit --no-fund && node smoke.ts'
endef

# Every target is phony: build and frontend are also directory names, and make would skip them as up to date.
.PHONY: help frontend frontend-lock smoke-lock frontend-test backend-test build test lint format localstack-up localstack-down localstack-reset \
	tf-lock tf-check tf-fmt require-arch require-aws-tfvars require-aws-dir \
	deploy-localstack deploy-aws destroy-localstack destroy-aws state-aws \
	smoke-localstack smoke-aws output-localstack output-aws cli-localstack cli-aws

.DEFAULT_GOAL := help

# A bare make fails, so that nothing expecting a build passes without one.
help:
	@echo "make <target>: the targets are listed in README.md, section Make targets"; test -n "$(MAKECMDGOALS)"

frontend:
	$(RUN) -w /workspace/frontend --entrypoint timeout node $(NPM_TIMEOUT) sh -c 'npm ci && npm run build'

frontend-lock:
	$(RUN) -w /workspace/frontend --entrypoint timeout node $(NPM_TIMEOUT) npm install --package-lock-only

smoke-lock:
	$(RUN) -w /workspace/smoke --entrypoint timeout node $(NPM_TIMEOUT) npm install --package-lock-only

frontend-test: frontend
	$(RUN) -w /workspace/frontend --entrypoint timeout node $(NPM_TIMEOUT) npm test

build: frontend
	$(GRADLE) assemble

test: frontend-test backend-test

backend-test:
	$(COMPOSE) up -d --wait --wait-timeout 60 dynamodb || { $(COMPOSE) logs --tail 50 dynamodb; echo "DynamoDB Local did not become healthy within 60 s: see the log above"; exit 1; }
	$(GRADLE) test

lint:
	$(GRADLE) spotlessCheck
	$(RUN) -w /workspace/frontend --entrypoint timeout node $(NPM_TIMEOUT) sh -c 'npm ci && npm run lint'
	$(RUN) -w /workspace/smoke --entrypoint timeout node $(NPM_TIMEOUT) sh -c 'npm ci && npm run lint'

format:
	$(GRADLE) spotlessApply
	$(RUN) -w /workspace/frontend --entrypoint timeout node $(NPM_TIMEOUT) sh -c 'npm ci && npm run lint -- --fix'

localstack-up:
	$(COMPOSE) up -d --wait --wait-timeout 180 localstack || { $(COMPOSE) logs --tail 50 localstack; echo "LocalStack did not become healthy within 180 s: see the log above"; exit 1; }

localstack-down:
	$(COMPOSE) down

# Free LocalStack keeps no state across restarts: its Terraform state is stale after one.
localstack-reset:
	rm -rf infra/terraform.tfstate.d/localstack

tf-lock:
	$(TF) init -backend=false
	$(TF) providers lock -platform=linux_amd64 -platform=linux_arm64

tf-check:
	$(TF) fmt -check -recursive
	$(TF) init -backend=false
	$(TF) validate

tf-fmt:
	$(TF) fmt -recursive

require-arch:
	@case "$(ARCH)" in x86_64|arm64) ;; *) echo "ARCH is required and must be x86_64 or arm64, not '$(ARCH)': make $(MAKECMDGOALS) ARCH=x86_64 (or arm64)"; exit 1;; esac

require-aws-tfvars:
	@test -f infra/aws.tfvars || { echo "infra/aws.tfvars is missing: copy infra/aws.tfvars.example and fill in the account id"; exit 1; }

# Without it, Docker stops with "bind source path does not exist" (compose.yaml: create_host_path: false).
require-aws-dir:
	@test -d "$(HOME)/.aws" || { echo "$(HOME)/.aws is missing: the *-aws targets read the AWS credentials (profile default) from it; on an EC2 instance with a role an empty directory is enough (mkdir ~/.aws), see README.md, Run it on AWS"; exit 1; }

# Only LocalStack takes the host's architecture: on AWS a default would silently switch a live stack.
deploy-localstack: ARCH ?= $(patsubst aarch64,arm64,$(shell uname -m))
deploy-localstack: require-arch build localstack-up
	$(call apply,$(TF),localstack.tfvars)
	$(call readiness,$(TF),$(LOCALSTACK_CURL))

deploy-aws: require-aws-dir require-arch require-aws-tfvars build
	$(call apply,$(TF_AWS),aws.tfvars)
	$(call readiness,$(TF_AWS),)

destroy-localstack:
	$(call destroy,$(TF),localstack.tfvars)

destroy-aws: require-aws-dir require-aws-tfvars
	$(call destroy,$(TF_AWS),aws.tfvars)

# Addresses only.
state-aws: require-aws-dir
	@$(TF_AWS_QUIET) init -input=false > /dev/null
	@if [ ! -f infra/terraform.tfstate.d/aws/terraform.tfstate ]; then \
		echo "No state file for the aws workspace in this checkout: nothing was deployed from here."; \
		echo "The state is local: a stack deployed from another checkout is not listed."; \
	else \
		resources=$$($(TF_AWS_QUIET) state list) || exit 1; \
		if [ -n "$$resources" ]; then echo "$$resources"; else echo "The aws state is empty: no resource is left."; fi; \
	fi

smoke-localstack:
	$(call smoke,$(TF),$(LOCALSTACK_SERVICE),0)

smoke-aws: require-aws-dir
	$(call smoke,$(TF_AWS),,1)

output-localstack:
	$(TF) output

output-aws: require-aws-dir
	$(TF_AWS) output

# make cli-localstack ARGS='s3 ls'
cli-localstack:
	$(RUN) --entrypoint timeout aws-cli $(CLI_TIMEOUT) aws $(ARGS)

cli-aws: require-aws-dir
	@region=$$($(TF_AWS_QUIET) output -raw aws_region) || exit 1; test -n "$$region" || { echo "no aws_region output: run make deploy-aws first"; exit 1; }; \
	$(RUN) --entrypoint timeout -e AWS_REGION="$$region" aws-cli-aws $(CLI_TIMEOUT) aws $(ARGS)
