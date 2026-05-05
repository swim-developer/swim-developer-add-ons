.DEFAULT_GOAL := help

ARTEMIS_PLUGIN_DIR := activemq-log-plugins
KEYCLOAK_SPI_DIR   := keycloak-swim-role-spi

.PHONY: help build test sonar security-deps

help:
	@echo "Available targets:"
	@echo "  build         Build both modules (skips tests)"
	@echo "  test          Run unit tests for both modules"
	@echo "  sonar         Run SonarQube analysis (requires SONAR_HOST_URL and SONAR_TOKEN)"
	@echo "  security-deps OWASP Dependency-Check on both modules"

build:
	cd $(ARTEMIS_PLUGIN_DIR) && ./mvnw clean package -DskipTests
	cd $(KEYCLOAK_SPI_DIR)   && ./mvnw clean package -DskipTests

test:
	cd $(ARTEMIS_PLUGIN_DIR) && ./mvnw test
	cd $(KEYCLOAK_SPI_DIR)   && ./mvnw test

sonar:
	cd $(ARTEMIS_PLUGIN_DIR) && ./mvnw verify sonar:sonar \
	  -Dsonar.host.url=$(SONAR_HOST_URL) \
	  -Dsonar.token=$(SONAR_TOKEN)
	cd $(KEYCLOAK_SPI_DIR) && ./mvnw verify sonar:sonar \
	  -Dsonar.host.url=$(SONAR_HOST_URL) \
	  -Dsonar.token=$(SONAR_TOKEN)

security-deps:
	cd $(ARTEMIS_PLUGIN_DIR) && ./mvnw org.owasp:dependency-check-maven:check -DsuppressionFile=../owasp-suppressions.xml
	cd $(KEYCLOAK_SPI_DIR)   && ./mvnw org.owasp:dependency-check-maven:check -DsuppressionFile=../owasp-suppressions.xml
