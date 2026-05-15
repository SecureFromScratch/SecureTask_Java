package com.securetask.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Service("s3FileStorageService")
public class S3FileStorageService implements FileStorageService {

    private final S3Client s3Client;
    private final String bucket;

    public S3FileStorageService(S3Client s3Client,
                                @Value("${upload.s3.bucket:securetask-uploads}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public void store(InputStream data, String storageKey, String contentType, long size) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket).key(storageKey)
                        .contentType(contentType).contentLength(size)
                        .build(),
                RequestBody.fromInputStream(data, size));
    }

    @Override
    public InputStream load(String storageKey) {
        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket).key(storageKey)
                        .build());
    }

    @Override
    public void delete(String storageKey) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket).key(storageKey)
                        .build());
    }
}
