package com.securetask.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service("localFileStorageService")
public class LocalFileStorageService implements FileStorageService {

    private final Path storageDir;

    public LocalFileStorageService(@Value("${upload.local.directory:uploads}") String directory) {
        this.storageDir = Paths.get(directory);
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storageDir);
    }

    @Override
    public void store(InputStream data, String storageKey, String contentType, long size) {
        try {
            Files.copy(data, storageDir.resolve(storageKey));
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + storageKey, e);
        }
    }

    @Override
    public InputStream load(String storageKey) {
        try {
            return new FileInputStream(storageDir.resolve(storageKey).toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(storageDir.resolve(storageKey));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file: " + storageKey, e);
        }
    }
}
