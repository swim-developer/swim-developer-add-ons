# keycloak-swim-role-spi

Keycloak EventListener SPI that automatically creates per-user client roles in the `amq-broker` client whenever a SWIM user is registered. This is what makes AMQP access control scale without manual role management.

## The problem it solves

SWIM Consumers authenticate against AMQ Broker using Keycloak-issued tokens (OIDC). For a consumer to be authorized to consume from its subscription queue, the broker expects the user's token to carry a specific client role, one role per service type.

With dozens of ANSPs registering users, creating those roles by hand in Keycloak's admin console is not realistic. This SPI hooks into the user creation lifecycle and does it automatically.

## How it works

The SPI registers a `swim-role-creator` event listener. It handles two events:

- `EventType.REGISTER`, triggered when a user self-registers
- `AdminEvent CREATE USER`, triggered when an admin creates a user via the Admin Console or REST API

On either event, for each configured role suffix, the SPI:
1. Looks up the target client (default: `amq-broker`) in the realm.
2. Creates the role `{username}{suffix}` if it doesn't already exist.
3. Assigns that role to the user.

**Example**: user `john` → roles `john-swim-dnotam-v1-amq-role`, `john-swim-ed254-v1-amq-role`

## Configuration

Configured via environment variables on the Keycloak container:

| Variable | Default | Description |
|----------|---------|-------------|
| `SWIM_ROLE_TARGET_CLIENT` | `amq-broker` | The Keycloak client where roles are created |
| `SWIM_ROLE_SUFFIXES` | `-swim-dnotam-v1-amq-role,-swim-ed254-v1-amq-role` | Comma-separated list of role name suffixes |

The defaults cover both DNOTAM and ED-254 service types. To support additional services, add the corresponding suffix to `SWIM_ROLE_SUFFIXES`.

## Build

```bash
./mvnw clean package -DskipTests
```

Output: `target/keycloak-swim-role-spi-1.0.0-SNAPSHOT.jar`

Requires Java 21.

## Local install test

Build the JAR first, then start Keycloak in dev mode with the JAR mounted:

```bash
./mvnw clean package -DskipTests
podman compose up -d
```

Keycloak starts at http://localhost:9080 (admin / admin).

Activate the listener:
1. Log in → **Realm Settings** → **Events** → **Event Listeners**
2. Add `swim-role-creator` → Save

Create a test user via **Users** → **Add user**. Check the assigned client roles:
- **Users** → select the user → **Role mappings** → **amq-broker** client roles
- You should see `{username}-swim-dnotam-v1-amq-role` and `{username}-swim-ed254-v1-amq-role`

> The JAR must exist at `target/keycloak-swim-role-spi-1.0.0-SNAPSHOT.jar` before running compose.

---

## Deployment on OpenShift (RHBK Operator)

The `deploy-spi.sh` script automates the full deployment in one step:

```bash
./deploy-spi.sh [namespace]
# default namespace: rhbk
```

The script does the following:

1. **Builds the JAR** with Maven.
2. **Creates a Kubernetes Secret** (`keycloak-swim-role-spi`) with the JAR encoded as base64 data.
3. **Patches the Keycloak CR** (`rhbk`) to mount the JAR into `/opt/keycloak/providers/` and inject the environment variables.
4. **Restarts the Keycloak pod** and waits for it to become ready.

The patch YAML that gets applied to the Keycloak CR is at `src/main/openshift/keycloak-env-patch.yaml`.

### Manual activation (once deployed)

After the pod restarts, activate the event listener in the Admin Console:

1. **Realm Settings** → **Events** → **Event Listeners**
2. Add `swim-role-creator`
3. Save

### Verify

```bash
oc logs statefulset/rhbk -n rhbk | grep -i swim-role
```

You should see log entries when users are created and roles are assigned.

## Notes

- The SPI is idempotent: if the role already exists or the user already has it, it skips gracefully.
- The `geolocation-spi` volume referenced in `keycloak-env-patch.yaml` is unrelated, it is an existing SPI that was already configured and is preserved by the patch.
