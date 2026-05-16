package com.securetask;

import com.securetask.service.FileTypeValidator;
import com.securetask.service.InvalidFileTypeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FileTypeValidatorTest {

    private final FileTypeValidator validator = new FileTypeValidator();

    @Test
    void pngMagicBytesAccepted() {
        byte[] png = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        assertThat(validator.validate(png)).isEqualTo("image/png");
    }

    @Test
    void jpegMagicBytesAccepted() {
        byte[] jpeg = {(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0x00, 0x10};
        assertThat(validator.validate(jpeg)).isEqualTo("image/jpeg");
    }

    @Test
    void gifMagicBytesAccepted() {
        byte[] gif = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x00, 0x00};
        assertThat(validator.validate(gif)).isEqualTo("image/gif");
    }

    @Test
    void webpMagicBytesAccepted() {
        byte[] webp = new byte[12];
        // RIFF at 0-3
        webp[0] = 0x52; webp[1] = 0x49; webp[2] = 0x46; webp[3] = 0x46;
        // WEBP at 8-11
        webp[8] = 0x57; webp[9] = 0x45; webp[10] = 0x42; webp[11] = 0x50;
        assertThat(validator.validate(webp)).isEqualTo("image/webp");
    }

    @Test
    void pdfMagicBytesAccepted() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E};
        assertThat(validator.validate(pdf)).isEqualTo("application/pdf");
    }

    @Test
    void javaClassFileRejected() {
        // Java .class files start with CA FE BA BE
        byte[] classFile = {(byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE, 0x00};
        assertThatThrownBy(() -> validator.validate(classFile))
                .isInstanceOf(InvalidFileTypeException.class);
    }

    @Test
    void htmlContentRejected() {
        // <htm in ASCII
        byte[] html = {0x3C, 0x68, 0x74, 0x6D, 0x6C, 0x3E};
        assertThatThrownBy(() -> validator.validate(html))
                .isInstanceOf(InvalidFileTypeException.class);
    }

    @Test
    void renamedJspAsJpgRejected() {
        // Plain text content with a .jpg filename claim — magic bytes are wrong
        byte[] textContent = "<%@ page %>hello".getBytes();
        assertThatThrownBy(() -> validator.validate(textContent))
                .isInstanceOf(InvalidFileTypeException.class);
    }

    @Test
    void emptyFileRejected() {
        assertThatThrownBy(() -> validator.validate(new byte[0]))
                .isInstanceOf(InvalidFileTypeException.class);
    }

    @Test
    void nullRejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidFileTypeException.class);
    }
}
