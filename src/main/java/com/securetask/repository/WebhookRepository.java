package com.securetask.repository;

import com.securetask.entity.User;
import com.securetask.entity.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findByUser(User user);

    Optional<Webhook> findByIdAndUser(Long id, User user);
}
