package com.securetask.service;

import com.securetask.dto.RegisterRequest;
import com.securetask.dto.UserResponse;
import com.securetask.entity.Role;
import com.securetask.entity.User;
import com.securetask.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user.
     *
     * First-user bootstrap: if the users table is empty, the registering user
     * becomes ADMIN. All subsequent registrations receive VIEWER.
     *
     * SERIALIZABLE isolation guarantees that two concurrent transactions cannot
     * both observe an empty users table and both become ADMIN. PostgreSQL's SSI
     * will detect the conflict and roll back one of the transactions. The caller
     * (controller) catches the resulting CannotSerializeTransactionException and
     * retries or returns an error.
     *
     * The database unique constraints on username/email serve as the final safety
     * net for all other race conditions.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public UserResponse register(RegisterRequest request) {
        // Uniform error message regardless of whether username or email conflicts.
        // Distinct messages would let an unauthenticated caller enumerate which
        // usernames and emails are already registered.
        if (userRepository.existsByUsername(request.getUsername())
                || userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("An account with those details already exists");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());

        // Hash with Argon2; the encoded string includes the algorithm tag,
        // salt, and parameters so the hash is self-describing.
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        // Atomic role assignment: COUNT inside the same SERIALIZABLE transaction.
        // SSI prevents two concurrent transactions from both observing count=0.
        Role role = (userRepository.countUsers() == 0) ? Role.ADMIN : Role.VIEWER;
        user.setRole(role);

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    public UserResponse findByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return UserResponse.from(user);
    }
}
