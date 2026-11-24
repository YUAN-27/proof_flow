package com.proofflow.business.cases;

import com.proofflow.business.identity.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Opt-in identity for local HTTP integration until a user authentication mechanism is available. */
@Configuration(proxyBeanMethods = false)
class CaseHttpAuthentication implements WebMvcConfigurer {
    static final String ACTOR_ATTRIBUTE = "caseActor";
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] token;
    private final ActorContext actor;

    CaseHttpAuthentication(
        @Value("${PROOFFLOW_CASES_TOKEN:}") String token,
        @Value("${PROOFFLOW_CASES_TENANT_ID:}") String tenantId,
        @Value("${PROOFFLOW_CASES_ACTOR_ID:}") String actorId
    ) {
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9._~+/-]{32,}={0,2}")) {
            throw new IllegalArgumentException("PROOFFLOW_CASES_TOKEN must contain at least 32 bearer-safe characters");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.actor = token.isEmpty() ? null : new ActorContext(tenantId, actorId);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (actor == null || !matchesBearerToken(authorization)) {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                request.setAttribute(ACTOR_ATTRIBUTE, actor);
                return true;
            }
        }).addPathPatterns(CaseController.PATH, CaseController.PATH + "/**");
    }

    private boolean matchesBearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        byte[] candidate = authorization.substring(BEARER_PREFIX.length()).stripLeading()
            .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, candidate);
    }
}
