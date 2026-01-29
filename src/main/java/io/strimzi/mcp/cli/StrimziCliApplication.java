package io.strimzi.mcp.cli;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI client application that calls the Strimzi MCP server REST API.
 */
@TopCommand
@Command(
    name = "strimzi-cli",
    description = "Strimzi Kafka management CLI client",
    mixinStandardHelpOptions = true,
    subcommands = {
        ChatCliCommand.class
    }
)
public class StrimziCliApplication {

    @Option(names = {"-s", "--server"}, description = "Server URL (default: http://localhost:8080)")
    String serverUrl = "http://localhost:8080";

    @Option(names = {"-v", "--verbose"}, description = "Verbose output")
    boolean verbose;

    public String getServerUrl() {
        return serverUrl;
    }

    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Run CLI without Quarkus context.
     */
    public int runCli(String[] args) {
        CommandLine cmd = new CommandLine(this);
        return cmd.execute(args);
    }
}