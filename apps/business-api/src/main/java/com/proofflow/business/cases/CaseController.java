package com.proofflow.business.cases;

import com.fasterxml.jackson.databind.JsonNode;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import com.proofflow.business.cases.CaseService.Status;
import com.proofflow.business.cases.CaseService.UpdateRejected;
import com.proofflow.business.identity.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(CaseController.PATH)
class CaseController {
    static final String PATH = "/api/v1/cases";
    private final CaseService cases;

    CaseController(CaseService cases) {
        this.cases = cases;
    }

    @PostMapping
    ResponseEntity<CaseResponse> create(
        @RequestAttribute(ActorContext.REQUEST_ATTRIBUTE) ActorContext actor, @RequestBody JsonNode body
    ) {
        JsonNode title = body.path("title");
        if (!title.isTextual() || title.textValue().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must be a nonblank string");
        }
        CaseSnapshot created = cases.create(actor, title.textValue());
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(CaseResponse.from(created));
    }

    @GetMapping("/{caseId}")
    CaseResponse find(
        @RequestAttribute(ActorContext.REQUEST_ATTRIBUTE) ActorContext actor, @PathVariable UUID caseId
    ) {
        return cases.find(actor, caseId).map(CaseResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PatchMapping("/{caseId}/status")
    CaseResponse updateStatus(
        @RequestAttribute(ActorContext.REQUEST_ATTRIBUTE) ActorContext actor,
        @PathVariable UUID caseId,
        @RequestBody JsonNode body
    ) {
        JsonNode version = body.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedVersion must be a positive integer");
        }
        JsonNode status = body.path("status");
        if (!status.isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be a string");
        }
        Status next;
        try {
            next = Status.valueOf(status.textValue());
        } catch (IllegalArgumentException invalidStatus) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown case status");
        }
        return CaseResponse.from(cases.updateStatus(actor, caseId, version.longValue(), next));
    }

    @ExceptionHandler(UpdateRejected.class)
    ProblemDetail rejectedUpdate(UpdateRejected rejected) {
        HttpStatus status = rejected.reason() == CaseService.UpdateFailure.NOT_FOUND
            ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", rejected.reason().name());
        return problem;
    }

    record CaseResponse(UUID id, String title, Status status, long version) {
        static CaseResponse from(CaseSnapshot snapshot) {
            return new CaseResponse(snapshot.id(), snapshot.title(), snapshot.status(), snapshot.version());
        }
    }
}
