import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class explore {

    private static final String BHAVCOPY_ENDPOINT = "https://mcp.nseindia.in/bhavcopy/cm/mcp";
    private static final String CM_MARKET_ENDPOINT = "https://mcp.nseindia.in/cmmkt/mcp";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {
        explore tester = new explore();

        System.out.println("==================================================");
        System.out.println("Testing NSE Capital Market Live MCP (cm-market)");
        System.out.println("Endpoint: " + CM_MARKET_ENDPOINT);
        System.out.println("==================================================");
        tester.testEndpoint(CM_MARKET_ENDPOINT, "cm_get_stock_quote", "{\"symbol\": \"RELIANCE\"}");

        System.out.println("\n==================================================");
        System.out.println("Testing NSE Bhavcopy Historical MCP (nse-bhavcopy)");
        System.out.println("Endpoint: " + BHAVCOPY_ENDPOINT);
        System.out.println("==================================================");
        tester.testEndpoint(BHAVCOPY_ENDPOINT, "moving_average", "{\"symbol\": \"TCS\", \"days\": 20}");
    }

    public void testEndpoint(String endpoint, String sampleToolName, String sampleArgsJson) {
        try {
            // Step 1: Initialize MCP Session
            String initPayload = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {
                      "name": "Java-MCP-Client",
                      "version": "1.0.0"
                    }
                  }
                }
                """;

            HttpRequest initRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(initPayload))
                    .build();

            HttpResponse<String> initResponse = client.send(initRequest, HttpResponse.BodyHandlers.ofString());

            System.out.println("[1] Initialize Status: " + initResponse.statusCode());
            String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
            System.out.println("    Session ID: " + sessionId);

            if (sessionId == null) {
                System.err.println("Failed to obtain mcp-session-id. Response: " + initResponse.body());
                return;
            }

            // Step 2: Send initialized notification
            String notifyPayload = """
                {
                  "jsonrpc": "2.0",
                  "method": "notifications/initialized"
                }
                """;
            HttpRequest notifyRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("mcp-session-id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(notifyPayload))
                    .build();
            client.send(notifyRequest, HttpResponse.BodyHandlers.discarding());
            System.out.println("[2] Initialized notification sent.");

            // Step 3: Call a Tool
            String toolCallPayload = String.format("""
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "%s",
                    "arguments": %s
                  }
                }
                """, sampleToolName, sampleArgsJson);

            HttpRequest toolRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("mcp-session-id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(toolCallPayload))
                    .build();

            HttpResponse<String> toolResponse = client.send(toolRequest, HttpResponse.BodyHandlers.ofString());
            System.out.println("[3] Tool Call ('" + sampleToolName + "') Status: " + toolResponse.statusCode());
            System.out.println("    Response Payload:\n" + toolResponse.body());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

