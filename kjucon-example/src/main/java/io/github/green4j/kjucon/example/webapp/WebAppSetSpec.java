/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.webapp;

import java.util.ArrayList;
import java.util.List;

/**
 * The spec of a {@link WebAppSet}: the applications, by name.
 */
public class WebAppSetSpec {
    private List<App> apps = new ArrayList<>();

    public List<App> getApps() {
        return apps;
    }

    public void setApps(final List<App> apps) {
        this.apps = apps != null ? apps : new ArrayList<>();
    }

    /**
     * One application of the set.
     */
    public static class App extends WebAppSpec {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }
}
