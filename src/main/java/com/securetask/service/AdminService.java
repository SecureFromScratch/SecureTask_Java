package com.securetask.service;

import com.securetask.dto.UserResponse;
import com.securetask.entity.Role;
import com.securetask.entity.User;
import com.securetask.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminService {

    private final UserRepository userRepository;

    public AdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns all registered users as safe DTOs.
     *
     * @PreAuthorize on the service (not only the controller) means the check is
     * enforced regardless of who calls this method — another controller, a
     * scheduled job, or a test. It cannot be bypassed by adding a new entry point.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<UserResponse> listAllUsers() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    /**
     * Changes the role of the target user.
     *
     * Guards in order:
     * 1. @PreAuthorize — only ADMINs may call this at all.
     * 2. Self-change guard — an admin cannot change their own role, preventing
     *    accidental self-lockout and stopping a compromised account from
     *    re-granting itself privileges after a downgrade.
     * 3. Peer-demotion guard — an admin cannot demote another ADMIN to a lower
     *    role. Without this, one compromised admin account could systematically
     *    demote all other admins and gain exclusive control of the system.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse changeUserRole(Long targetId, Role newRole, String callerUsername) {
        User caller = userRepository.findByUsername(callerUsername)
                .orElseThrow(() -> new ResourceNotFoundException("Caller not found"));

        if (caller.getId().equals(targetId)) {
            throw new ConflictException("Admins cannot change their own role");
        }

        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Prevent one admin from demoting another admin. A peer-demotion attack
        // would let a single compromised account strip all other admins of their
        // privileges and gain exclusive control.
        if (target.getRole() == Role.ADMIN) {
            throw new ConflictException("Cannot change the role of another admin");
        }

        target.setRole(newRole);
        return UserResponse.from(userRepository.save(target));
    }
}
