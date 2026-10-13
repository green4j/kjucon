/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.test;

import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.crud.AttributeSet;
import io.fabric8.mockwebserver.dsl.HttpMethod;
import io.fabric8.mockwebserver.http.Buffer;
import io.fabric8.mockwebserver.http.Headers;
import io.fabric8.mockwebserver.http.MockResponse;
import io.fabric8.mockwebserver.http.RecordedRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * An in-memory API server for tests: fabric8's mock server in its CRUD mode, with two corrections.
 * <ul>
 *   <li>The CRUD mode answers a server-side apply with 415. Here an apply is a merge patch of an existing
 *   object, or the creation of a missing one - not the real thing (no field ownership, nothing removed which a
 *   manager stopped applying), but enough to drive controllers through their flows.</li>
 *   <li>The CRUD mode merges the arrays of a JSON merge patch into the existing ones, so a list - finalizers -
 *   can never shrink. Here a merge patch replaces arrays, as RFC 7386 and the API server do.</li>
 * </ul>
 * What only a real API server does is for the integration tests against k3s.
 */
public final class MockKubernetes implements AutoCloseable {
    private static final String APPLY = "apply-patch";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String MERGE_PATCH = "application/merge-patch+json";
    private static final String JSON = "application/json";
    private static final String STATUS = "/status";

    private final KubernetesMockServer server;
    private final NamespacedKubernetesClient client;
    private final ApplyAwareDispatcher dispatcher = new ApplyAwareDispatcher();

    /**
     * Starts the server and a client of it, in the namespace "test".
     */
    public MockKubernetes() {
        server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(),
                dispatcher, false);
        server.init();
        client = server.createClient();
    }

    /**
     * @return a client of the server
     */
    public NamespacedKubernetesClient client() {
        return client;
    }

    /**
     * @param path of an object, such as {@code /api/v1/namespaces/test/configmaps/c1}, without a query
     * @return how many times it was read - lists and watches not counted
     */
    public int gets(final String path) {
        final AtomicInteger count = dispatcher.gets.get(path);
        return count == null ? 0 : count.get();
    }

    /**
     * Answers the creations posted to a collection itself, instead of storing them: for the reviews and other
     * requests which the API server computes an answer to.
     *
     * @param path of the collection, such as {@code /apis/authorization.k8s.io/v1/selfsubjectaccessreviews}
     * @param answer the JSON answered to the JSON posted
     */
    public void answerCreate(final String path,
                             final UnaryOperator<String> answer) {
        dispatcher.answers.put(path, answer);
    }

    /**
     * Refuses the applies of an object, as the API server refuses an invalid one.
     *
     * @param path of the object, such as {@code /api/v1/namespaces/test/configmaps/c1}
     * @param code the status code answered, such as 422
     */
    public void refuseApply(final String path,
                            final int code) {
        dispatcher.refusals.put(APPLY + path, new Refusal(code, Integer.MAX_VALUE));
    }

    /**
     * Refuses the next applies of an object, then accepts them again - as a conflict or a race the server sees
     * once.
     *
     * @param path of the object, such as {@code /api/v1/namespaces/test/configmaps/c1}
     * @param code the status code answered, such as 409
     * @param times how many applies are refused
     */
    public void refuseApply(final String path,
                            final int code,
                            final int times) {
        dispatcher.refusals.put(APPLY + path, new Refusal(code, times));
    }

    /**
     * Refuses the next patches of an object other than applies - merge and JSON patches, those of finalizers.
     *
     * @param path of the object
     * @param code the status code answered, such as 409
     * @param times how many patches are refused
     */
    public void refusePatch(final String path,
                            final int code,
                            final int times) {
        dispatcher.refusals.put(path, new Refusal(code, times));
    }

    @Override
    public void close() {
        client.close();
        server.destroy();
    }

    private static final class ApplyAwareDispatcher extends KubernetesCrudDispatcher {
        private final KubernetesSerialization serialization = new KubernetesSerialization();
        private final Map<String, AtomicInteger> gets = new ConcurrentHashMap<>();
        private final Map<String, UnaryOperator<String>> answers = new ConcurrentHashMap<>();
        private final Map<String, Refusal> refusals = new ConcurrentHashMap<>();

        @Override
        public MockResponse handleCreate(final RecordedRequest request) {
            final UnaryOperator<String> answer = answers.get(withoutQuery(request.getPath()));
            if (answer == null) {
                return super.handleCreate(request);
            }
            return new MockResponse().setResponseCode(201).setBody(answer.apply(request.getUtf8Body()));
        }

        @Override
        public MockResponse handleGet(final String path) {
            gets.computeIfAbsent(withoutQuery(path), k -> new AtomicInteger()).incrementAndGet();
            return super.handleGet(path);
        }

        @Override
        public MockResponse handlePatch(final RecordedRequest request) {
            final String contentType = request.getHeader(CONTENT_TYPE);
            final boolean apply = contentType != null && contentType.contains(APPLY);
            final boolean merge = contentType != null && contentType.contains(MERGE_PATCH);
            final String path = withoutQuery(request.getPath());
            final Refusal refusal = refusals.get(apply ? APPLY + path : path);
            if (refusal != null && refusal.left.getAndDecrement() > 0) {
                return new MockResponse().setResponseCode(refusal.code).setBody("{\"kind\":\"Status\","
                        + "\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":" + refusal.code + "}");
            }
            if (!apply && !merge) {
                return super.handlePatch(request);
            }
            final Map.Entry<AttributeSet, String> current = findResource(getKey(path));
            final String json = serialization.convertToJson(request.getUtf8Body());
            if (current == null) {
                if (merge || path.endsWith(STATUS)) {
                    return new MockResponse().setResponseCode(404);
                }
                final String collection = path.substring(0, path.lastIndexOf('/'));
                return handleCreate(rewrite(request, HttpMethod.POST, collection, JSON, json));
            }
            final Map<String, Object> merged = mergePatch(parse(current.getValue()), parse(json));
            if (apply) {
                // an apply carries no precondition; the stored version is the one it applies to
                metadata(merged).put("resourceVersion", metadata(parse(current.getValue())).get("resourceVersion"));
            }
            return handleUpdate(rewrite(request, HttpMethod.PUT, path, JSON, serialization.asJson(merged)));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> parse(final String json) {
            return serialization.unmarshal(json, Map.class);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> metadata(final Map<String, Object> resource) {
            return (Map<String, Object>) resource.computeIfAbsent("metadata", k -> new LinkedHashMap<>());
        }

        // RFC 7386: objects merge recursively, null removes, anything else - arrays included - replaces.
        @SuppressWarnings("unchecked")
        private static Map<String, Object> mergePatch(final Map<String, Object> target,
                                                      final Map<String, Object> patch) {
            final Map<String, Object> result = new LinkedHashMap<>(target);
            for (final Map.Entry<String, Object> e : patch.entrySet()) {
                final Object value = e.getValue();
                if (value == null) {
                    result.remove(e.getKey());
                } else if (value instanceof Map && result.get(e.getKey()) instanceof Map) {
                    result.put(e.getKey(), mergePatch((Map<String, Object>) result.get(e.getKey()),
                            (Map<String, Object>) value));
                } else {
                    result.put(e.getKey(), value);
                }
            }
            return result;
        }

        private static RecordedRequest rewrite(final RecordedRequest request,
                                               final HttpMethod method,
                                               final String path,
                                               final String contentType,
                                               final String body) {
            final Headers headers = request.getHeaders().newBuilder()
                    .removeAll(CONTENT_TYPE)
                    .add(CONTENT_TYPE, contentType)
                    .build();
            return new RecordedRequest(request.getHttpVersion(), method, path, headers,
                    new Buffer().writeUtf8(body));
        }

        private static String withoutQuery(final String path) {
            final int query = path.indexOf('?');
            return query < 0 ? path : path.substring(0, query);
        }
    }

    // what the server answers a refused request with, and how many more times
    private static final class Refusal {
        private final int code;
        private final AtomicInteger left;

        private Refusal(final int code,
                        final int times) {
            this.code = code;
            this.left = new AtomicInteger(times);
        }
    }
}
