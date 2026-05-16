package com.securetask.repository;

import com.securetask.entity.Attachment;
import com.securetask.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByTask(Task task);

    // Returns empty for both "not found" and "belongs to another task",
    // so a caller cannot distinguish the two cases — same 404 pattern as tasks/webhooks.
    Optional<Attachment> findByIdAndTask(Long id, Task task);
}
