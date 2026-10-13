## Quick start

A first operator: a `WebApp` custom resource which becomes a web server serving a page. It takes a cluster
you can reach with `kubectl` (kind, k3d, minikube or Docker Desktop do), Java 17 and Gradle or Maven.

### 1. The dependency

```groovy
dependencies {
    implementation "io.github.green4j:kjucon-all:${kjuconVersion}"
}
```

`kjucon-all` brings the framework and a fabric8 client with the JDK HTTP transport. To pick the client and its
transport yourself, depend on `kjucon-compose` and add `io.fabric8:kubernetes-client` with a transport.

### 2. The custom resource

The spec is a plain bean; the status is kjucon's `StandardStatus`, which the framework keeps up to date:

```java
@Group("demo.example.com")
@Version("v1")
public class WebApp extends CustomResource<WebAppSpec, StandardStatus> implements Namespaced {
}

public class WebAppSpec {
    private String message = "Hello from kjucon";
    private int replicas = 1;
    // getters and setters
}
```

Its CRD - with the status subresource, which kjucon writes the status through:

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: webapps.demo.example.com
spec:
  group: demo.example.com
  scope: Namespaced
  names: {kind: WebApp, plural: webapps, singular: webapp}
  versions:
    - name: v1
      served: true
      storage: true
      subresources:
        status: {}
      additionalPrinterColumns:
        - {name: Phase, type: string, jsonPath: .status.phase}
        - {name: Message, type: string, jsonPath: .status.message}
      schema:
        openAPIV3Schema:
          type: object
          x-kubernetes-preserve-unknown-fields: true
```

### 3. The composition

What a `WebApp` is made of. Each component names an object, and its factory builds the desired state on the
metadata it is given - name, namespace, owner reference and labels are already there:

```java
public class WebAppComposition implements CompositionFactory<WebApp> {
    @Override
    public Composition<WebApp> create(ReconcileContext<WebApp> context) {
        String name = context.primary().getMetadata().getName();
        WebAppSpec spec = context.primary().getSpec();

        Composition.Builder<WebApp> app = Composition.builder();
        Component<WebApp, ConfigMap> page = app.component(name + "-page", ConfigMap.class,
                (ctx, meta) -> new ConfigMapBuilder().withMetadata(meta)
                        .addToData("index.html", "<h1>" + spec.getMessage() + "</h1>")
                        .build());
        Component<WebApp, Deployment> server = app.component(name, Deployment.class,
                (ctx, meta) -> new DeploymentBuilder().withMetadata(meta)
                        .withNewSpec()
                            .withReplicas(spec.getReplicas())
                            .withNewSelector().addToMatchLabels("app", name).endSelector()
                            .withNewTemplate()
                                .withNewMetadata().addToLabels("app", name).endMetadata()
                                .withNewSpec()
                                    .addNewContainer().withName("web").withImage("nginx:alpine")
                                        .addNewPort().withContainerPort(80).endPort()
                                        .addNewVolumeMount().withName("page")
                                            .withMountPath("/usr/share/nginx/html").endVolumeMount()
                                    .endContainer()
                                    .addNewVolume().withName("page")
                                        .withNewConfigMap().withName(name + "-page").endConfigMap().endVolume()
                                .endSpec()
                            .endTemplate()
                        .endSpec().build())
                .rolloutOn(page);                    // a changed page rolls the pods
        app.component(name, Service.class,
                (ctx, meta) -> new ServiceBuilder().withMetadata(meta)
                        .withNewSpec().addToSelector("app", name)
                            .addNewPort().withPort(80).withNewTargetPort(80).endPort()
                        .endSpec().build())
                .dependsOn(server);                  // applied once the Deployment is available
        return app.build();
    }
}
```

### 4. The operator

```java
public static void main(String[] args) throws InterruptedException {
    try (KubernetesClient client = new KubernetesClientBuilder().build()) {
        Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace("default")
                .labelDomain("demo.example.com");

        builder.controller(WebApp.class,
                        CompositionReconciler.builder(new WebAppComposition())
                                .manages(ConfigMap.class, Deployment.class, Service.class)
                                .build())
                .name("webapps");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown));
        try (Operator operator = builder.build()) {
            operator.start();
            stopped.await();
        }
    }
}
```

`manages(...)` lists every type a composition may hold: the controller watches them, so a change of any
object wakes its `WebApp` up, and prune and cleanup look at them. `labelDomain` prefixes the labels,
annotations and finalizer kjucon puts on what it manages.

### 5. Run it

```bash
kubectl apply -f webapp-crd.yaml
# start the main class from the IDE or with your build tool; it uses the current kubeconfig context

kubectl apply -f - <<EOF
apiVersion: demo.example.com/v1
kind: WebApp
metadata:
  name: hello
spec:
  message: Hello, kjucon
  replicas: 2
EOF

kubectl get webapps -w
# NAME    PHASE     MESSAGE
# hello   Pending   Deployment/hello: ...
# hello   Ready
```

Now play with it:
* `kubectl edit webapp hello` and change the message: the ConfigMap changes and the pods roll.
* `kubectl delete deployment hello`: it is back within a second.
* `kubectl delete webapp hello`: the Service, the Deployment and the ConfigMap go, in that order, and only then
  the `WebApp`.

### Next

The [User manual](user-manual.md) starts again from a plain reconciler and builds up to hierarchies,
ownership and production concerns. The `webapp` package of [kjucon-example](../kjucon-example) is this
operator with one more level - a `WebAppSet` of `WebApp`s - a health endpoint and observers printing what
happens; its other packages are a plain reconciler and a controller with no custom resource.
