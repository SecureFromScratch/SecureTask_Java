package com.securetask.security;

import com.securetask.entity.User;
import com.securetask.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bridges our User entity to Spring Security's authentication mechanism.
 * Roles are stored without the ROLE_ prefix in the DB and added here.
 */
@Service
public class SecureTaskUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public SecureTaskUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Generic message intentionally — including the queried username would
        // leak account existence into logs even though DaoAuthenticationProvider
        // maps this to a generic BadCredentialsException before sending a response.
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
