/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main application class.
 * Starts the Strimzi MCP server with optional LLM functionality.
 */
@QuarkusMain
public class StrimziMcpApplication {

    private StrimziMcpApplication() {
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String... args) {
        Quarkus.run();
    }
}
