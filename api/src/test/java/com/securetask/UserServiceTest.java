package com.securetask;

import com.securetask.dto.RegisterRequest;
import com.securetask.dto.UserResponse;
import com.securetask.entity.Role;
import com.securetask.repository.UserRepository;
import com.securetask.service.ConflictException;
import com.securetask.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecretsManagerConfig.class)
class UserServiceTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    // ── Lab 01 requirement: first user becomes ADMIN ──────────────────────────

    @Test
    void firstUserBecomesAdmin() {
        UserResponse user = userService.register(req("alice", "alice@example.com", "password123"));
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    }

    // ── Lab 01 requirement: second user becomes VIEWER ────────────────────────

    @Test
    void secondUserBecomesViewer() {
        userService.register(req("alice", "alice@example.com", "password123"));
        UserResponse bob = userService.register(req("bob", "bob@example.com", "password456"));
        assertThat(bob.getRole()).isEqualTo(Role.VIEWER);
    }

    // ── Lab 01 requirement: concurrent first-user registration ────────────────
    // SERIALIZABLE isolation means only one transaction can observe an empty table
    // and commit. The others either:
    //   (a) succeed with VIEWER (they ran after the first committed), or
    //   (b) fail with a serialization error (rolled back by PostgreSQL SSI).
    // Either way, the database should contain exactly one ADMIN when all settle.

    @Test
    void concurrentFirstRegistrationCreatesAtMostOneAdmin() throws Exception {
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<UserResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return userService.register(
                        req("user" + idx, "user" + idx + "@example.com", "password123")
                    );
                } catch (Exception e) {
                    // Serialization failures or conflicts are expected and safe.
                    return null;
                }
            }));
        }

        ready.await();
        go.countDown();

        List<UserResponse> results = new ArrayList<>();
        for (Future<UserResponse> f : futures) {
            UserResponse r = f.get();
            if (r != null) {
                results.add(r);
            }
        }
        pool.shutdown();

        // Count ADMINs among successful registrations.
        long adminCount = results.stream()
                .filter(u -> u.getRole() == Role.ADMIN)
                .count();

        // Also verify the database directly — ground truth.
        long dbAdminCount = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN)
                .count();

        assertThat(adminCount).as("successful registrations produced at most 1 ADMIN").isLessThanOrEqualTo(1);
        assertThat(dbAdminCount).as("database contains exactly 1 ADMIN after concurrent registrations").isEqualTo(1);
    }

    // ── Lab 01 requirement: passwords stored as Argon2 hashes ────────────────

    @Test
    void passwordStoredAsArgon2Hash() {
        userService.register(req("alice", "alice@example.com", "supersecret"));
        String hash = userRepository.findByUsername("alice")
                .orElseThrow().getPasswordHash();
        // Argon2PasswordEncoder produces the native PHC string format: $argon2id$...
        assertThat(hash).startsWith("$argon2");
        // The raw password must NOT appear in the hash string.
        assertThat(hash).doesNotContain("supersecret");
    }

    // ── Lab 01 requirement: password hash not exposed in UserResponse ─────────

    @Test
    void passwordHashNotExposedInUserResponse() throws NoSuchMethodException {
        // UserResponse must not expose a getPasswordHash() method.
        Class<?> clazz = UserResponse.class;
        boolean hasGetter = java.util.Arrays.stream(clazz.getMethods())
                .anyMatch(m -> m.getName().equals("getPasswordHash") || m.getName().equals("passwordHash"));
        assertThat(hasGetter)
                .as("UserResponse must not have a getPasswordHash() method")
                .isFalse();
    }

    // ── Duplicate username rejected ───────────────────────────────────────────

    @Test
    void duplicateUsernameIsRejected() {
        userService.register(req("alice", "alice@example.com", "password123"));
        assertThatThrownBy(() ->
            userService.register(req("alice", "other@example.com", "password456"))
        ).isInstanceOf(ConflictException.class)
         .hasMessageContaining("already exists");
    }

    // ── Duplicate email rejected ──────────────────────────────────────────────

    @Test
    void duplicateEmailIsRejected() {
        userService.register(req("alice", "alice@example.com", "password123"));
        assertThatThrownBy(() ->
            userService.register(req("alice2", "alice@example.com", "password456"))
        ).isInstanceOf(ConflictException.class)
         .hasMessageContaining("already exists");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static RegisterRequest req(String username, String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }
}
