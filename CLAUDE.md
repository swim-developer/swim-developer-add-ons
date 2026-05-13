# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Platform-level extensions for the SWIM (System Wide Information Management) infrastructure. These are broker and identity add-ons that SWIM services depend on — not SWIM services themselves.

Two independent Maven modules, each with its own Maven wrapper (`./mvnw`):

| Module | Java | What it does |
|--------|------|-------------|
| `activemq-log-plugins` | 17 | Artemis broker plugin: intercepts message ACKs on SWIM subscription queues, publishes delivery audit records to `ACK_MONITOR` topic |
| `keycloak-swim-role-spi` | 21 | Keycloak EventListener SPI: auto-creates per-user Artemis client roles on user registration |

## Build and Test Commands

```bash
# Build both modules (skips test execution, compiles tests)
make build

# Run tests for both modules
make test

# Build a single module
cd activemq-log-plugins && ./mvnw clean package -DskipTests
cd keycloak-swim-role-spi && ./mvnw clean package -DskipTests

# Run tests for a single module
cd activemq-log-plugins && ./mvnw test
cd keycloak-swim-role-spi && ./mvnw test

# OWASP dependency check
make security-deps
```

**Build rule**: always use `./mvnw clean package -DskipTests`. Never use `-Dmaven.test.skip=true` (it skips test compilation, hiding compile errors).

## Local Development with Containers

Each module has a `compose.yml` for local testing. Build the JAR first, then start:

```bash
# ActiveMQ plugin — Artemis broker at localhost:8161 (admin/admin), AMQP at 5672
cd activemq-log-plugins && ./mvnw clean package -DskipTests && podman compose up -d

# Keycloak SPI — Keycloak at localhost:9080 (admin/admin)
cd keycloak-swim-role-spi && ./mvnw clean package -DskipTests && podman compose up -d
```

Use `podman` (not `docker`) for container commands.

## Architecture

### activemq-log-plugins

Single class: `ACKMonitorPlugin` extends `LoggingActiveMQServerPlugin`. On `messageAcknowledged`, filters queues matching the SWIM subscription pattern (`PREFIX-userId-UUID`), then async-publishes a structured `ACKMessage` JSON to the `ACK_MONITOR` multicast topic (7-day expiry). Uses a fixed thread pool (`2 × CPUs`) for async dispatch. Deployed on OpenShift via a custom init container image that copies the JAR into the broker's `lib/`.

### keycloak-swim-role-spi

Two classes implementing the Keycloak EventListener SPI pattern:
- `SwimRoleEventListenerProviderFactory` — factory registered via `META-INF/services`, provider ID is `swim-role-creator`
- `SwimRoleEventListenerProvider` — handles `REGISTER` and admin `CREATE USER` events; creates `{username}{suffix}` client roles in the `amq-broker` client

Configured via env vars `SWIM_ROLE_TARGET_CLIENT` and `SWIM_ROLE_SUFFIXES`. Deployed on OpenShift via `deploy-spi.sh` which builds, creates a K8s Secret with the JAR, patches the Keycloak CR, and restarts the pod.

## Code Standards

- The `ACKMessage` record inside `ACKMonitorPlugin` is a known exception to the "no inner classes" rule, predating its adoption.
- All OpenShift/K8s resources must be physical YAML files applied via `oc apply -f`
- Code and documentation in English
