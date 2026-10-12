package com.bigbazaar.identity.seller;

import java.util.Arrays;
import java.util.Map;

/**
 * Allowed upload types and their "magic bytes": the first bytes every real file of that type starts
 * with. Checking them catches a renamed .exe claiming to be a PDF (SRS 11, file uploads).
 */
final class DocumentFormats {

    static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    static final int SNIFF_BYTES = 8;

    private static final Map<String, Format> FORMATS = Map.of(
            "application/pdf", new Format(".pdf", new byte[] {'%', 'P', 'D', 'F', '-'}),
            "image/jpeg", new Format(".jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            "image/png", new Format(".png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}));

    static boolean isAllowed(String contentType) {
        return FORMATS.containsKey(contentType);
    }

    static String extension(String contentType) {
        return FORMATS.get(contentType).extension();
    }

    static boolean matches(String contentType, byte[] prefix) {
        Format format = FORMATS.get(contentType);
        return format != null && prefix.length >= format.magic().length
                && Arrays.equals(Arrays.copyOf(prefix, format.magic().length), format.magic());
    }

    private record Format(String extension, byte[] magic) {
    }

    private DocumentFormats() {
    }
}
