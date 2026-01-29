package io.strimzi.mcp.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;

/**
 * Interactive chat CLI command that calls the server REST API.
 */
@Command(
    name = "chat",
    description = "Start interactive chat with Strimzi assistant or send a single message",
    mixinStandardHelpOptions = true
)
public class ChatCliCommand implements Callable<Integer> {

    @ParentCommand
    StrimziCliApplication parent;

    @Parameters(
        index = "0..*",
        arity = "0..*",
        description = "Message to send (if not provided, starts interactive mode)"
    )
    String[] message;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        // Check if server is running
        if (!checkServerHealth()) {
            System.err.println("❌ Server not running at " + parent.getServerUrl());
            System.err.println("Start the server first:");
            System.err.println("  mvn quarkus:dev");
            return 1;
        }

        if (message != null && message.length > 0) {
            // Single message mode
            String messageText = String.join(" ", message);
            return sendMessage(messageText);
        } else {
            // Interactive mode
            return interactiveChat();
        }
    }

    private boolean checkServerHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(parent.getServerUrl() + "/api/chat/health"))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private Integer sendMessage(String messageText) {
        try {
            if (parent.isVerbose()) {
                System.out.println("🔧 Server: " + parent.getServerUrl());
                System.out.println();
            }

            System.out.println("💬 User: " + messageText);
            System.out.println();

            String response = callChatApi(messageText);

            System.out.println("🤖 Assistant:");
            System.out.println(response);
            System.out.println();

            return 0;

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            if (parent.isVerbose()) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private Integer interactiveChat() {
        System.out.println("🚀 Strimzi Interactive Chat");
        System.out.println("Server: " + parent.getServerUrl());
        System.out.println("Type 'exit' or 'quit' to end the chat, 'help' for commands");
        System.out.println("─".repeat(60));
        System.out.println();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            try {
                System.out.print("💬 You: ");
                String input = reader.readLine();

                if (input == null || input.trim().equalsIgnoreCase("exit") || input.trim().equalsIgnoreCase("quit")) {
                    System.out.println("👋 Goodbye!");
                    break;
                }

                if (input.trim().isEmpty()) {
                    continue;
                }

                if (input.trim().equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }

                if (input.trim().equalsIgnoreCase("clear")) {
                    // Clear screen (works on most terminals)
                    System.out.print("\033[2J\033[H");
                    continue;
                }

                System.out.println();
                String response = callChatApi(input.trim());

                System.out.println("🤖 Assistant:");
                System.out.println(response);
                System.out.println();

            } catch (IOException e) {
                System.err.println("❌ IO Error: " + e.getMessage());
                return 1;
            } catch (Exception e) {
                System.err.println("❌ Error: " + e.getMessage());
                if (parent.isVerbose()) {
                    e.printStackTrace();
                }
                System.out.println();
            }
        }

        return 0;
    }

    private void printHelp() {
        System.out.println();
        System.out.println("🛠️  Available Commands:");
        System.out.println("  help   - Show this help");
        System.out.println("  clear  - Clear the screen");
        System.out.println("  exit   - Exit chat mode");
        System.out.println("  quit   - Exit chat mode");
        System.out.println();
        System.out.println("💡 Example questions:");
        System.out.println("  • What Kafka pods are running in the kafka namespace?");
        System.out.println("  • Show me operator logs from default namespace");
        System.out.println("  • Check operator status in kafka namespace");
        System.out.println("  • Help me troubleshoot my Kafka cluster");
        System.out.println();
    }

    /**
     * Call the chat REST API.
     */
    private String callChatApi(String message) throws Exception {
        // Create request body
        String requestBody = mapper.writeValueAsString(new ChatApiRequest(message));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(parent.getServerUrl() + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Server error: " + response.statusCode() + " - " + response.body());
        }

        // Parse response
        ChatApiResponse apiResponse = mapper.readValue(response.body(), ChatApiResponse.class);
        return apiResponse.response();
    }

    // Simple DTOs for API calls
    private record ChatApiRequest(String message) {}
    private record ChatApiResponse(String response, String provider, String timestamp) {}
}