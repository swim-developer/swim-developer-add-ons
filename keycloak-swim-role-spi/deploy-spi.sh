#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${1:-rhbk}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SECRET_KEY="keycloak-swim-role-spi.jar"
SECRET_NAME="keycloak-swim-role-spi"
KEYCLOAK_CR="rhbk"

echo "=== Step 1/5: Build JAR ==="
cd "${PROJECT_DIR}"
mvn clean package -DskipTests -q
JAR_PATH=$(ls "${PROJECT_DIR}"/target/keycloak-swim-role-spi-*.jar 2>/dev/null | head -1)
if [[ -z "${JAR_PATH}" ]]; then
  echo "ERROR: JAR not found in target/" >&2
  exit 1
fi
echo "JAR built: ${JAR_PATH}"

echo "=== Step 2/5: Generate Secret from JAR ==="
JAR_BASE64=$(base64 < "${JAR_PATH}")

oc apply -n "${NAMESPACE}" -f - <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ${SECRET_NAME}
type: Opaque
data:
  ${SECRET_KEY}: ${JAR_BASE64}
EOF
echo "Secret '${SECRET_NAME}' updated (key: ${SECRET_KEY})"

echo "=== Step 3/4: Patch Keycloak CR (env vars + volume mounts) ==="
oc patch keycloak "${KEYCLOAK_CR}" -n "${NAMESPACE}" --type=merge \
  -p "$(cat "${PROJECT_DIR}/src/main/openshift/keycloak-env-patch.yaml")"
echo "Keycloak CR patched"

echo "=== Step 4/4: Restart Keycloak pod ==="
oc delete pod "${KEYCLOAK_CR}-0" -n "${NAMESPACE}"
oc wait pod/"${KEYCLOAK_CR}-0" -n "${NAMESPACE}" --for=condition=Ready --timeout=120s
echo "Keycloak pod ready"

echo ""
echo "Done. Verify SPI is loaded:"
echo "  oc logs statefulset/${KEYCLOAK_CR} -n ${NAMESPACE} | grep -i swim-role"
