package org.example.gametopupkh.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.identity.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal with the user's current token version. The JWT filter compares
 * the version in the signed token with this value so a password reset can invalidate
 * access tokens that are still within their normal 15-minute lifetime.
 */
public final class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String password;
    private final List<GrantedAuthority> authorities;
    private final long tokenVersion;

    public AuthenticatedUser(User user, List<GrantedAuthority> authorities) {
        this.id = user.getId();
        this.password = user.getPasswordHash();
        this.authorities = List.copyOf(authorities);
        this.tokenVersion = user.getTokenVersion();
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return id.toString();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
