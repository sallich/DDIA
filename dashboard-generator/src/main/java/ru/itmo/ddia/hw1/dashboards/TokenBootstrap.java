package ru.itmo.ddia.hw1.dashboards;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A one-shot step: creates a service account in Grafana and issues a token for it, which
 * the dashboard generator then uses.
 *
 * Runs in compose once Grafana has passed its health probe. The admin password is read from
 * a docker secret and the issued token is written to a shared volume, so neither of them
 * ever appears in docker-compose.yaml.
 *
 * Idempotent: on a restart it reuses a token that is still valid in the volume, and reuses
 * the service account when one already exists.
 */
public final class TokenBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TokenBootstrap.class);

    private static final String SERVICE_ACCOUNT_NAME = "dashboard-generator";

    public static void main(String[] args) throws Exception {
        String grafanaUrl = Env.required("GRAFANA_URL");
        String adminUser = Env.orDefault("GRAFANA_ADMIN_USER", "admin");
        String adminPassword = Env.secretFromFile("GRAFANA_ADMIN_PASSWORD_FILE");
        Path tokenFile = Path.of(Env.required("GRAFANA_SA_TOKEN_FILE"));

        if (existingTokenWorks(grafanaUrl, tokenFile)) {
            log.info("Token in {} is still valid, not issuing a new one", tokenFile);
            return;
        }

        GrafanaClient admin = GrafanaClient.withBasicAuth(grafanaUrl, adminUser, adminPassword);
        int serviceAccountId = findOrCreateServiceAccount(admin);
        String token = createToken(admin, serviceAccountId);

        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, token,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        log.info("Service account id={}, token issued and written to {}", serviceAccountId, tokenFile);
    }

    private static boolean existingTokenWorks(String grafanaUrl, Path tokenFile) {
        try {
            if (!Files.exists(tokenFile)) {
                return false;
            }
            String token = Files.readString(tokenFile).trim();
            if (token.isEmpty()) {
                return false;
            }
            return GrafanaClient.withToken(grafanaUrl, token).get("/api/org").ok();
        } catch (Exception e) {
            return false;
        }
    }

    private static int findOrCreateServiceAccount(GrafanaClient admin) throws Exception {
        JsonNode found = admin
                .get("/api/serviceaccounts/search?query=" + SERVICE_ACCOUNT_NAME)
                .requireOk("service account lookup")
                .json();
        for (JsonNode account : found.path("serviceAccounts")) {
            if (SERVICE_ACCOUNT_NAME.equals(account.path("name").asText())) {
                return account.path("id").asInt();
            }
        }

        // Editor is enough to create dashboards; the service account needs no admin rights.
        String body = """
                {"name": "%s", "role": "Editor", "isDisabled": false}
                """.formatted(SERVICE_ACCOUNT_NAME);
        return admin.post("/api/serviceaccounts", body)
                .requireOk("service account creation")
                .json()
                .path("id")
                .asInt();
    }

    private static String createToken(GrafanaClient admin, int serviceAccountId) throws Exception {
        String body = """
                {"name": "generator-%d"}
                """.formatted(System.currentTimeMillis());
        return admin.post("/api/serviceaccounts/" + serviceAccountId + "/tokens", body)
                .requireOk("token issuance")
                .json()
                .path("key")
                .asText();
    }
}