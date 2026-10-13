/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON patch (RFC 6902) which replaces a value only if another one is still as it was read: a {@code test}, then
 * a {@code replace}.
 */
final class JsonPatch {
    // the API server answers a failed test as any patch it cannot apply
    private static final int UNPROCESSABLE = 422;

    private JsonPatch() {
    }

    /**
     * @param client to patch with
     * @param resource to patch
     * @param testPath of the value which must not have changed
     * @param expected value there
     * @param replacePath of the value to replace
     * @param value to put there
     * @param <T> type of the resource
     * @return the patched resource
     * @throws KubernetesClientException if the patch failed - {@link #isFailedTest} if it may be a failed test
     */
    static <T extends HasMetadata> T testAndReplace(final KubernetesClient client,
                                                    final T resource,
                                                    final String testPath,
                                                    final Object expected,
                                                    final String replacePath,
                                                    final Object value) {
        final List<Map<String, Object>> operations = List.of(
                operation("test", testPath, expected),
                operation("replace", replacePath, value));
        return client.resource(resource).patch(PatchContext.of(PatchType.JSON),
                client.getKubernetesSerialization().asJson(operations));
    }

    /**
     * @param error of {@link #testAndReplace}
     * @return true if the test may have failed: the server tells it from no other invalid patch
     */
    static boolean isFailedTest(final KubernetesClientException error) {
        return error.getCode() == UNPROCESSABLE;
    }

    private static Map<String, Object> operation(final String op,
                                                 final String path,
                                                 final Object value) {
        final Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", op);
        operation.put("path", path);
        operation.put("value", value);
        return operation;
    }
}
