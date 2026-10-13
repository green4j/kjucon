## kjucon user manual

A tutorial from the simplest controller to hierarchies of compositions running in production. Each chapter
builds on the one before. The [Quick start](quick-start.md) is the short way to a running operator; this is the
long way, with the reasons.

Three examples in [kjucon-example](../kjucon-example) follow the chapters, each in a package with a main class
of its own, runnable against the current kubeconfig context and tested on k3s:

| package | main class, run task | what it is | chapters |
|---|---|---|---|
| `greeting` | `GreetingOperator`, `runGreetings` | one custom resource, one plain reconciler, `kjucon-core` alone | 1-4 |
| `mirror` | `MirrorController`, `runMirror` | a controller of ConfigMaps - no custom resource - writing to a target cluster | 5 |
| `webapp` | `WebAppOperator`, `runWebApps` | a composition, and a composition of compositions | 6-10 |

1. [A plain reconciler](#1-a-plain-reconciler)
2. [The operator](#2-the-operator)
3. [What wakes a reconcile](#3-what-wakes-a-reconcile)
4. [Cleanup and finalizers](#4-cleanup-and-finalizers)
5. [Controllers without a custom resource](#5-controllers-without-a-custom-resource)
6. [From a reconciler to a composition](#6-from-a-reconciler-to-a-composition)
7. [Order, readiness and rollouts](#7-order-readiness-and-rollouts)
8. [When the composition changes](#8-when-the-composition-changes)
9. [Hierarchies](#9-hierarchies)
10. [Sharing the cluster](#10-sharing-the-cluster)
11. [Running in production](#11-running-in-production)
12. [Testing](#12-testing)
13. [Reference](#13-reference)

---

### 1. A plain reconciler

A controller makes the cluster look the way a resource describes. kjucon calls that a reconcile: it is given
the resource as it is now, never the event which caused it, and works everything out from that. Several events
may collapse into one call; a call may come with nothing changed at all. Calls for one resource never overlap.

The smallest useful one, `GreetingReconciler` of the example: a `Greeting` which keeps a ConfigMap with its
text and reports in its status.

```java
@Group("example.kjucon.green4j.github.io")
@Version("v1")
public class Greeting extends CustomResource<GreetingSpec, StandardStatus> implements Namespaced {
}

public final class GreetingReconciler implements Reconciler<Greeting> {
    @Override
    public void configure(ControllerBuilder<Greeting> controller) {
        controller.name("greetings")
                .owns(ConfigMap.class)                // a change of its ConfigMap wakes the greeting
                .finalizer(false);                    // nothing to clean up: see chapter 4
    }

    @Override
    public ReconcileResult reconcile(ReconcileContext<Greeting> context) {
        Greeting greeting = context.primary();
        String to = greeting.getSpec() == null ? null : greeting.getSpec().getTo();
        if (to == null || to.isBlank()) {
            // nothing to retry until somebody fixes the spec: say so, and wait for the change
            setStatus(context, status -> status.markError("spec.to is required", generation(context)));
            return ReconcileResult.success();
        }
        context.apply(new ConfigMapBuilder()
                .withMetadata(context.newMetadata(greeting.getMetadata().getName() + "-greeting"))
                .addToData("text", "Hello, " + to + '!')
                .build());
        setStatus(context, status -> status.markReady(generation(context)));
        return ReconcileResult.success();
    }

    // the status is written only if the update changes it
    private static void setStatus(ReconcileContext<Greeting> context, Predicate<StandardStatus> update) {
        context.patchStatus(greeting -> {
            if (greeting.getStatus() == null) {
                greeting.setStatus(new StandardStatus());
            }
            return update.test(greeting.getStatus());
        });
    }
}
```

`configure` is called when the reconciler is added to an operator, so the reconciler states what its controller
needs itself; chapter 2 adds it to one. What the context does here:
* `newMetadata(name)` - metadata of an object the resource owns: the name, its namespace, an owner reference
  (the object is garbage collected with the resource, and its changes can wake it up) and kjucon's owner labels
  and annotations.
* `apply(object)` - server-side apply under the controller's field manager, forced. What the controller no
  longer sets is removed, what other managers set is kept.
* `patchStatus(updater)` - the updater says whether it changed anything. If not, nothing is written - no event
  for every watcher. If it did, the status alone is applied on the `/status` subresource of a fresh read.
* reads - `get`, `list`, `owned` - come from the operator's informers when the type is watched, and from the
  API server otherwise. `getLive` always goes to the API server, and `secretValues(namespace, name)` reads a
  Secret's values decoded.

`StandardStatus` holds a phase (`Pending`, `Ready`, `Error`, `Deleting`), a message, `observedGeneration`,
and the `Ready`, `Reconciling` and `Stalled` conditions in `metav1.Condition` shape. Any status class works;
this one is what compositions maintain for you later on.

What a reconcile returns:

| result | meaning |
|---|---|
| `ReconcileResult.success()` | done; wait for the next event |
| `ReconcileResult.success(after)` | done; reconcile again after a while anyway |
| `ReconcileResult.requeue()` / `requeueAfter(delay)` | not done yet - come back, with no back-off |
| `ReconcileResult.error(e)` - or throwing | failed; retried with back-off |

A resource which cannot be reconciled until somebody changes it - an invalid spec - must not be retried in a
loop. The greeting says so in its status and returns a success; throwing `InvalidResourceException` does the
same for a reconciler with nothing to report: it is not retried with back-off, and the next change of the
resource, or of something it owns, brings it back.

### 2. The operator

An `Operator` is a set of controllers sharing one client, one set of informers and one leader election:

```java
try (KubernetesClient client = new KubernetesClientBuilder().build()) {
    Operator.Builder builder = Operator.builder()
            .client(client)
            .watchNamespace("demo")                  // or watchNamespaces(...), watchAllNamespaces()
            .labelDomain("demo.example.com");

    builder.controller(Greeting.class, new GreetingReconciler())   // configures itself, see chapter 1
            .workers(2);                             // anything set here wins over what it configured

    try (Operator operator = builder.build()) {
        operator.start();
        ...                                          // until the process should stop
    }
}
```

* `start()` returns at once. Informers list and watch in the background; the controllers begin once their
  informers have synced - and, with leader election, once this instance leads.
* Without `watchNamespace...`, the operator watches the namespace of the kubeconfig context, or all of them.
* The label domain prefixes everything kjucon puts on objects: `<domain>/owner-uid`, `<domain>/controller`,
  the finalizer `<domain>/<controller name>`. It must be a DNS-1123 subdomain - your own domain, so that two
  operators never take each other's objects.
* The controller name is the field manager of what it applies, the value of its controller label and the
  default finalizer. It must be a label value, unique within the operator, and it should not change - see
  [renaming](#renaming) when it must.
* `workers(n)` reconciles up to n different resources in parallel. One resource is never reconciled twice at
  the same time.
* Nothing is registered anywhere but in the operator: it owns its controllers, informers, threads and
  reconcilers, and `close()` releases them all - the controllers stop, the informers stop, and every
  `Reconciler`, an `AutoCloseable`, is closed. What the application built and handed over - the client, a
  client of another cluster - stays the application's to close.
* A reconciler can configure the controller it is given to - `Reconciler.configure(ControllerBuilder)`, called
  by `controller(type, reconciler)` before the caller's own settings, which win.

### 3. What wakes a reconcile

A request is queued when:
* **the resource changes.** By default, a change of its spec (the generation), labels or annotations; a change
  of the status alone does not wake its own controller. `filter(EventFilter)` changes that -
  `EventFilter.all()`, `generationChanged()`, `labelsChanged()`, `annotationsChanged()`, combined with `or` and
  `and`. A type without a generation - a ConfigMap, a Secret - cannot tell its content from anything else, so
  every change of it counts.
* **something it owns changes** - `owns(ConfigMap.class)`. The owner is found by the owner reference, or, for
  an object in another namespace or cluster-scoped which cannot have one, by the owner annotations
  `newMetadata` puts on it.
* **something it depends on changes** - `watches(Secret.class, mapper)`, where a `SecondaryMapper` names the
  resources to reconcile:

  ```java
  .watches(Secret.class, secret -> greetingsUsing(secret.getMetadata().getName()))
  ```

* **something outside Kubernetes says so** - an `EventSource`, started with the controller:

  ```java
  .eventSource(enqueuer -> scheduler.scheduleAtFixedRate(
          () -> enqueuer.enqueue(new ReconcileRequest("demo", "hello")), 1, 1, TimeUnit.MINUTES))
  ```

* **time passes** - `resyncPeriod(Duration)` reconciles every resource again, changed or not, or a reconcile
  returns `success(after)` for itself.
* **it failed** - retried by `retry(RateLimiter)`, `RateLimiter.defaults()` being exponential from 500 ms to 5
  minutes with jitter, per resource. API errors a retry cannot fix - 400, 401, 403, 422 - are retried at the
  slowest pace.

`selector(Map.of("tier", "web"))` makes a controller reconcile only the resources with those labels. A
resource holding the controller's finalizer is still cleaned up when it is deleted, even if it lost the
labels meanwhile.

### 4. Cleanup and finalizers

With the finalizer, a deleted resource stays until `cleanup` succeeds:

```java
@Override
public ReconcileResult cleanup(ReconcileContext<Greeting> context) {
    externalSystem.forget(context.request().name());
    return ReconcileResult.success();                // the finalizer goes now
}
```

* The controller adds the finalizer before the first reconcile. Once `cleanup` returns a success it removes it,
  and the API server deletes the resource. A failure is retried with back-off; `requeueAfter` waits for
  something - objects to go - without counting as a failure.
* What the resource owns through an owner reference is deleted by the garbage collector anyway - which is why
  the greeting does without a finalizer. `cleanup` is for what the garbage collector cannot reach: other
  namespaces, cluster-scoped objects, other clusters, the world outside.
* `deleted(request)` is called once the resource is gone, from the informer's thread: drop whatever the
  reconciler kept in memory about it.
* `finalizer(false)` does without, `finalizer("other.example.com/name")` uses another name.

### 5. Controllers without a custom resource

Nothing in kjucon needs a CRD. The primary type of a controller can be any type the API server serves - a
Secret, a Namespace, a Service, a custom resource of somebody else - and the reconcile can write anywhere: the
cluster, another cluster, a database, a cloud API. That is a controller, not an operator in the CRD sense, and
the loop is the same: shared informers, the work queue, back-off, leader election, observers.

`ConfigMapMirror` of the example copies every ConfigMap labelled `mirror: "true"` into a namespace of a target
cluster, keeps the copy up to date and deletes it when the original goes:

```java
builder.controller(ConfigMap.class, new ConfigMapMirror(target, "mirrored"));
```

```java
@Override
public void configure(ControllerBuilder<ConfigMap> controller) {
    controller.name("configmap-mirror")
            .selector(Map.of("mirror", "true"))
            .resyncPeriod(Duration.ofMinutes(10));  // a copy changed in the target is not seen otherwise
}

@Override
public ReconcileResult reconcile(ReconcileContext<ConfigMap> context) {
    ConfigMap original = context.primary();
    ConfigMap copy = new ConfigMapBuilder()
            .withNewMetadata()
                .withNamespace(targetNamespace)
                .withName(copyName(original))
                .addToAnnotations(SOURCE, context.request().key())
            .endMetadata()
            .withData(original.getData())
            .withBinaryData(original.getBinaryData())
            .build();
    // applied, not replaced: a key removed from the original goes from the copy, what others add to it stays
    target.resource(copy).fieldManager("configmap-mirror").forceConflicts().serverSideApply();
    return ReconcileResult.success();
}

@Override
public ReconcileResult cleanup(ReconcileContext<ConfigMap> context) {
    // only now the finalizer goes and the original with it: the copy cannot be forgotten
    target.configMaps().inNamespace(targetNamespace).withName(copyName(context.primary())).delete();
    return ReconcileResult.success();
}
```

Things to decide for such a controller:
* **The finalizer.** It is on by default, which puts `<domain>/<controller name>` on every resource the
  controller reconciles - here, on ConfigMaps the controller does not own. Keep it only if `cleanup` must undo
  something before the object may go - as the mirror deletes the copy. Otherwise `finalizer(false)`.
* **Status.** A built-in type usually has no status of yours to write. Report through the `ControllerObserver`
  and the `ErrorHandler` instead, or through events and annotations if users must see it.
* **What wakes it.** A change in the target is invisible to Kubernetes: an `EventSource` watching the target, or
  a resync, brings it back.
* **Another cluster.** The operator's client is the cluster it watches. Writes to a second cluster go through a
  client of your own; watching both means one operator per cluster, or an `EventSource` watching the other one
  and queuing the resources of this one.
* **Memory.** Informers cache every object of a watched type in the watched namespaces, not only the selected
  ones: watching all Secrets cluster-wide holds all of them, and needs the rights to read them.

Compositions, from the next chapter on, are for resources made of Kubernetes objects. A controller like this
one needs `kjucon-core` only.

### 6. From a reconciler to a composition

Most controllers do the same thing in every reconcile: build a few objects, apply them in some order, wait for
them, delete what is no longer needed, set the status. A **composition** declares the objects, and
`CompositionReconciler` does the rest.

The `Greeting` of chapter 1, as a composition:

```java
CompositionFactory<Greeting> greetings = context -> {
    Greeting greeting = context.primary();
    Composition.Builder<Greeting> builder = Composition.builder();
    builder.component(greeting.getMetadata().getName() + "-greeting", ConfigMap.class,
            (ctx, meta) -> new ConfigMapBuilder().withMetadata(meta)
                    .addToData("text", "Hello, " + greeting.getSpec().getTo() + '!')
                    .build());
    return builder.build();
};

builder.controller(Greeting.class,
                CompositionReconciler.builder(greetings)
                        .manages(ConfigMap.class)
                        .build())
        .name("greetings");
```

* A **component** has a type, a name and a factory. The factory gets metadata already filled in - name,
  namespace, owner reference, owner labels and annotations, the `app.kubernetes.io/component` label - and builds
  the desired state on it. It must keep the type, name and namespace it is given.
* `manages(...)` declares every type a composition may hold. The controller owns them - a change of any of
  its objects wakes the resource up - and prune and cleanup look only at them.
* A `CompositionReconciler` is added like any reconciler, with `controller(type, reconciler)`. It configures
  its controller itself - `owns(...)` of every managed type, and the finalizer its cleanup needs - and the
  `ControllerBuilder` returned is configured further as in chapter 2. The operator closes it when it is
  closed itself.
* The status is the composition's: `Pending` with what is awaited, `Ready`, `Error` with what failed,
  `Deleting`, with the conditions and `observedGeneration`. The resource must have a `StandardStatus`, or
  supply another `ComposedStatusUpdater` with `statusUpdater(...)`.
* A factory which throws `InvalidResourceException` puts the resource in `Error` with the message, and nothing
  of it is applied.

### 7. Order, readiness and rollouts

Components are applied in dependency order, each one once everything it depends on is ready:

```java
Component<WebApp, ConfigMap> page = app.component(name + "-page", ConfigMap.class, this::page);
Component<WebApp, Deployment> server = app.component(name, Deployment.class, this::deployment)
        .rolloutOn(page);
Component<WebApp, Service> service = app.component(name, Service.class, this::service)
        .dependsOn(server);
```

* `dependsOn(...)` - applied once those are ready; deleted before them.
* `rolloutOn(...)` - also stamps a hash of those components on the pod template of a Deployment, StatefulSet or
  DaemonSet, so the pods roll whenever a ConfigMap or Secret they read changes. It implies `dependsOn`.

Readiness follows kstatus:
* built-in checks for Deployment, StatefulSet, DaemonSet, ReplicaSet, Job, Pod, PersistentVolumeClaim and
  LoadBalancer Services;
* `Ready` conditions and `observedGeneration` for anything else with a status, and the `StandardStatus` phase
  for resources composed by kjucon;
* anything without a status is ready once it exists.

For one component, `readyWhen(check)` replaces the check of its type:

```java
app.component(name, Ingress.class, this::ingress).readyWhen(ReadinessChecks.INGRESS_WITH_ADDRESS);
```

For a type, everywhere: `CompositionReconciler.builder(...).readiness(ReadinessChecks.defaults().with(Kafka.class,
kafka -> ...))`.

Nothing is polled: changes of the objects wake the resource up. `progressResync` (30 s) reconciles a
composition which is not ready again should no event come, and `resync` (5 minutes) a ready one.

**Jobs.** A composed Job runs once. A Job template cannot change, so a Job meant to run again when something
changes carries a hash of it in its name - `Names.child(name, hash)`. The old one is pruned, the new one runs.
A Job with `ttlSecondsAfterFinished` fails its component: deleted by its TTL, it would be created and run
again.

### 8. When the composition changes

The factory runs on every reconcile, so a composition may differ from one reconcile to the next.

* **Prune.** An object the resource owns which is no longer in the composition is deleted. Renaming a component
  is a prune and a create: the old object goes in the same pass the new one is applied, before the new one is
  ready - mind it for a Service or a PersistentVolumeClaim.
* **Orphans.** `.deletePolicy(DeletePolicy.ORPHAN)` releases the object instead - for data which must outlive
  its owner. kjucon removes its owner reference and the labels and annotations it put on it, and never touches
  it again.
* **Cleanup.** With `CleanupMode.ORDERED`, the default, a deleted resource's objects go in reverse dependency
  order - an object once everything depending on it is gone - and the finalizer goes after the last one.
  `.cleanup(CleanupMode.GARBAGE_COLLECTOR)` leaves it to the garbage collector, without a finalizer.
* **Other namespaces.** `.namespace("shared")` puts a component in another namespace, `.namespace(null)` makes
  it cluster-scoped. The operator must watch where it lives - that is how kjucon sees it to prune and delete
  it, and how its changes wake the owner. Such an object cannot have an owner reference, so the garbage
  collector would never delete it: it needs `CleanupMode.ORDERED`, as does `ORPHAN`.
* A composition breaking these rules, or holding a type not declared with `manages(...)`, is invalid: nothing
  of it is applied and the resource goes to `Error`.

### 9. Hierarchies

A component can be a custom resource composed by another controller of the operator. The parent waits for it
to be `Ready` like for any Deployment, its status changes wake the parent, and deleting the parent unwinds the
tree level by level.

[kjucon-example](../kjucon-example) composes a `WebAppSet` of one `WebApp` per application:

```java
CompositionFactory<WebAppSet> sets = context -> {
    WebAppSet set = context.primary();
    Composition.Builder<WebAppSet> builder = Composition.<WebAppSet>builder()
            .propagateLabels("team");                // the set's "team" label goes on every WebApp
    for (WebAppSetSpec.App app : set.getSpec().getApps()) {
        builder.component(Names.child(set.getMetadata().getName(), app.getName()), WebApp.class,
                (ctx, meta) -> webApp(meta, app));
    }
    return builder.build();
};

builder.controller(WebAppSet.class,
        CompositionReconciler.builder(sets).manages(WebApp.class).build()).name("webappsets");
builder.controller(WebApp.class,
        CompositionReconciler.builder(webApps).manages(ConfigMap.class, Deployment.class, Service.class).build())
        .name("webapps");
```

* `Names.child(...)` joins name parts and shortens the result with a hash when it would be too long for a name.
* `propagateLabels` and `propagateAnnotations` copy keys of the resource onto every component;
  `decorate(MetadataDecorator)` adds anything else, per component.

### 10. Sharing the cluster

kjucon tells its objects by labels of its domain: `<domain>/owner-uid` names the resource,
`<domain>/controller` the controller. `app.kubernetes.io/managed-by` is set too, for people and tools; where
Helm or Argo CD sets it, `managedByLabel(false)` leaves it to them.

* **Adoption.** An object with the name of a component which nobody controls is adopted. Fields its creator set
  and the composition does not apply stay, owned by that creator. The adopting apply carries the
  resourceVersion the object was seen with, so an object changed meanwhile is not taken over blindly: the next
  pass looks again.
* **Somebody else's object.** An object of another resource, or of another controller of the same resource,
  is not taken over - in any namespace, cluster-scoped too. The component fails naming the owner, and is
  retried with back-off, so it gets the object once the owner lets it go. An object pruned or released is free
  for another resource at once.
* **Two at once.** Within an operator, an object just applied for one resource is held for it until the cache
  shows it. Across replicas, leader election keeps one instance applying. Two different operators creating an
  object which does not exist yet can still both succeed; the last one wins, the other resource fails on its
  next pass.
* **Forced apply.** A field another controller also writes, such as `spec.replicas` under a
  HorizontalPodAutoscaler, is taken back on every reconcile. A component must not set such a field.
* **When an object cannot be applied.** One which is somebody else's, or a Job with a TTL, fails its component
  alone: the components depending on it wait, the others are applied, what left the composition is pruned, and
  the resource goes to `Error` with the reason. One the API server refuses - invalid, forbidden, the server
  failing - stops the reconcile by default: the components after it are not applied and nothing is pruned until
  a reconcile gets through. `applyErrors(ApplyErrorPolicy.FAIL_COMPONENT)` makes it fail alone instead, on the
  reconciler for all components or on one component the rest can do without:

  ```java
  app.component(name, ServiceMonitor.class, this::monitor).applyErrors(ApplyErrorPolicy.FAIL_COMPONENT);
  ```

### 11. Running in production

**Observability.** kjucon logs nothing; it reports, and the application decides where things go.
* `ControllerObserver` (`Operator.Builder.observer`, or per controller) and `CompositionObserver`
  (`CompositionReconciler.Builder.observer`) have a no-op default for every event: the operator and controllers
  started and stopped, informers synced or failed, leadership, reconciles queued, started and finished with
  their duration, retries, finalizers, queue depth; components applied, waiting, changing readiness, pruned,
  orphaned, deleted. Logs and metrics hang here.
* `ErrorHandler` (`Operator.Builder.errorHandler`) is the one place a failure is told in full -
  `onReconcileError` for a failed reconcile or cleanup, `onError` for anything else dealt with on the way. The
  default, `StdErrErrorHandler`, prints to the standard error with the stack trace of a resource's first
  failure only. An application with a logging framework gives the operator a handler which logs.

**Health.** `Operator.health()` has `isLive()` and `isReady()`: informers synced and not failed, leadership,
and with `apiServerProbe(Duration)` the API server reachable. Reconcile errors never make an operator
unhealthy. The examples' `HealthServer` serves `/healthz` and `/readyz` with the JDK's HTTP server.

**Leader election.** With more than one replica:

```java
Operator.builder().leaderElection(LeaderElection.lease("demo", "greetings-operator"))
```

Only the leader runs the controllers; the others keep their informers warm. A leader which loses its lease
stops its controllers and exits with status 1 to be restarted, as controller-runtime does;
`exitOnLost(false)` keeps it up, not live. The operator needs the rights to the Lease, which is checked at
start.

<a id="renaming"></a>**Renaming.** The controller name is in the objects, so renaming needs care:
* a controller: list the old name with `formerNames("old")`. What it applied under the old name is its own -
  pruned, cleaned up, its fields handed over to the new field manager - and the old finalizer is replaced;
* a finalizer, or disabling it: list the old one with `formerFinalizers(...)`, or a deleted resource keeps it
  and stays in `Terminating`. Switching a composition to `CleanupMode.GARBAGE_COLLECTOR` does this by itself.

**RBAC.** The operator needs to list and watch every type it watches in the watched namespaces, to patch its
primary resources, their `/status` and their finalizers, and to create, patch and delete what it composes.

**Limitations.**
* Events reach a controller only for the namespaces the operator watches, and a composition may place objects
  only there.
* Informers cache every object of a watched type, not only those the operator owns.
* Only one controller should write the status of a resource. Several controllers may compose the same
  resource: each prunes only what it manages.

### 12. Testing

The test fixtures of `kjucon-core` - not published, copy what you need - show how kjucon tests itself:
`MockKubernetes` runs fabric8's in-memory API server (`io.fabric8:kubernetes-server-mock`) extended to accept
server-side apply as an upsert, and `Await` waits for a condition:

```java
MockKubernetes kubernetes = new MockKubernetes();
Operator.Builder builder = Operator.builder().client(kubernetes.client()).watchNamespace(Fixtures.NAMESPACE);
builder.controller(WebApp.class, reconciler).name("webapps");
try (Operator operator = Await.started(builder.build())) {
    kubernetes.client().resource(webApp).create();
    Await.until(() -> configMap("hello-page") != null, "the page");
}
```

`Fixtures` holds what the tests share, `Await.started` starts an operator and waits until it is ready, and
`RecordingErrorHandler` keeps what the operator reports for the test to look at.

The mock checks flows - what is applied, pruned, deleted, in which order - not the semantics only a real API
server has: field ownership, garbage collection, the status subresource, validation. `refuseApply(path, code)`
makes it refuse an apply, to test error handling. For the rest, the example's integration tests run against
k3s in Docker: `./gradlew :kjucon-example:intTest`.

### 13. Reference

**Concepts**, next to controller-runtime and the Java Operator SDK:

| kjucon | controller-runtime | JOSDK | what it is |
|---|---|---|---|
| `Operator` | `Manager` | `Operator` | controllers sharing a client, informers and a leader election |
| `Controller` | `Controller` | controller | the reconcile loop of one primary type |
| `Reconciler` | `Reconciler` | `Reconciler` | `reconcile` and `cleanup` of one resource |
| `ReconcileResult` | `Result` | `UpdateControl` | success, requeue, requeue after, error |
| `owns(type)` / `watches(type, mapper)` | `Owns()` / `Watches()` | `InformerEventSource` | secondary events waking the owner |
| `EventSource` | `source.Channel` | external event source | events Kubernetes does not know about |
| `Composition` / `Component` | - | `Workflow` / `DependentResource` | the objects a resource is composed of |
| `dependsOn` / `readyWhen` | - | `dependsOn` / `readyPostcondition` | ordering and readiness of components |
| `ReadinessChecks` | kstatus | condition classes | how ready an object is |
| `StandardStatus` | `metav1.Condition` | - | phase, conditions, observedGeneration |

**How kjucon writes.** Server-side apply does not cover every write a controller makes, so each operation uses
the method which does what it needs on a real API server:

| operation | method | why |
|---|---|---|
| desired state of an owned object | server-side apply, forced, field manager = controller name | removes what is no longer applied, keeps what other managers own, takes back what they changed of ours |
| status of the primary resource | compared with the cache first; if it changed, a fresh read, then server-side apply of the status alone on `/status` | the whole status is the controller's: a message or condition no longer set disappears, and nothing but the status becomes its field manager's |
| finalizers | JSON patch: `test` of the list as read, then `replace` (the first finalizer: merge patch with `resourceVersion`) | the test keeps finalizers of others from being lost to a concurrent change of the list, while a write of anything else does not fail it |
| release of an orphan | JSON patch | removes the owner reference, and the labels and annotations the controller set itself |
| prune and cleanup | delete | |

Each of these is exercised against k3s by `WritePathsIntTest`.

**Defaults.**

| setting | default |
|---|---|
| `Operator.Builder.labelDomain` | `kjucon.green4j.github.io` |
| namespaces | the kubeconfig context's, or all |
| `ControllerBuilder.name` | the lowercase kind |
| `workers` | 1 |
| `finalizer` | on, `<domain>/<name>` |
| `filter` | generation, labels or annotations changed |
| `retry` | exponential, 500 ms to 5 minutes, with jitter |
| `resyncPeriod` | none |
| `CompositionReconciler.Builder.resync` / `progressResync` | 5 minutes / 30 seconds |
| `cleanup` | `CleanupMode.ORDERED` |
| `applyErrors` | `ApplyErrorPolicy.STOP_COMPOSITION` |
| `Component.deletePolicy` | `DeletePolicy.DELETE` |
