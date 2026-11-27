package com.proofflow.business.evidence;

import com.proofflow.business.cases.CaseService;
import com.proofflow.business.evidence.EvidenceRepository.Metadata;
import com.proofflow.business.identity.ActorContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(CaseEvidenceController.PATH)
@ConditionalOnProperty(name = "proofflow.cases.repository", havingValue = "memory", matchIfMissing = true)
class CaseEvidenceController {
    static final String PATH = "/api/v1/cases/{caseId}/evidence";
    static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = HexFormat.of().parseHex("89504e470d0a1a0a");
    private static final byte[] JPEG = HexFormat.of().parseHex("ffd8ff");

    private final CaseService cases;
    private final EvidenceRepository evidence;

    CaseEvidenceController(CaseService cases, EvidenceRepository evidence) {
        this.cases = cases;
        this.evidence = evidence;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Metadata> upload(@RequestAttribute(ActorContext.REQUEST_ATTRIBUTE) ActorContext actor,
                                    @PathVariable UUID caseId, @RequestParam("file") MultipartFile file) throws IOException {
        requireCase(actor, caseId);
        var filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/")
            || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evidence file is empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Evidence file exceeds 10 MiB");
        }

        byte[] bytes = file.getBytes();
        var metadata = new Metadata(UUID.randomUUID(), caseId, filename, detectContentType(bytes),
            bytes.length, Instant.now(), actor.actorId());
        if (!evidence.insert(actor.tenantId(), metadata, bytes)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local evidence storage is full");
        }
        return ResponseEntity.created(URI.create(PATH.replace("{caseId}", caseId.toString()) + "/" + metadata.id()))
            .body(metadata);
    }

    @GetMapping
    List<Metadata> list(@RequestAttribute(ActorContext.REQUEST_ATTRIBUTE) ActorContext actor, @PathVariable UUID caseId) {
        requireCase(actor, caseId);
        return evidence.list(actor.tenantId(), caseId);
    }

    @GetMapping("/{evidenceId}")
    ResponseEntity<byte[]> download(@RequestAttribute(ActorContext.REQUEST_ATTRIBUTE) ActorContext actor,
                                    @PathVariable UUID caseId, @PathVariable UUID evidenceId) {
        requireCase(actor, caseId);
        var attachment = evidence.find(actor.tenantId(), caseId, evidenceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var disposition = ContentDisposition.attachment()
            .filename(attachment.metadata().filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(attachment.content());
    }

    private void requireCase(ActorContext actor, UUID caseId) {
        cases.find(actor, caseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String detectContentType(byte[] content) {
        if (startsWith(content, PDF)) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        if (startsWith(content, PNG)) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (startsWith(content, JPEG)) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF, PNG and JPEG are supported");
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return content.length >= signature.length
            && Arrays.equals(content, 0, signature.length, signature, 0, signature.length);
    }
}
