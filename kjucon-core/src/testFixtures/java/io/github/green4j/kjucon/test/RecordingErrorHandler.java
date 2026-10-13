/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.test;

import io.github.green4j.kjucon.ErrorHandler;
import io.github.green4j.kjucon.ReconcileRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records what an operator reports, for the tests to look at.
 */
public final class RecordingErrorHandler implements ErrorHandler {
    private final List<Throwable> reconcileErrors = new CopyOnWriteArrayList<>();
    private final List<String> reconcileFailures = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    @Override
    public void onReconcileError(final String controller,
                                 final ReconcileRequest request,
                                 final Phase phase,
                                 final int attempt,
                                 final Throwable error) {
        reconcileErrors.add(error);
        reconcileFailures.add(phase + ":" + attempt + ":" + error.getMessage());
    }

    @Override
    public void onError(final String source,
                        final String message,
                        final Throwable error) {
        errors.add(source + ": " + message);
    }

    /**
     * @return the errors of reconciles and cleanups, as they were thrown or returned
     */
    public List<Throwable> reconcileErrors() {
        return reconcileErrors;
    }

    /**
     * @return the failed reconciles and cleanups, each as phase:attempt:message
     */
    public List<String> reconcileFailures() {
        return reconcileFailures;
    }

    /**
     * @return the other errors, each as source: message
     */
    public List<String> errors() {
        return errors;
    }
}
