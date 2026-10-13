/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.webapp;

/**
 * The spec of a {@link WebApp}: a web server serving one page.
 */
public class WebAppSpec {
    private String image = "nginx:alpine";
    private int replicas = 1;
    private String message = "Hello from kjucon";

    public String getImage() {
        return image;
    }

    public void setImage(final String image) {
        this.image = image;
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(final int replicas) {
        this.replicas = replicas;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }
}
