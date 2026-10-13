/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.greeting;

/**
 * The spec of a {@link Greeting}.
 */
public class GreetingSpec {
    private String to;

    /**
     * @return whom to greet
     */
    public String getTo() {
        return to;
    }

    /**
     * @param to whom to greet
     */
    public void setTo(final String to) {
        this.to = to;
    }
}
