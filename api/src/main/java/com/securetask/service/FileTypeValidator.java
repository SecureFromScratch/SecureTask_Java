package com.securetask.service;

import org.springframework.stereotype.Component;

/**
 * Validates uploaded file content by inspecting magic bytes.
 *
 * Why magic bytes instead of the Content-Type header or file extension?
 * - The Content-Type header is supplied by the client and can be set to anything.
 * - A file extension can be renamed (e.g., malicious.jsp → harmless.jpg).
 * - Magic bytes are the first few bytes of the actual file content and cannot be
 *   faked without also faking the content — a renamed JSP file will not have JPEG
 *   magic bytes, so it will be rejected here even if the client claims it is a JPEG.
 */
@Component
public class FileTypeValidator {

    public String validate(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new InvalidFileTypeException("File must not be empty");
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A
                && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }

        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        // GIF: 47 49 46 38 (GIF8)
        if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38) {
            return "image/gif";
        }

        // WebP: RIFF at bytes 0-3, WEBP at bytes 8-11
        if (bytes.length >= 12
                && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) {
            return "image/webp";
        }

        // PDF: %PDF → 25 50 44 46
        if (bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46) {
            return "application/pdf";
        }

        throw new InvalidFileTypeException("File type not allowed. Accepted: PNG, JPEG, GIF, WebP, PDF");
    }
}
