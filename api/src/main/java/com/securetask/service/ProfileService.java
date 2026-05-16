package com.securetask.service;

import com.securetask.dto.ProfileResponse;
import com.securetask.entity.User;
import com.securetask.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileService {

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final FileTypeValidator fileTypeValidator;
    private final String storageBackend;

    public ProfileService(UserRepository userRepository,
                          @Qualifier("s3FileStorageService") FileStorageService fileStorageService,
                          FileTypeValidator fileTypeValidator) {
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.fileTypeValidator = fileTypeValidator;
        this.storageBackend = "s3";
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String username) {
        return ProfileResponse.from(resolveUser(username), storageBackend);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ProfileResponse uploadAvatar(MultipartFile file, String username) {
        User user = resolveUser(username);

        if (file.isEmpty()) {
            throw new InvalidFileTypeException("File must not be empty");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        String detectedType = fileTypeValidator.validate(bytes);
        if (!ALLOWED_IMAGE_TYPES.contains(detectedType)) {
            throw new InvalidFileTypeException("Avatar must be an image. Accepted: PNG, JPEG, GIF, WebP");
        }

        // Replace existing avatar: delete the old file before storing the new one.
        if (user.getAvatarKey() != null) {
            fileStorageService.delete(user.getAvatarKey());
        }

        String storageKey = UUID.randomUUID().toString();
        fileStorageService.store(new ByteArrayInputStream(bytes), storageKey, detectedType, bytes.length);

        user.setAvatarKey(storageKey);
        user.setAvatarContentType(detectedType);
        return ProfileResponse.from(userRepository.save(user), storageBackend);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public AvatarDownload getAvatar(String username) {
        User user = resolveUser(username);
        if (user.getAvatarKey() == null) {
            throw new ResourceNotFoundException("No avatar uploaded");
        }
        InputStream stream = fileStorageService.load(user.getAvatarKey());
        String contentType = user.getAvatarContentType() != null
                ? user.getAvatarContentType() : "application/octet-stream";
        return new AvatarDownload(stream, contentType);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ProfileResponse deleteAvatar(String username) {
        User user = resolveUser(username);
        if (user.getAvatarKey() != null) {
            fileStorageService.delete(user.getAvatarKey());
            user.setAvatarKey(null);
            user.setAvatarContentType(null);
            userRepository.save(user);
        }
        return ProfileResponse.from(user, storageBackend);
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public record AvatarDownload(InputStream inputStream, String contentType) {}
}
