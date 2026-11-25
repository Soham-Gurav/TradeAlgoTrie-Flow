import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable client for communicating with NSE MCP Servers over Streamable HTTP.
 */
public class NseMcpClient {

    public static final String BHAVCOPY_URL = "https://mcp.nseindia.in/bhavcopy/cm/mcp";
    public static final String CM_MARKET_URL = "https://mcp.nseindia.in/cmmkt/mcp";

    private final String endpointUrl;
    private final HttpClient httpClient;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private String sessionId;

    public NseMcpClient(String endpointUrl) {
        this.endpointUrl = endpointUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Factory method for Live Capital Market data (Quotes, Gainers/Losers, Indices)
     */
    public static NseMcpClient liveMarket() {
        return new NseMcpClient(CM_MARKET_URL);
    }

    /**
     * Factory method for Historical Bhavcopy data (OHLCV, Moving Averages, 52w High/Low)
     */
    public static NseMcpClient bhavcopy() {
        return new NseMcpClient(BHAVCOPY_URL);
    }

    /**
     * Ensures an active MCP session handshake exists.
     */
    public synchronized void connect() throws IOException, InterruptedException {
        if (sessionId != null) {
            return; // already connected
        }

        // 1. Send initialize request
        String initPayload = String.format("""
            {
              "jsonrpc": "2.0",
              "id": %d,
              "method": "initialize",
              "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": { "name": "NseJavaClient", "version": "1.0.0" }
              }
            }
            """, requestId.getAndIncrement());

        HttpRequest initReq = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initPayload))
                .build();

        HttpResponse<String> initRes = httpClient.send(initReq, HttpResponse.BodyHandlers.ofString());
        if (initRes.statusCode() != 200) {
            throw new IOException("Failed to initialize MCP: HTTP " + initRes.statusCode() + " - " + initRes.body());
        }

        this.sessionId = initRes.headers().firstValue("mcp-session-id")
                .orElseThrow(() -> new IOException("Missing 'mcp-session-id' header from MCP server"));

        // 2. Send initialized notification
        HttpRequest notifyReq = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Content-Type", "application/json")
                .header("mcp-session-id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .build();

        httpClient.send(notifyReq, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Calls an arbitrary MCP tool on this server.
     *
     * @param toolName The name of the tool (e.g., "cm_get_stock_quote", "moving_average")
     * @param jsonArgs JSON string of arguments (e.g. "{\"symbol\":\"RELIANCE\"}")
     * @return Raw text/JSON result returned by the tool
     */
    public String callTool(String toolName, String jsonArgs) throws IOException, InterruptedException {
        connect();

        String payload = String.format("""
            {
              "jsonrpc": "2.0",
              "id": %d,
              "method": "tools/call",
              "params": {
                "name": "%s",
                "arguments": %s
              }
            }
            """, requestId.getAndIncrement(), toolName, jsonArgs == null || jsonArgs.isBlank() ? "{}" : jsonArgs);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("mcp-session-id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Tool call failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        return extractContentText(response.body());
    }

    // --- Helper Convenience Methods for CM Market ---

    public String getStockQuote(String symbol) throws IOException, InterruptedException {
        return callTool("cm_get_stock_quote", String.format("{\"symbol\": \"%s\"}", symbol));
    }

    public String getLiveMarketData(String variation) throws IOException, InterruptedException {
        // variation: "gainers" or "loosers"
        return callTool("cm_get_live_market_data", String.format("{\"index\": \"%s\"}", variation));
    }

    public String getLiveIndices() throws IOException, InterruptedException {
        return callTool("cm_get_live_indices", "{}");
    }

    // --- Helper Convenience Methods for Bhavcopy ---

    public String getMovingAverage(String symbol, int days) throws IOException, InterruptedException {
        return callTool("moving_average", String.format("{\"symbol\": \"%s\", \"days\": %d}", symbol, days));
    }

    public double getMovingAverageDouble(String symbol, int days) throws IOException, InterruptedException {
        String val = getMovingAverage(symbol, days);
        return Double.parseDouble(val.trim());
    }

    public String get52WeekHighLow(String symbol) throws IOException, InterruptedException {
        return callTool("get_52_week_high_low", String.format("{\"symbol\": \"%s\"}", symbol));
    }

    public String getTopMovers(String date, int n) throws IOException, InterruptedException {
        return callTool("get_top_movers", String.format("{\"date\": \"%s\", \"n\": %d}", date, n));
    }

    public String getStockHistory(String symbol, int months, String endDate) throws IOException, InterruptedException {
        // months: max 3 per chunk; endDate: 'today' or 'yyyy-MM-dd'
        return callTool("get_stock_history", String.format("{\"symbol\": \"%s\", \"months\": %d, \"endDate\": \"%s\"}", symbol, months, endDate));
    }

    public String getIndexHistory(String indexName, int months, String endDate) throws IOException, InterruptedException {
        // months: max 12 per chunk; indexName: e.g. "NIFTY 50"
        return callTool("get_index_history", String.format("{\"indexName\": \"%s\", \"months\": %d, \"endDate\": \"%s\"}", indexName, months, endDate));
    }

    /**
     * Extracts text content payload from MCP SSE response
     */
    private static String extractContentText(String rawBody) {
        // Look for "text":"..." inside MCP result content
        Pattern pattern = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(rawBody);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return rawBody;
    }

    private static String unescapeJson(String input) {
        return input.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
    }
}

