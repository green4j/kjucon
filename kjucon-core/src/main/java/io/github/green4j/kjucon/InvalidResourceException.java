/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

/**
 * Thrown by a reconciler when the resource itself is wrong - a missing field, a value out of range - so that no
 * retry could ever succeed. The controller reports it to its error handler and does not retry; the next event of
 * the resource - a change of it or of something it owns, or a resync - is what reconciles it again.
 */
public class InvalidResourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message what is wrong with the resource, as it should appear in its status
     */
    public InvalidResourceException(final String message) {
        super(message);
    }

    /**
     * @param message what is wrong with the resource, as it should appear in its status
     * @param cause of it
     */
    public InvalidResourceException(final String message,
                                    final Throwable cause) {
        super(message, cause);
    }
}
