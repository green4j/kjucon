/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.github.green4j.kjucon.util.Errors;

import java.io.PrintStream;
import java.time.Instant;

/**
 * The default {@link ErrorHandler}: prints to the standard error, so that no failure goes unnoticed. The stack
 * trace of a failing reconcile is printed on its first failure only; the retries print one line each. An
 * application with a logging framework replaces it with a handler which logs.
 */
public class StdErrErrorHandler implements ErrorHandler {
    private final PrintStream out;

    /**
     * Prints to {@code System.err}.
     */
    public StdErrErrorHandler() {
        this(System.err);
    }

    /**
     * @param out to print to
     */
    public StdErrErrorHandler(final PrintStream out) {
        this.out = out;
    }

    @Override
    public void onReconcileError(final String controller,
                                 final ReconcileRequest request,
                                 final Phase phase,
                                 final int attempt,
                                 final Throwable error) {
        final String what = phase == Phase.CLEANUP ? "Cleanup" : "Reconcile";
        if (error instanceof InvalidResourceException) {
            print(controller + ": " + what + " of " + request + " rejected the resource: " + error.getMessage(),
                    null);
            return;
        }
        if (attempt <= 1 && !Errors.isPermanent(error)) {
            print(controller + ": " + what + " of " + request + " failed", error);
            return;
        }
        print(controller + ": " + what + " of " + request + " failed (attempt " + attempt + "): " + error, null);
    }

    @Override
    public void onError(final String source,
                        final String message,
                        final Throwable error) {
        print(source + ": " + message, error);
    }

    private void print(final String line,
                       final Throwable error) {
        synchronized (out) {
            out.println(Instant.now() + " kjucon " + line);
            if (error != null) {
                error.printStackTrace(out);
            }
        }
    }
}
