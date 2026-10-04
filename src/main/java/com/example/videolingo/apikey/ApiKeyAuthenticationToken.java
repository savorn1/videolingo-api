package com.example.videolingo.apikey;

import java.util.Collection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

// A request authenticated with an API key rather than a sign-in. Behaves
// exactly like the owner's session token; the distinct type lets the audit
// log say so, and lets key management refuse key-authenticated requests.
public class ApiKeyAuthenticationToken extends UsernamePasswordAuthenticationToken {

    private final Long keyId;

    public ApiKeyAuthenticationToken(String username, Long keyId, Collection<? extends GrantedAuthority> authorities) {
        super(username, null, authorities);
        this.keyId = keyId;
    }

    public Long getKeyId() {
        return keyId;
    }
}
