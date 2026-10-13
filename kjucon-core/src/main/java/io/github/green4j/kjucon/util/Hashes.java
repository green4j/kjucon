/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable hashes of text, for names and annotations which must change exactly when what they are derived from does.
 */
public final class Hashes {
    private static final String ALGORITHM = "SHA-256";
    private static final int HEX_RADIX = 16;
    private static final int NIBBLE_BITS = 4;
    private static final int NIBBLE_MASK = 0xF;
    /** The length of the longest hash: 64 hex digits of SHA-256. */
    public static final int MAX_LENGTH = 64;

    private Hashes() {
    }

    /**
     * @param value to hash
     * @param length of the hash, in lowercase hex digits, up to {@link #MAX_LENGTH}
     * @return the first digits of the SHA-256 of the value's UTF-8 bytes
     */
    public static String sha256(final String value,
                                final int length) {
        if (length < 1 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length must be within 1 and " + MAX_LENGTH + ": " + length);
        }
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
        final StringBuilder hex = new StringBuilder(length + 1);
        for (int i = 0; hex.length() < length; i++) {
            hex.append(Character.forDigit((digest[i] >> NIBBLE_BITS) & NIBBLE_MASK, HEX_RADIX));
            hex.append(Character.forDigit(digest[i] & NIBBLE_MASK, HEX_RADIX));
        }
        return hex.substring(0, length);
    }
}
