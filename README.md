## Kubernetes Java Controllers (kjucon)

A minimalistic framework for Kubernetes controllers on [fabric8](https://github.com/fabric8io/kubernetes-client),
built to:
* Make the reconcile loop a solved problem. Informers are shared, the work queue has client-go semantics,
  retries back off per resource with jitter, finalizers are handled, leader election runs on a Lease, and one
  namespace, several or all of them are watched.
* Turn a hierarchy of resources into a declaration. A custom resource is *composed* of the objects it owns:
  components applied in dependency order with server-side apply, judged ready the way kstatus judges it,
  pruned when they leave the composition and deleted in reverse order. A component can itself be a custom
  resource composed by another controller - hierarchies of any depth.
* Depend on nothing but `kubernetes-client-api`. kjucon logs nothing: it reports to observers and an error
  handler the application provides.

### Quick start

A `WebApp` custom resource is composed of a ConfigMap with its page, a Deployment rolling whenever the page
changes, and a Service applied once the Deployment is available:

```java
CompositionFactory<WebApp> webApps = context -> {
    String name = context.primary().getMetadata().getName();
    WebAppSpec spec = context.primary().getSpec();

    Composition.Builder<WebApp> app = Composition.builder();
    Component<WebApp, ConfigMap> page = app.component(name + "-page", ConfigMap.class,
            (ctx, meta) -> page(meta, spec));
    Component<WebApp, Deployment> server = app.component(name, Deployment.class,
            (ctx, meta) -> deployment(meta, spec)).rolloutOn(page);
    app.component(name, Service.class, (ctx, meta) -> service(meta)).dependsOn(server);
    return app.build();
};
```

```java
Operator.Builder builder = Operator.builder().client(client).watchNamespace("apps");

builder.controller(WebApp.class,
        CompositionReconciler.builder(webApps)
                .manages(ConfigMap.class, Deployment.class, Service.class)
                .build());

try (Operator operator = builder.build()) {
    operator.start();                                // returns at once; the controllers run in the background
    ...
}
```

The status of a `WebApp` - `Pending` with what is awaited, `Ready`, `Error` with what failed, `Deleting` -
follows its composition, with the `Ready`, `Reconciling` and `Stalled` conditions and `observedGeneration`.

The [Quick start](docs/quick-start.md) runs this end to end in a few minutes. The
[User manual](docs/user-manual.md) goes from a plain reconciler to hierarchies of compositions and running
them in production.

Nothing needs a custom resource or a composition: a controller of ConfigMaps, Secrets or anything else the API
server serves, writing to another cluster or outside Kubernetes, is a `Reconciler` on `kjucon-core` alone.
[kjucon-example](kjucon-example) has one of each, from the plainest up, each with a main class of its own: a
`Greeting` resource kept by a plain reconciler, a ConfigMap mirror with no custom resource, and a `WebAppSet`
composed of `WebApp`s composed of Kubernetes objects.

### Binaries

Binaries for Maven, Ivy, Gradle, and others can be found at
https://central.sonatype.com/search?q=g:io.github.green4j.

Example for Maven:

```xml
<dependency>
    <groupId>io.github.green4j</groupId>
    <artifactId>kjucon-all</artifactId>
    <version>${kjucon.version}</version>
</dependency>
```

Three artifacts are published, all under `io.github.green4j`:

| artifact | what it is |
|---|---|
| `kjucon-core` | operator, controllers, work queue, informers, status, utilities |
| `kjucon-compose` | compositions, components, readiness. Brings `kjucon-core` with it |
| `kjucon-all` | the two above in one jar, plus a ready-to-use fabric8 client: `kubernetes-client` with the JDK HTTP transport |

### Dependencies

kjucon is built for Java 17, the baseline of fabric8 8. `kjucon-core` depends on
`io.fabric8:kubernetes-client-api` and on nothing else: no logging framework, no metrics library, no HTTP
server, no annotation processing.

kjucon never builds a client: it is given one. The application picks the client implementation,
`io.fabric8:kubernetes-client`, and its transport - `kubernetes-httpclient-jdk`, `-vertx-5`, `-okhttp` or
`-jetty`. `kjucon-all` makes that choice for it.

### Building

```bash
./gradlew build                       # unit tests against an in-memory API server, Checkstyle, JaCoCo
./gradlew :kjucon-example:intTest     # the examples against k3s in Docker
```

### Documentation

- [docs/quick-start.md](docs/quick-start.md) - a first operator, from the dependency to `kubectl get`.
- [docs/user-manual.md](docs/user-manual.md) - a tutorial from a plain reconciler to production, with the
  reference of what kjucon writes and why.
- [kjucon-example](kjucon-example) - the three examples of the manual, with their integration tests on k3s.
