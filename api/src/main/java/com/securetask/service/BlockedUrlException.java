package com.securetask.service;

public class BlockedUrlException extends RuntimeException {

    public BlockedUrlException(String message) {
        super(message);
    }
}
