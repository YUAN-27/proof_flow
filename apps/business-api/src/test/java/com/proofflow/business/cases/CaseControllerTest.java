package com.proofflow.business.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofflow.business.identity.ActorContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "PROOFFLOW_CASES_TOKEN=" + CaseControllerTest.FIXTURE_TOKEN,
    "PROOFFLOW_CASES_TENANT_ID=tenant-a",
    "PROOFFLOW_CASES_ACTOR_ID=reviewer"
})
@AutoConfigureMockMvc
class CaseControllerTest {
    static final String FIXTURE_TOKEN = "test-fixture-not-a-production-credential";
    private static final String CASES = "/api/v1/cases";
    private static final String AUTHORIZATION = "Bearer " + FIXTURE_TOKEN;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CaseService service;

    @Test
    void createsReadsAndUpdatesACaseThroughHttp() throws Exception {
        String id = createCase();
        mvc.perform(get(CASES + "/{caseId}", id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Missing serial number"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patch(CASES + "/{caseId}/status", id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedVersion":1,"status":"IN_REVIEW"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_REVIEW"))
            .andExpect(jsonPath("$.version").value(2));

        mvc.perform(patch(CASES + "/{caseId}/status", id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedVersion":1,"status":"CLOSED"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void listsCasesAndReturnsAuditOnlyForTheAuthenticatedTenant() throws Exception {
        String id = createCase();
        var other = service.create(new ActorContext("tenant-b", "other"), "Other tenant");
        mvc.perform(get(CASES).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .param("q", id).param("status", "OPEN").param("limit", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get(CASES + "/{caseId}/audit", id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].actorId").value("reviewer"))
            .andExpect(jsonPath("$[0].operation").value("CREATED"));
        mvc.perform(get(CASES + "/{caseId}/audit", other.id()).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isNotFound());
        mvc.perform(get(CASES).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).param("limit", "0"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRequestsWithoutValidCredentialsBeforeParsingTheBody() throws Exception {
        mvc.perform(post(CASES).contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        mvc.perform(get(CASES + "/{caseId}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-fixture"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotReadOrUpdateAnotherTenantsCaseEvenWithSpoofedHeaders() throws Exception {
        var other = new ActorContext("tenant-b", "other");
        var created = service.create(other, "Another tenant's case");
        mvc.perform(get(CASES + "/{caseId}", created.id())
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).header("X-Tenant-Id", "tenant-b"))
            .andExpect(status().isNotFound());

        mvc.perform(patch(CASES + "/{caseId}/status", created.id())
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).header("X-Tenant-Id", "tenant-b")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedVersion":1,"status":"IN_REVIEW","tenantId":"tenant-b","actorId":"other"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(service.find(other, created.id())).contains(created);
        assertThat(service.auditTrail(other, created.id())).hasSize(1);
    }

    @Test
    void distinguishesInvalidInputFromAConflictingStateTransition() throws Exception {
        mvc.perform(post(CASES).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"title":42}
                    """))
            .andExpect(status().isBadRequest());

        String id = createCase();
        mvc.perform(patch(CASES + "/{caseId}/status", id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedVersion":1.5,"status":"IN_REVIEW"}
                    """))
            .andExpect(status().isBadRequest());

        mvc.perform(patch(CASES + "/{caseId}/status", id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedVersion":1,"status":"CLOSED"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    private String createCase() throws Exception {
        var response = mvc.perform(post(CASES).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"title":"Missing serial number"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.version").value(1))
            .andReturn().getResponse();
        String id = json.readTree(response.getContentAsByteArray()).get("id").asText();
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo(CASES + "/" + id);
        return id;
    }
}
