package org.example.gametopupkh.security;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a user for Spring Security. The username is the user id, the password is the
 * stored hash and the authorities are the roles prefixed with {@code ROLE_}.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UUID userId = parseUserId(username);
        User user = userRepository.findWithRolesById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User " + username + " was not found"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new DisabledException("User " + username + " is not active");
        }
        return new AuthenticatedUser(user, authoritiesOf(user));
    }

    private List<GrantedAuthority> authoritiesOf(User user) {
        return user.getRoleNames().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    private UUID parseUserId(String username) {
        try {
            return UUID.fromString(username);
        } catch (IllegalArgumentException e) {
            throw new UsernameNotFoundException("Username '" + username + "' is not a valid user id");
        }
    }
}
