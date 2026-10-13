/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

/**
 * The one place a failure is told in full - kjucon itself logs nothing, so this is what a log wants. Called from
 * the thread which met the failure, after it was handled; it must not block. {@link StdErrErrorHandler} is the
 * default.
 */
public interface ErrorHandler {

    /** What failed. */
    enum Phase {
        /** {@link Reconciler#reconcile}, or adding the finalizer before it. */
        RECONCILE,
        /** {@link Reconciler#cleanup}, or removing the finalizer after it. */
        CLEANUP
    }

    /**
     * A reconcile or a cleanup failed; the retry is scheduled right after this call - except for an
     * {@link InvalidResourceException}, which waits for the next event of the resource.
     *
     * @param controller name
     * @param request which failed
     * @param phase which failed
     * @param attempt the number of failures in a row, from 1
     * @param error as it was thrown or returned
     */
    void onReconcileError(String controller,
                          ReconcileRequest request,
                          Phase phase,
                          int attempt,
                          Throwable error);

    /**
     * Anything else failed and was dealt with: an informer, a secondary mapper, an event source, an observer, a
     * best-effort status write. Nothing waits on it.
     *
     * @param source of the failure: a controller name, an informed type, "operator"
     * @param message what was being done
     * @param error the failure, may be null
     */
    void onError(String source,
                 String message,
                 Throwable error);
}
