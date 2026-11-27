package com.proofflow.business.cases;

import com.proofflow.business.identity.ActorContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(StaffSecurityConfiguration.PATH)
@ConditionalOnProperty(name = "proofflow.staff.enabled", havingValue = "true")
class StaffSessionController {
    @GetMapping("/csrf")
    ResponseEntity<CsrfToken> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(token);
    }

    @GetMapping("/me")
    ResponseEntity<ActorContext> me(@AuthenticationPrincipal StaffAccount account) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(account.actor());
    }
}
