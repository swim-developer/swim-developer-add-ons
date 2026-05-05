#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

IMAGE_REGISTRY="${IMAGE_REGISTRY:-quay.io}"
IMAGE_NAMESPACE="${IMAGE_NAMESPACE:-swim-developer}"
IMAGE_NAME="${IMAGE_NAME:-amq-broker-init-swim}"
IMAGE_TAG="${IMAGE_TAG:-7.13.2}"

FULL_IMAGE="${IMAGE_REGISTRY}/${IMAGE_NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"

echo "Building JAR..."
cd "${PROJECT_DIR}"
mvn clean package -DskipTests

echo "Building container image: ${FULL_IMAGE}"
cd "${SCRIPT_DIR}"
cp "${PROJECT_DIR}/target/activemq-log-plugin.jar" .

podman build -t "${FULL_IMAGE}" -f Containerfile .

rm -f activemq-log-plugin.jar

echo "Image built: ${FULL_IMAGE}"
echo ""
echo "To push: podman push ${FULL_IMAGE}"
echo ""
echo "To use in ActiveMQArtemis CR:"
echo "  spec:"
echo "    deploymentPlan:"
echo "      initImage: ${FULL_IMAGE}"

