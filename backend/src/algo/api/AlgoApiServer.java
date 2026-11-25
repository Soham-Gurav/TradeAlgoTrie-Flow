package algo.api;

import algo.bloom.OrderFlowBloomFilter;
import algo.flow.OrderFlowNetwork;
import algo.stats.RandomizedQuickselect;
import algo.test.AlgorithmTestSuite;
import algo.trie.DoubleTriePredictor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-Performance Embedded Java REST API Server.
 * Exposes all 4 assessed advanced algorithms to the Next.js frontend over clean JSON endpoints.
 * 
 * Endpoints:
 * - GET  /api/health            -> System telemetry and algorithm metadata
 * - GET  /api/trie/predict      -> Solves Double Trie autofill on live market data
 * - GET  /api/flow/solve        -> Solves Edmonds-Karp Max-Flow and Min-Cut Dynamic Sink
 * - GET  /api/bloom/screen      -> Screens HFT trade sequence through Probabilistic Bloom Filter
 * - GET  /api/stats/benchmark   -> Executes empirical Randomized Quickselect vs Sort benchmark
 * - GET  /api/test/run          -> Executes complete 20-test academic correctness test suite
 */
public class AlgoApiServer {

    private static final int PORT = Integer.parseInt(System.getProperty("server.port", "8085"));
    private final OrderFlowNetwork flowEngine = new OrderFlowNetwork();
    private final OrderFlowBloomFilter bloomFilter = new OrderFlowBloomFilter(1000, 0.02);
    private final RandomizedQuickselect quickselect = new RandomizedQuickselect();
    private final AlgorithmTestSuite testSuite = new AlgorithmTestSuite();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AlgoApiServer() {
        // Pre-populate Bloom filter with known institutional spoofing signatures
        bloomFilter.insert("SPOOF_BID_ICEBERG_LARGE");
        bloomFilter.insert("STOP_HUNT_SWEEP_REVERSAL");
        bloomFilter.insert("LIQUIDITY_VACUUM_PUMP");
        bloomFilter.insert("LAYERED_WALL_CANCELLATION");
        bloomFilter.insert("DELTA_DIVERGENCE_TRAP");
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/trie/predict", new TriePredictHandler());
        server.createContext("/api/flow/solve", new FlowSolveHandler());
        server.createContext("/api/bloom/screen", new BloomScreenHandler());
        server.createContext("/api/stats/benchmark", new StatsBenchmarkHandler());
        server.createContext("/api/test/run", new TestRunHandler());

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        System.out.println("==========================================================================================");
        System.out.println("      JAVA REST API SERVER RUNNING ON http://localhost:" + PORT + "                       ");
        System.out.println("==========================================================================================");
        System.out.println("Endpoints available:");
        System.out.println("  * GET  /api/health");
        System.out.println("  * GET  /api/trie/predict?symbol=SOLUSDT&interval=15m&steps=5");
        System.out.println("  * GET  /api/flow/solve?symbol=SOLUSDT");
        System.out.println("  * GET  /api/bloom/screen?signature=SPOOF_BID_ICEBERG_LARGE");
        System.out.println("  * GET  /api/stats/benchmark?size=10000");
        System.out.println("  * GET  /api/test/run");
        System.out.println("==========================================================================================");
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            String json = """
            {
                "status": "ONLINE",
                "service": "Advanced Algorithm Real-World Trading Engine",
                "version": "2.0.0",
                "javaVersion": "%s",
                "syllabusUnitsCovered": ["Unit 4: Tries", "Unit 5: Max-Flow Min-Cut", "Unit 6: Bloom Filter (CO5)", "Unit 1: Quickselect"],
                "activeAlgorithms": 4,
                "timestamp": %d
            }
            """.formatted(System.getProperty("java.version"), System.currentTimeMillis());
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class TriePredictHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String symbol = params.getOrDefault("symbol", "SOLUSDT").toUpperCase();
            String interval = params.getOrDefault("interval", "15m");
            int steps = Integer.parseInt(params.getOrDefault("steps", "5"));

            try {
                String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=250", symbol, interval);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                List<DoubleTriePredictor.SimpleCandle> candles = parseKlines(resp.body());
                if (candles.size() < 20) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Insufficient market candle data\"}");
                    return;
                }

                DoubleTriePredictor predictor = new DoubleTriePredictor(5);
                predictor.train(candles);

                double[] atrs = calculateAtrs(candles, 14);
                double currentAtr = atrs[candles.size() - 1];

                List<DoubleTriePredictor.SimpleCandle> prefix = candles.subList(Math.max(0, candles.size() - 5), candles.size());
                DoubleTriePredictor.AutofillForecast forecast = predictor.autofill(prefix, currentAtr, steps);

                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"symbol\":\"").append(symbol).append("\",");
                json.append("\"interval\":\"").append(interval).append("\",");
                json.append("\"currentPrice\":").append(candles.get(candles.size() - 1).close()).append(",");
                json.append("\"currentAtr\":").append(currentAtr).append(",");
                json.append("\"prefixMatchCount\":").append(forecast.prefixMatchCount()).append(",");
                json.append("\"fallbackDepth\":").append(forecast.fallbackDepth()).append(",");
                json.append("\"cumulativeTargetPrice\":").append(forecast.cumulativeTargetPrice()).append(",");
                json.append("\"cumulativeReturnPct\":").append(forecast.cumulativeReturnPct()).append(",");
                json.append("\"overallConfidencePct\":").append(forecast.overallConfidencePct()).append(",");
                json.append("\"primaryVerdict\":\"").append(forecast.primaryVerdict()).append("\",");

                // Matched prefix
                json.append("\"matchedPrefix\":[");
                for (int i = 0; i < forecast.matchedPrefix().size(); i++) {
                    json.append("\"").append(forecast.matchedPrefix().get(i).display()).append("\"");
                    if (i < forecast.matchedPrefix().size() - 1) json.append(",");
                }
                json.append("],");

                // Predicted path
                json.append("\"predictedPath\":[");
                for (int i = 0; i < forecast.predictedPath().size(); i++) {
                    var p = forecast.predictedPath().get(i);
                    json.append("{");
                    json.append("\"step\":").append(p.stepIndex()).append(",");
                    json.append("\"time\":").append(p.predictedTimeSec()).append(",");
                    json.append("\"token\":\"").append(p.token().display()).append("\",");
                    json.append("\"open\":").append(p.open()).append(",");
                    json.append("\"high\":").append(p.high()).append(",");
                    json.append("\"low\":").append(p.low()).append(",");
                    json.append("\"close\":").append(p.close()).append(",");
                    json.append("\"expectedDeltaPct\":").append(p.expectedDeltaPct()).append(",");
                    json.append("\"confidencePct\":").append(p.confidencePct());
                    json.append("}");
                    if (i < forecast.predictedPath().size() - 1) json.append(",");
                }
                json.append("]");
                json.append("}");

                sendJsonResponse(exchange, 200, json.toString());
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class FlowSolveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String symbol = params.getOrDefault("symbol", "SOLUSDT").toUpperCase();

            try {
                // 1. Fetch depth
                String dUrl = String.format("https://api.binance.com/api/v3/depth?symbol=%s&limit=20", symbol);
                HttpRequest dReq = HttpRequest.newBuilder().uri(URI.create(dUrl)).header("User-Agent", "Mozilla/5.0").GET().build();
                HttpResponse<String> dResp = httpClient.send(dReq, HttpResponse.BodyHandlers.ofString());

                List<OrderFlowNetwork.BookLevel> bids = new ArrayList<>();
                List<OrderFlowNetwork.BookLevel> asks = new ArrayList<>();
                parseOrderBook(dResp.body(), bids, asks);

                // 2. Fetch aggTrades
                String tUrl = String.format("https://api.binance.com/api/v3/aggTrades?symbol=%s&limit=200", symbol);
                HttpRequest tReq = HttpRequest.newBuilder().uri(URI.create(tUrl)).header("User-Agent", "Mozilla/5.0").GET().build();
                HttpResponse<String> tResp = httpClient.send(tReq, HttpResponse.BodyHandlers.ofString());

                double buyVol = 0.0, sellVol = 0.0;
                Pattern pat = Pattern.compile("\"q\"\\s*:\\s*\"([0-9.]+)\"[^}]+?\"m\"\\s*:\\s*(true|false)");
                Matcher m = pat.matcher(tResp.body());
                while (m.find()) {
                    double q = Double.parseDouble(m.group(1));
                    if (Boolean.parseBoolean(m.group(2))) sellVol += q;
                    else buyVol += q;
                }

                double midPrice = (bids.isEmpty() || asks.isEmpty()) ? 100.0 : (bids.get(0).price() + asks.get(0).price()) / 2.0;
                var res = flowEngine.solveNetwork(midPrice, buyVol, sellVol, bids, asks);

                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"symbol\":\"").append(symbol).append("\",");
                json.append("\"midPrice\":").append(midPrice).append(",");
                json.append("\"directionType\":\"").append(res.directionType().name()).append("\",");
                json.append("\"title\":\"").append(res.directionType().title).append("\",");
                json.append("\"description\":\"").append(res.directionType().description).append("\",");
                json.append("\"targetPrice\":").append(res.targetPrice()).append(",");
                json.append("\"targetReturnPct\":").append(res.targetReturnPct()).append(",");
                json.append("\"netSinkPressure\":").append(res.netSinkPressure()).append(",");
                json.append("\"maxBuyFlow\":").append(res.maxBuyFlow()).append(",");
                json.append("\"maxSellFlow\":").append(res.maxSellFlow()).append(",");
                json.append("\"totalBuySupply\":").append(res.totalBuySupply()).append(",");
                json.append("\"totalSellSupply\":").append(res.totalSellSupply()).append(",");

                if (res.primaryBottleneck() != null) {
                    var b = res.primaryBottleneck();
                    json.append("\"bottleneck\":{");
                    json.append("\"wallName\":\"").append(b.wallName()).append("\",");
                    json.append("\"priceLevel\":").append(b.priceLevel()).append(",");
                    json.append("\"saturatedCapacity\":").append(b.saturatedCapacity()).append(",");
                    json.append("\"flowPushed\":").append(b.flowPushed()).append(",");
                    json.append("\"isBreached\":").append(b.isBreached());
                    json.append("},");
                }

                json.append("\"bids\":[");
                for (int i = 0; i < Math.min(bids.size(), 6); i++) {
                    json.append("{\"price\":").append(bids.get(i).price()).append(",\"qty\":").append(bids.get(i).quantity()).append("}");
                    if (i < Math.min(bids.size(), 6) - 1) json.append(",");
                }
                json.append("],");

                json.append("\"asks\":[");
                for (int i = 0; i < Math.min(asks.size(), 6); i++) {
                    json.append("{\"price\":").append(asks.get(i).price()).append(",\"qty\":").append(asks.get(i).quantity()).append("}");
                    if (i < Math.min(asks.size(), 6) - 1) json.append(",");
                }
                json.append("]");
                json.append("}");

                sendJsonResponse(exchange, 200, json.toString());
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class BloomScreenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String sig = params.getOrDefault("signature", "SPOOF_BID_ICEBERG_LARGE");
            var res = bloomFilter.screenPattern(sig);

            String json = """
            {
                "signature": "%s",
                "possiblyPresent": %b,
                "theoreticalFalsePositiveRate": %f,
                "matchingHashBuckets": %d,
                "recommendation": "%s",
                "bitArraySize": %d,
                "numHashFunctions": %d,
                "elementCount": %d,
                "capacityFillRatio": %f
            }
            """.formatted(
                    res.patternSignature(), res.possiblyPresent(), res.theoreticalFalsePositiveRate(),
                    res.matchingHashBuckets(), res.recommendation(), bloomFilter.getBitArraySize(),
                    bloomFilter.getNumHashFunctions(), bloomFilter.getElementCount(), bloomFilter.getCapacityFillRatio()
            );
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class StatsBenchmarkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            int size = Integer.parseInt(params.getOrDefault("size", "10000"));
            var bm = quickselect.benchmark(size, 0.95);

            String json = """
            {
                "arraySize": %d,
                "quantilePercentile": %f,
                "selectedValue": %f,
                "quickselectTimeNs": %d,
                "sortBaselineTimeNs": %d,
                "speedupFactor": %f
            }
            """.formatted(bm.arraySize(), bm.quantilePercentile(), bm.selectedValue(), bm.quickselectTimeNs(), bm.sortBaselineTimeNs(), bm.speedupFactor());
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class TestRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            var rep = testSuite.runAllTests();

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"totalTests\":").append(rep.totalTests()).append(",");
            json.append("\"passedTests\":").append(rep.passedTests()).append(",");
            json.append("\"failedTests\":").append(rep.failedTests()).append(",");
            json.append("\"passRatePct\":").append(rep.passRatePct()).append(",");
            json.append("\"results\":[");
            for (int i = 0; i < rep.results().size(); i++) {
                var r = rep.results().get(i);
                json.append("{");
                json.append("\"module\":\"").append(r.suiteName()).append("\",");
                json.append("\"name\":\"").append(r.testName()).append("\",");
                json.append("\"passed\":").append(r.passed()).append(",");
                json.append("\"details\":\"").append(r.details().replace("\"", "\\\"")).append("\",");
                json.append("\"latencyUs\":").append(r.executionTimeNs() / 1000.0);
                json.append("}");
                if (i < rep.results().size() - 1) json.append(",");
            }
            json.append("]");
            json.append("}");

            sendJsonResponse(exchange, 200, json.toString());
        }
    }

    private boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void sendJsonResponse(HttpExchange exchange, int code, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2) map.put(pair[0], pair[1]);
        }
        return map;
    }

    private static List<DoubleTriePredictor.SimpleCandle> parseKlines(String json) {
        List<DoubleTriePredictor.SimpleCandle> list = new ArrayList<>();
        Pattern rowPat = Pattern.compile("\\[([^\\[\\]]+)\\]");
        Matcher m = rowPat.matcher(json);
        while (m.find()) {
            String[] cols = m.group(1).split(",");
            if (cols.length >= 6) {
                long timeSec = Long.parseLong(cols[0].trim()) / 1000;
                double open = Double.parseDouble(cols[1].replace("\"", "").trim());
                double high = Double.parseDouble(cols[2].replace("\"", "").trim());
                double low = Double.parseDouble(cols[3].replace("\"", "").trim());
                double close = Double.parseDouble(cols[4].replace("\"", "").trim());
                double volume = Double.parseDouble(cols[5].replace("\"", "").trim());
                list.add(new DoubleTriePredictor.SimpleCandle(timeSec, open, high, low, close, volume));
            }
        }
        return list;
    }

    private static double[] calculateAtrs(List<DoubleTriePredictor.SimpleCandle> candles, int period) {
        double[] atrs = new double[candles.size()];
        if (candles.isEmpty()) return atrs;
        atrs[0] = candles.get(0).range();
        double trSum = 0.0;
        for (int i = 1; i < candles.size(); i++) {
            DoubleTriePredictor.SimpleCandle c = candles.get(i);
            DoubleTriePredictor.SimpleCandle p = candles.get(i - 1);
            double tr = Math.max(c.high() - c.low(), Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close())));
            if (i <= period) {
                trSum += tr;
                atrs[i] = trSum / i;
            } else {
                atrs[i] = (atrs[i - 1] * (period - 1) + tr) / period;
            }
        }
        return atrs;
    }

    private static void parseOrderBook(String json, List<OrderFlowNetwork.BookLevel> bids, List<OrderFlowNetwork.BookLevel> asks) {
        int bidsIdx = json.indexOf("\"bids\":");
        int asksIdx = json.indexOf("\"asks\":");
        if (bidsIdx == -1 || asksIdx == -1) return;

        String bidsPart = json.substring(bidsIdx, asksIdx);
        String asksPart = json.substring(asksIdx);

        Pattern pairPat = Pattern.compile("\\[\\s*\"([0-9.]+)\"\\s*,\\s*\"([0-9.]+)\"\\s*\\]");

        Matcher mb = pairPat.matcher(bidsPart);
        while (mb.find()) {
            bids.add(new OrderFlowNetwork.BookLevel(Double.parseDouble(mb.group(1)), Double.parseDouble(mb.group(2))));
        }

        Matcher ma = pairPat.matcher(asksPart);
        while (ma.find()) {
            asks.add(new OrderFlowNetwork.BookLevel(Double.parseDouble(ma.group(1)), Double.parseDouble(ma.group(2))));
        }
    }

    public static void main(String[] args) {
        try {
            new AlgoApiServer().start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

