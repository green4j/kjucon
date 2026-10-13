/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.util.function.Consumer;

/**
 * The one way the controllers reach an observer or the error handler. Everything reported is already decided, so
 * an exception leaving an observer could only break the controller which reports it: it goes to the error
 * handler instead, and one leaving the error handler is dropped.
 */
final class Observed {
    private final ErrorHandler errorHandler;

    Observed(final ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    ErrorHandler errorHandler() {
        return errorHandler;
    }

    <O> void by(final O observer,
                final Consumer<O> event) {
        if (observer == null) {
            return;
        }
        try {
            event.accept(observer);
        } catch (final RuntimeException e) {
            error("observer", "An observer failed", e);
        }
    }

    void error(final String source,
               final String message,
               final Throwable error) {
        if (errorHandler == null) {
            return;
        }
        try {
            errorHandler.onError(source, message, error);
        } catch (final RuntimeException ignored) {
            // the error handler is the last resort: nothing is left to tell
        }
    }

    void reconcileError(final String controller,
                        final ReconcileRequest request,
                        final ErrorHandler.Phase phase,
                        final int attempt,
                        final Throwable error) {
        if (errorHandler == null) {
            return;
        }
        try {
            errorHandler.onReconcileError(controller, request, phase, attempt, error);
        } catch (final RuntimeException ignored) {
            // the error handler is the last resort: nothing is left to tell
        }
    }
}
