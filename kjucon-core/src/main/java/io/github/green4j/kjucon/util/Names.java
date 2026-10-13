/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

/**
 * Deterministic Kubernetes object names. A child named from its parent and some free-form text (a stream, an
 * application, an index) must get the same valid name on every reconcile, and two different texts must not
 * collapse into one name once sanitized or truncated - hence the stable hash suffix.
 */
public final class Names {
    /** The longest DNS-1123 label: Service names, label values, most CR names that become hostnames. */
    public static final int DNS1123_LABEL_MAX = 63;
    /** The longest DNS-1123 subdomain: the limit for most other object names. */
    public static final int DNS1123_SUBDOMAIN_MAX = 253;

    private static final int HASH_LENGTH = 8;
    private static final char SEPARATOR = '-';
    // what precedes a hash which is all that is left of a name: a hash may begin with a digit, which some names -
    // those of Services, DNS-1035 labels - must not
    private static final char HASH_PREFIX = 'x';
    // one character of the name, the separator and the hash
    private static final int MIN_LENGTH = 1 + 1 + HASH_LENGTH;

    private Names() {
    }

    /**
     * Joins the parts with '-', sanitizes the result to a DNS-1123 label and, if anything was changed by the
     * sanitizing or the result does not fit {@code maxLength}, makes it unique again with a stable hash of
     * the original parts.
     *
     * @param maxLength of the result, at least 10 - room for a character, the separator and a hash
     * @param parts to join; nulls are skipped
     * @return a valid, deterministic name
     */
    public static String child(final int maxLength,
                               final String... parts) {
        if (maxLength < MIN_LENGTH) {
            throw new IllegalArgumentException("maxLength is too small: " + maxLength);
        }
        final StringBuilder raw = new StringBuilder();
        for (final String part : parts) {
            if (part == null) {
                continue;
            }
            if (raw.length() > 0) {
                raw.append(SEPARATOR);
            }
            raw.append(part);
        }
        final String original = raw.toString();
        final String sanitized = sanitize(original);
        if (sanitized.equals(original) && sanitized.length() <= maxLength && !sanitized.isEmpty()) {
            return sanitized;
        }
        final String hash = Hashes.sha256(original, HASH_LENGTH);
        final int room = maxLength - HASH_LENGTH - 1;
        final String base = trimDashes(sanitized.length() > room ? sanitized.substring(0, room) : sanitized);
        return base.isEmpty() ? HASH_PREFIX + hash : base + SEPARATOR + hash;
    }

    /**
     * {@link #child(int, String...)} limited to {@link #DNS1123_LABEL_MAX}.
     *
     * @param parts to join
     * @return a valid, deterministic name
     */
    public static String child(final String... parts) {
        return child(DNS1123_LABEL_MAX, parts);
    }

    /**
     * @param value to check
     * @return true if the value is a valid DNS-1123 label
     */
    public static boolean isDns1123Label(final String value) {
        return value != null
                && !value.isEmpty()
                && value.length() <= DNS1123_LABEL_MAX
                && sanitize(value).equals(value);
    }

    /**
     * @param value to check
     * @return true if the value is a valid DNS-1123 subdomain: DNS-1123 labels joined with '.', at most 253
     *         characters
     */
    public static boolean isDns1123Subdomain(final String value) {
        if (value == null || value.isEmpty() || value.length() > DNS1123_SUBDOMAIN_MAX) {
            return false;
        }
        for (final String label : value.split("\\.", -1)) {
            if (!isDns1123Label(label)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param value to check
     * @return true if the value can be a label value: empty, or up to 63 alphanumerics with '-', '_' and '.'
     *         inside
     */
    public static boolean isLabelValue(final String value) {
        if (value == null) {
            return false;
        }
        if (value.isEmpty()) {
            return true;
        }
        if (value.length() > DNS1123_LABEL_MAX) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean alnum = isLowerAlphanumeric(Character.toLowerCase(c));
            final boolean edge = i == 0 || i == value.length() - 1;
            if (!alnum && (edge || (c != '-' && c != '_' && c != '.'))) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(final String value) {
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = Character.toLowerCase(value.charAt(i));
            sb.append(isLowerAlphanumeric(c) ? c : SEPARATOR);
        }
        return trimDashes(sb.toString());
    }

    private static String trimDashes(final String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SEPARATOR) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SEPARATOR) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isLowerAlphanumeric(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
