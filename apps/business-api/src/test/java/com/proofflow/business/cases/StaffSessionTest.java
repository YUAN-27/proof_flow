package com.proofflow.business.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofflow.business.identity.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "proofflow.staff.enabled=true",
    "PROOFFLOW_CASES_TOKEN=" + CaseControllerTest.FIXTURE_TOKEN
})
class StaffSessionTest {
    private static final String STAFF = "/api/v1/staff";
    private static final String CASES = "/api/v1/cases";
    private static final String PASSWORD = "staff-fixture-password";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final ActorContext INTAKE = new ActorContext("tenant-a", "staff-intake");
    private static final ActorContext REVIEWER = new ActorContext("tenant-a", "staff-review");

    @MockitoBean(name = "staffUserDetailsService")
    private UserDetailsService users;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CaseService cases;

    @BeforeEach
    void registerAccounts() {
        when(users.loadUserByUsername("intake")).thenAnswer(invocation -> new StaffAccount("intake", HASH, true, INTAKE));
        when(users.loadUserByUsername("review")).thenAnswer(invocation -> new StaffAccount("review", HASH, true, REVIEWER));
        when(users.loadUserByUsername("disabled")).thenAnswer(invocation -> new StaffAccount("disabled", HASH, false, REVIEWER));
        when(users.loadUserByUsername("unknown")).thenThrow(new UsernameNotFoundException("Unknown staff account"));
    }

    @Test
    void attributesCreationUploadAndReviewToTheirSignedInActors() throws Exception {
        var intake = login("intake");
        var reviewer = login("review");
        mvc.perform(get(STAFF + "/me").session(intake.session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.actorId").value(INTAKE.actorId()));

        var created = mvc.perform(post(CASES).session(intake.session()).header(intake.header(), intake.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Serial number mismatch\"}"))
            .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        var pdf = new MockMultipartFile("file", "serial.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        mvc.perform(multipart(CASES + "/" + id + "/evidence").file(pdf).session(reviewer.session())
                .header(reviewer.header(), reviewer.token()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.uploadedBy").value(REVIEWER.actorId()));
        mvc.perform(patch(CASES + "/" + id + "/status").session(reviewer.session())
                .header(reviewer.header(), reviewer.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"status\":\"IN_REVIEW\"}"))
            .andExpect(status().isOk());

        assertThat(cases.auditTrail(INTAKE, UUID.fromString(id))).extracting(CaseService.AuditEntry::actorId)
            .containsExactly(INTAKE.actorId(), REVIEWER.actorId());
    }

    @Test
    void rejectsLegacyBearerInvalidCredentialsAndUnprotectedWrites() throws Exception {
        mvc.perform(get(CASES + "/" + UUID.randomUUID()).header("Authorization", "Bearer " + CaseControllerTest.FIXTURE_TOKEN))
            .andExpect(status().isUnauthorized());
        var anonymous = csrf(new MockHttpSession());
        mvc.perform(post(STAFF + "/login").session(anonymous.session()).param("username", "intake").param("password", PASSWORD))
            .andExpect(status().isForbidden());
        mvc.perform(post(STAFF + "/login").session(anonymous.session()).header(anonymous.header(), anonymous.token())
                .param("username", "intake").param("password", "wrong"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(STAFF + "/login").session(anonymous.session()).header(anonymous.header(), anonymous.token())
                .param("username", "disabled").param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(STAFF + "/login").session(anonymous.session()).header(anonymous.header(), anonymous.token())
                .param("username", "unknown").param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
        var staff = login("intake");
        mvc.perform(post(CASES).session(staff.session()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(STAFF + "/logout").session(staff.session()).header(staff.header(), staff.token()))
            .andExpect(status().isNoContent());
        assertThat(staff.session().isInvalid()).isTrue();
    }

    private CsrfSession login(String username) throws Exception {
        var anonymous = csrf(new MockHttpSession());
        var login = mvc.perform(post(STAFF + "/login").session(anonymous.session()).header(anonymous.header(), anonymous.token())
                .param("username", username).param("password", PASSWORD))
            .andExpect(status().isNoContent()).andReturn();
        return csrf((MockHttpSession) login.getRequest().getSession(false));
    }

    private CsrfSession csrf(MockHttpSession session) throws Exception {
        var response = mvc.perform(get(STAFF + "/csrf").session(session)).andExpect(status().isOk()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsString());
        return new CsrfSession(session, body.get("headerName").asText(), body.get("token").asText());
    }

    private record CsrfSession(MockHttpSession session, String header, String token) {}
}
