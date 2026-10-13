/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * Tells apart the failures a retry can fix from those it cannot.
 */
public final class Errors {
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int UNPROCESSABLE = 422;
    private static final String ELLIPSIS = "...";
    // deep enough for any wrapping, short of a cycle of causes
    private static final int MAX_CAUSE_DEPTH = 16;

    private Errors() {
    }

    /**
     * A failure the API server will keep answering the same way until something outside the controller changes:
     * a malformed or invalid object (400, 422) or missing permissions (401, 403). Retrying it quickly only adds
     * load; it is retried slowly instead.
     *
     * @param error to classify
     * @return true if the error, or one of its causes, is such an API error
     */
    public static boolean isPermanent(final Throwable error) {
        final KubernetesClientException e = find(error);
        if (e == null) {
            return false;
        }
        final int code = e.getCode();
        return code == BAD_REQUEST || code == UNAUTHORIZED || code == FORBIDDEN || code == UNPROCESSABLE;
    }

    /**
     * @param error to classify
     * @return true if the error, or one of its causes, is a failed call to the API server - whatever it answered,
     *         or no answer at all
     */
    public static boolean isApiError(final Throwable error) {
        return find(error) != null;
    }

    /**
     * @param error to classify
     * @return true if the error, or one of its causes, is a 409 Conflict - a stale read, fixed by a re-read
     */
    public static boolean isConflict(final Throwable error) {
        final KubernetesClientException e = find(error);
        return e != null && e.getCode() == CONFLICT;
    }

    /**
     * @param error to classify
     * @return true if the error, or one of its causes, is a 404 Not Found
     */
    public static boolean isNotFound(final Throwable error) {
        final KubernetesClientException e = find(error);
        return e != null && e.getCode() == NOT_FOUND;
    }

    /**
     * A message fit for a status: what the API server said, rather than the request fabric8 wraps around it, cut
     * to a length a status field can carry.
     *
     * @param error to describe
     * @param maxLength of the message, longer than the ellipsis a cut one ends with
     * @return the message
     */
    public static String message(final Throwable error,
                                 final int maxLength) {
        final KubernetesClientException e = find(error);
        String message = e != null && e.getStatus() != null && e.getStatus().getMessage() != null
                ? e.getStatus().getMessage() : error.getMessage();
        if (message == null) {
            message = error.getClass().getSimpleName();
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }

    private static KubernetesClientException find(final Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t instanceof KubernetesClientException) {
                return (KubernetesClientException) t;
            }
            t = t.getCause();
        }
        return null;
    }
}
