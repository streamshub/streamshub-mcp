package io.strimzi.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.strimzi.mcp.cli.StrimziCliApplication;

/**
 * Main application class.
 *
 * Server mode (default): Starts REST API + MCP server
 * CLI mode: When --cli flag is passed
 */
@QuarkusMain
public class StrimziMcpApplication {

    public static void main(String... args) {
        // Check if CLI mode
        if (args.length > 0 && "--cli".equals(args[0])) {
            // CLI mode - run CLI client
            String[] cliArgs = new String[args.length - 1];
            System.arraycopy(args, 1, cliArgs, 0, cliArgs.length);

            // Run CLI directly without Quarkus
            System.exit(new StrimziCliApplication().runCli(cliArgs));
        } else {
            // Server mode
            System.out.println("🚀 Starting Strimzi MCP & Chat Server");
            System.out.println();
            System.out.println("📡 Endpoints available at:");
            System.out.println("  • Chat API:    http://localhost:8080/api/chat");
            System.out.println("  • MCP Server:  http://localhost:8080/mcp");
            System.out.println("  • Health:      http://localhost:8080/api/chat/health");
            System.out.println();
            System.out.println("💡 Use Ctrl+C to stop the server");
            System.out.println("─".repeat(60));

            Quarkus.run();
        }
    }
}