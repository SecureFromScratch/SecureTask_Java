package com.securetask.repository;

import com.securetask.entity.Task;
import com.securetask.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByOwner(User owner);

    List<Task> findByOwnerOrderByPinnedDescCreatedAtDesc(User owner);

    Optional<Task> findByIdAndOwner(Long id, User owner);
}
