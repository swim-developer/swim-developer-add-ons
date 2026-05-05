package com.github.swim_developer.keycloak;

import lombok.extern.slf4j.Slf4j;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.Arrays;
import java.util.List;

@Slf4j
public class SwimRoleEventListenerProvider implements EventListenerProvider {

    private static final String ENV_TARGET_CLIENT = "SWIM_ROLE_TARGET_CLIENT";
    private static final String ENV_ROLE_SUFFIXES = "SWIM_ROLE_SUFFIXES";
    private static final String DEFAULT_TARGET_CLIENT = "amq-broker";
    private static final String DEFAULT_ROLE_SUFFIXES = "-swim-dnotam-v1-amq-role,-swim-ed254-v1-amq-role";

    private final KeycloakSession session;
    private final String targetClientId;
    private final List<String> roleSuffixes;

    public SwimRoleEventListenerProvider(KeycloakSession session) {
        this.session = session;
        this.targetClientId = getEnvOrDefault(ENV_TARGET_CLIENT, DEFAULT_TARGET_CLIENT);
        this.roleSuffixes = parseSuffixes(getEnvOrDefault(ENV_ROLE_SUFFIXES, DEFAULT_ROLE_SUFFIXES));
    }

    private List<String> parseSuffixes(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String getEnvOrDefault(String envName, String defaultValue) {
        String value = System.getenv(envName);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.REGISTER) {
            String userId = event.getUserId();
            String realmId = event.getRealmId();
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm != null) {
                UserModel user = session.users().getUserById(realm, userId);
                if (user != null) {
                    processUserCreation(realm, user);
                }
            }
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getOperationType() == OperationType.CREATE
                && event.getResourceType() == ResourceType.USER) {
            String realmId = event.getRealmId();
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm != null) {
                String userId = extractUserIdFromResourcePath(event.getResourcePath());
                if (userId != null) {
                    UserModel user = session.users().getUserById(realm, userId);
                    if (user != null) {
                        processUserCreation(realm, user);
                    }
                }
            }
        }
    }

    private void processUserCreation(RealmModel realm, UserModel user) {
        ClientModel client = getTargetClient(realm);
        if (client == null) {
            return;
        }
        for (String suffix : roleSuffixes) {
            createAndAssignRole(client, user, suffix);
        }
    }

    private ClientModel getTargetClient(RealmModel realm) {
        ClientModel client = realm.getClientByClientId(targetClientId);
        if (client == null) {
            log.warn("Client '{}' not found in realm '{}'", targetClientId, realm.getName());
        }
        return client;
    }

    private void createAndAssignRole(ClientModel client, UserModel user, String suffix) {
        String roleName = user.getUsername() + suffix;
        RoleModel role = getOrCreateRole(client, roleName);
        assignRoleToUser(user, role);
    }

    private RoleModel getOrCreateRole(ClientModel client, String roleName) {
        RoleModel role = client.getRole(roleName);
        if (role != null) {
            log.debug("Role '{}' already exists in client '{}'", roleName, client.getClientId());
            return role;
        }

        role = client.addRole(roleName);
        role.setDescription("Auto-generated SWIM queue role: " + roleName);
        log.info("Created role '{}' in client '{}'", roleName, client.getClientId());
        return role;
    }

    private void assignRoleToUser(UserModel user, RoleModel role) {
        if (user.hasRole(role)) {
            log.debug("User '{}' already has role '{}'", user.getUsername(), role.getName());
            return;
        }
        user.grantRole(role);
        log.info("Assigned role '{}' to user '{}'", role.getName(), user.getUsername());
    }

    private String extractUserIdFromResourcePath(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith("users/")) {
            return null;
        }
        String[] parts = resourcePath.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    @Override
    public void close() {
    }
}
