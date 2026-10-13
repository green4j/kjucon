/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.OperatorHealth;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Liveness and readiness probes over the JDK's own HTTP server: {@code /healthz} and {@code /readyz} answer 200 or
 * 503 with the health of the operator as text. kjucon itself has no HTTP server; this is all it takes.
 */
public final class HealthServer implements AutoCloseable {
    private static final int OK = 200;
    private static final int UNAVAILABLE = 503;

    private final HttpServer server;

    /**
     * @param operator whose health to serve
     * @param port to listen on
     */
    public HealthServer(final Operator operator,
                        final int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/healthz", exchange -> answer(exchange, operator, OperatorHealth::isLive));
        server.createContext("/readyz", exchange -> answer(exchange, operator, OperatorHealth::isReady));
        server.start();
    }

    /**
     * @return the port the probes are served on
     */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void answer(final HttpExchange exchange,
                               final Operator operator,
                               final Predicate<OperatorHealth> probe) throws IOException {
        final OperatorHealth health = operator.health();
        final byte[] body = (health + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(probe.test(health) ? OK : UNAVAILABLE, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
