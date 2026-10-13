## kjucon examples

Three controllers, from the plainest up, each in a package with a main class of its own. They follow the
chapters of the [user manual](../docs/user-manual.md), run against the current kubeconfig context, and are
tested on k3s by `./gradlew :kjucon-example:intTest`.

| package | what it is | run |
|---|---|---|
| [greeting](src/main/java/io/github/green4j/kjucon/example/greeting) | one custom resource, `Greeting`, kept by a plain `Reconciler` on `kjucon-core` alone | `./gradlew :kjucon-example:runGreetings --args='--install-crds'` |
| [mirror](src/main/java/io/github/green4j/kjucon/example/mirror) | a controller with no custom resource: ConfigMaps labelled `mirror=true` copied to a namespace of the same or another cluster | `./gradlew :kjucon-example:runMirror --args='--to mirrored [--target-context other]'` |
| [webapp](src/main/java/io/github/green4j/kjucon/example/webapp) | two levels of composition: a `WebAppSet` of `WebApp`s, each a ConfigMap, a Deployment and a Service | `./gradlew :kjucon-example:runWebApps --args='--install-crds'` |

Then apply the samples of the example, for instance:

```bash
kubectl apply -f kjucon-example/src/main/resources/samples/greeting/
kubectl get greetings
```

What they share is in the root package: the label domain, running an operator until Ctrl+C, the health
endpoints on `:8081/healthz` and `/readyz`, and observers printing what happens.
