///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Scanner;

/**
 * Simple interactive chat client for Strimzi MCP server.
 *
 * Usage:
 *   jbang strimzi-cli.java                              # Interactive chat
 *   jbang strimzi-cli.java "Show me all clusters"      # Single message
 *   jbang strimzi-cli.java --server http://remote:8080 # Custom server
 */
class StrimziChatCli {

    private final String serverUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public StrimziChatCli(String serverUrl) {
        this.serverUrl = serverUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public static void main(String... args) {
        String serverUrl = "http://localhost:8080";
        String message = null;

        // Simple argument parsing
        for (int i = 0; i < args.length; i++) {
            if ("--server".equals(args[i]) && i + 1 < args.length) {
                serverUrl = args[++i];
            } else if (args[i].startsWith("--")) {
                // Skip other options
            } else if (message == null) {
                message = String.join(" ", java.util.Arrays.copyOfRange(args, i, args.length));
                break;
            }
        }

        StrimziChatCli cli = new StrimziChatCli(serverUrl);

        if (message != null) {
            // Single message mode
            cli.sendMessage(message);
        } else {
            // Interactive mode
            cli.startInteractiveMode();
        }
    }

    private void startInteractiveMode() {
        System.out.println("🤖 Strimzi Chat CLI");
        System.out.println("Server: " + serverUrl);
        System.out.println("Type your message or 'exit' to quit");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("You: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            if ("exit".equals(input.toLowerCase()) || "quit".equals(input.toLowerCase())) {
                System.out.println("👋 Goodbye!");
                break;
            }

            sendMessage(input);
            System.out.println();
        }

        scanner.close();
    }

    private void sendMessage(String message) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("message", message);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseData = objectMapper.readValue(response.body(), Map.class);
                String chatResponse = (String) responseData.get("response");

                System.out.println();
                System.out.println("🤖 " + chatResponse);

            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorData = objectMapper.readValue(response.body(), Map.class);
                String error = (String) errorData.getOrDefault("message", "Unknown error");

                System.err.println("❌ " + error);
                if (response.statusCode() == 503) {
                    System.err.println("   Hint: Make sure ENABLE_LLM=true and provider is configured");
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Connection failed: " + e.getMessage());
            System.err.println("   Make sure server is running at: " + serverUrl);
        }
    }
}