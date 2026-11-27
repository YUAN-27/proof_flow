package com.proofflow.business.cases;

import com.proofflow.business.identity.ActorContext;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

final class StaffAccount extends User {
    private final ActorContext actor;

    StaffAccount(String username, String passwordHash, boolean enabled, ActorContext actor) {
        super(username, passwordHash, enabled, true, true, true, AuthorityUtils.createAuthorityList("ROLE_STAFF"));
        this.actor = actor;
    }

    ActorContext actor() {
        return actor;
    }
}
