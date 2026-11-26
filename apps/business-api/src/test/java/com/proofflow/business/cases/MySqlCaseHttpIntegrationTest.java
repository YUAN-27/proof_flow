package com.proofflow.business.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofflow.business.BusinessApplication;
import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PROOF_FLOW_TEST_MYSQL_URL", matches = "jdbc:mysql:.+")
class MySqlCaseHttpIntegrationTest {
    @Test
    void readsTheCaseAndAuditAfterRestartingTheApplication() throws Exception {
        String tenant = "http-restart-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        var json = new ObjectMapper();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            UUID id;
            try (var app = startApplication(tenant, token)) {
                var created = send(client, app, token, "POST", "", """
                    {"title":"MySQL restart regression"}
                    """);
                assertThat(created.statusCode()).isEqualTo(201);
                id = UUID.fromString(json.readTree(created.body()).path("id").asText());

                var updated = send(client, app, token, "PATCH", "/" + id + "/status", """
                    {"expectedVersion":1,"status":"IN_REVIEW"}
                    """);
                assertThat(updated.statusCode()).isEqualTo(200);
                assertThat(json.readTree(updated.body()).path("version").asLong()).isEqualTo(2);
            }

            try (var restarted = startApplication(tenant, token)) {
                var found = send(client, restarted, token, "GET", "/" + id, "");
                assertThat(found.statusCode()).isEqualTo(200);
                var snapshot = json.readTree(found.body());
                assertThat(snapshot.path("id").asText()).isEqualTo(id.toString());
                assertThat(snapshot.path("status").asText()).isEqualTo("IN_REVIEW");
                assertThat(snapshot.path("version").asLong()).isEqualTo(2);

                var history = restarted.getBean(CaseRepository.class).auditTrail(tenant, id);
                assertThat(history).extracting(AuditEntry::version).containsExactly(1L, 2L);
                assertThat(history).extracting(AuditEntry::operation)
                    .containsExactly(Operation.CREATED, Operation.STATUS_UPDATED);
            }
        }
    }

    private static ServletWebServerApplicationContext startApplication(String tenant, String token) {
        var settings = new MapPropertySource("mysql-restart-test", Map.of(
            "server.address", "127.0.0.1",
            "server.port", "0",
            "proofflow.cases.repository", "mysql",
            "proofflow.cases.mysql.url", System.getenv("PROOF_FLOW_TEST_MYSQL_URL"),
            "proofflow.cases.mysql.user", System.getenv().getOrDefault("PROOF_FLOW_TEST_MYSQL_USER", ""),
            "proofflow.cases.mysql.password", System.getenv().getOrDefault("PROOF_FLOW_TEST_MYSQL_PASSWORD", ""),
            "PROOFFLOW_CASES_TOKEN", token,
            "PROOFFLOW_CASES_TENANT_ID", tenant,
            "PROOFFLOW_CASES_ACTOR_ID", "restart-test"
        ));
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BusinessApplication.class)
            .initializers(context -> context.getEnvironment().getPropertySources().addFirst(settings))
            .registerShutdownHook(false)
            .run();
    }

    private static HttpResponse<String> send(HttpClient client, ServletWebServerApplicationContext app,
                                             String token, String method, String path, String body) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + CaseController.PATH + path);
        var request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
