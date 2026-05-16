package com.securetask.service;

import java.io.InputStream;

public record AttachmentDownload(
        String originalFilename,
        String contentType,
        long fileSize,
        InputStream inputStream
) {}
