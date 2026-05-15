package com.securetask.service;

import java.io.InputStream;

public interface FileStorageService {

    void store(InputStream data, String storageKey, String contentType, long size);

    InputStream load(String storageKey);

    void delete(String storageKey);
}
