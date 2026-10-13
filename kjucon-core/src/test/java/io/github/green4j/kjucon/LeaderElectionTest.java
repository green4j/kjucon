/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import io.github.green4j.kjucon.test.Fixtures;
import io.github.green4j.kjucon.test.MockKubernetes;
import io.github.green4j.kjucon.test.RecordingErrorHandler;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.green4j.kjucon.test.Fixtures.CONTROLLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderElectionTest {
    private static final String NS = Fixtures.NAMESPACE;

    private MockKubernetes kubernetes;
    private KubernetesClient client;

    @BeforeEach
    void start() {
        kubernetes = new MockKubernetes();
        client = kubernetes.client();
    }

    @AfterEach
    void stop() {
        kubernetes.close();
    }

    @Test
    void noAccessToTheLeaseIsReported() {
        kubernetes.answerCreate("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews",
                body -> body.replaceFirst("\\}\\s*$", ",\"status\":{\"allowed\":false}}"));
        final RecordingErrorHandler handler = new RecordingErrorHandler();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(NS)
                .leaderElection(LeaderElection.lease(NS, "widgets-leader").identity("me"))
                .errorHandler(handler)
                .exit(status -> { });
        builder.controller(Widget.class, context -> ReconcileResult.success()).name(CONTROLLER);

        try (Operator operator = builder.build()) {
            operator.start();
            assertEquals(List.of("leader-election: No access to the lease test/widgets-leader to get, create, update;"
                    + " this instance will never lead"), handler.errors());
        }
    }

    @Test
    void lossOfTheLeadershipStopsTheControllersAndExits() {
        final AtomicInteger exitStatus = new AtomicInteger(-1);
        final List<String> events = new CopyOnWriteArrayList<>();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(NS)
                .leaderElection(LeaderElection.lease(NS, "widgets-leader")
                        .identity("me")
                        .leaseDuration(Duration.ofSeconds(2))
                        .renewDeadline(Duration.ofMillis(1500))
                        .retryPeriod(Duration.ofMillis(200))
                        .onLost(() -> events.add("onLost")))
                .observer(new ControllerObserver() {
                    @Override
                    public void onLeadershipLost(final String identity) {
                        events.add("lost");
                    }

                    @Override
                    public void onControllerStopped(final String controller) {
                        events.add("stopped");
                    }
                })
                .exit(exitStatus::set);
        builder.controller(Widget.class, context -> ReconcileResult.success()).name(CONTROLLER);

        try (Operator operator = builder.build()) {
            Await.started(operator);

            // another instance takes the lease over
            Await.until(() -> {
                Conflicts.retry(() -> client.resources(Lease.class).inNamespace(NS).withName("widgets-leader")
                        .edit(lease -> {
                            lease.getSpec().setHolderIdentity("somebody-else");
                            lease.getSpec().setRenewTime(ZonedDateTime.now().plusSeconds(30));
                            return lease;
                        }));
                return exitStatus.get() >= 0;
            }, "the exit after the loss");

            assertEquals(1, exitStatus.get());
            assertEquals(List.of("lost", "stopped", "onLost"), events);
            assertFalse(operator.health().isLive());
            assertTrue(operator.health().leaderElection());
        }
    }
}
