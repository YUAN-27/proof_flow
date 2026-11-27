package com.proofflow.business.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofflow.business.cases.CaseService;
import com.proofflow.business.identity.ActorContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "PROOFFLOW_CASES_TOKEN=" + CaseEvidenceControllerTest.FIXTURE_TOKEN,
    "PROOFFLOW_CASES_TENANT_ID=tenant-a",
    "PROOFFLOW_CASES_ACTOR_ID=reviewer"
})
@AutoConfigureMockMvc
class CaseEvidenceControllerTest {
    static final String FIXTURE_TOKEN = "test-evidence-fixture-not-a-production-credential";
    private static final String PATH = "/api/v1/cases/{caseId}/evidence";
    private static final String AUTHORIZATION = "Bearer " + FIXTURE_TOKEN;
    private static final byte[] PDF = "%PDF-1.7\nfixture".getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CaseService cases;

    @Test
    void uploadsListsAndDownloadsTheOriginalBytes() throws Exception {
        UUID caseId = createCase("tenant-a");
        mvc.perform(get(PATH, caseId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isOk()).andExpect(content().json("[]"));

        var result = mvc.perform(multipart(PATH, caseId).file(file("凭证.pdf", PDF))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.filename").value("凭证.pdf"))
            .andExpect(jsonPath("$.contentType").value("application/pdf"))
            .andExpect(jsonPath("$.uploadedBy").value("reviewer"))
            .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
            .andReturn();
        String id = json.readTree(result.getResponse().getContentAsByteArray()).path("id").asText();
        mvc.perform(get(PATH, caseId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment;")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
            .andExpect(content().bytes(PDF));
        mvc.perform(get(PATH + "/{id}", createCase("tenant-a"), id).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthenticationAndDoesNotExposeAnotherTenantsCase() throws Exception {
        UUID otherCase = createCase("tenant-b");
        mvc.perform(get(PATH, otherCase)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH, otherCase).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .header("X-Tenant-Id", "tenant-b"))
            .andExpect(status().isNotFound());
        mvc.perform(multipart(PATH, otherCase).file(file("receipt.pdf", PDF))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/{id}", otherCase, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidFilesWithoutPublishingAttachmentMetadata() throws Exception {
        UUID caseId = createCase("tenant-a");
        mvc.perform(multipart(PATH, caseId).file(file("../receipt.pdf", PDF))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH, caseId).file(file("empty.pdf", new byte[0]))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH, caseId).file(file("fake.pdf", new byte[] {1, 2, 3}))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart(PATH, caseId).file(file("large.pdf", new byte[CaseEvidenceController.MAX_FILE_BYTES + 1]))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(PATH, caseId).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    private UUID createCase(String tenant) {
        return cases.create(new ActorContext(tenant, "creator"), "Missing receipt").id();
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, MediaType.APPLICATION_PDF_VALUE, bytes);
    }
}
