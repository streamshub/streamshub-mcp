package io.strimzi.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main application class.
 * Starts the Strimzi MCP server with optional LLM functionality.
 */
@QuarkusMain
public class StrimziMcpApplication {

    public static void main(String... args) {
        Quarkus.run();
    }
}