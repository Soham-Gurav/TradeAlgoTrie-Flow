package algo.api;

import algo.approx.MetricTspApproximator;
import algo.bloom.OrderFlowBloomFilter;
import algo.flow.OrderFlowNetwork;
import algo.priority.CryptoKeywordAnalyzer;
import algo.priority.CryptoNewsPriorityQueue;
import algo.priority.NewsModels.*;
import algo.priority.RssFeedFetcher;
import algo.sim.MonteCarloSimulator;
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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private final MonteCarloSimulator monteCarloEngine = new MonteCarloSimulator();
    private final MetricTspApproximator tspEngine = new MetricTspApproximator();
    private final CryptoKeywordAnalyzer keywordAnalyzer = new CryptoKeywordAnalyzer();
    private final AlgorithmTestSuite testSuite = new AlgorithmTestSuite();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final RssFeedFetcher rssFeedFetcher = new RssFeedFetcher(httpClient);

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
        server.createContext("/api/sim/montecarlo", new MonteCarloHandler());
        server.createContext("/api/sim/tsp", new MetricTspHandler());
        server.createContext("/api/news/feed", new NewsFeedHandler());
        server.createContext("/api/news/keywords/check", new NewsKeywordCheckHandler());
        server.createContext("/api/news/benchmark", new NewsBenchmarkHandler());
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
        System.out.println("  * GET  /api/sim/montecarlo?symbol=BTCUSDT&interval=15m&trials=2000&horizon=30&mode=GBM");
        System.out.println("  * GET  /api/sim/tsp?symbols=BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,DOGEUSDT,AVAXUSDT,LINKUSDT");
        System.out.println("  * GET  /api/news/feed?coins=ALL&mode=BALANCED&topK=20");
        System.out.println("  * GET  /api/news/keywords/check?text=SEC+approves+spot+Bitcoin+ETF&coin=BTC");
        System.out.println("  * GET  /api/news/benchmark?size=5000&topK=20");
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
                "version": "2.2.0",
                "javaVersion": "%s",
                "syllabusUnitsCovered": ["Unit 2: Binary Max-Heap Priority Queue", "Unit 4: Tries", "Unit 5: Max-Flow Min-Cut", "Unit 5: Metric TSP 2-Approx", "Unit 6: Monte Carlo Simulation", "Unit 6: Bloom Filter (CO5)", "Unit 1/6: Quickselect"],
                "activeAlgorithms": 7,
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
            int rawSize = Integer.parseInt(params.getOrDefault("size", "10000"));
            int size = Math.max(10, Math.min(500000, rawSize));
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

    private class MonteCarloHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String symbol = params.getOrDefault("symbol", "BTCUSDT").trim().toUpperCase();
            String interval = params.getOrDefault("interval", "15m").trim();
            int trials = Integer.parseInt(params.getOrDefault("trials", "2000"));
            int horizon = Integer.parseInt(params.getOrDefault("horizon", "30"));
            String modeParam = params.getOrDefault("mode", "GBM").trim().toUpperCase();
            double tpPct = Double.parseDouble(params.getOrDefault("tpPct", "2.0"));
            double slPct = Double.parseDouble(params.getOrDefault("slPct", "1.5"));

            try {
                String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=250", symbol, interval);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                List<DoubleTriePredictor.SimpleCandle> candles = parseKlines(resp.body());
                if (candles.size() < 10) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Insufficient historical candles for Monte Carlo calibration\"}");
                    return;
                }

                MonteCarloSimulator.SimulationMode mode = "BOOTSTRAP".equals(modeParam)
                        ? MonteCarloSimulator.SimulationMode.BOOTSTRAP
                        : MonteCarloSimulator.SimulationMode.GBM;

                var sim = monteCarloEngine.simulate(candles, trials, horizon, mode, tpPct, slPct);

                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"symbol\":\"").append(escapeJson(symbol)).append("\",");
                json.append("\"interval\":\"").append(escapeJson(interval)).append("\",");
                json.append("\"mode\":\"").append(sim.mode()).append("\",");
                json.append("\"spotPrice\":").append(sim.spotPrice()).append(",");
                json.append("\"perBarDriftPct\":").append(sim.perBarDriftPct()).append(",");
                json.append("\"perBarVolatilityPct\":").append(sim.perBarVolatilityPct()).append(",");
                json.append("\"annualizedVolatilityPct\":").append(sim.annualizedVolatilityPct()).append(",");
                json.append("\"trials\":").append(sim.trials()).append(",");
                json.append("\"horizonSteps\":").append(sim.horizonSteps()).append(",");
                json.append("\"tpPct\":").append(sim.tpPct()).append(",");
                json.append("\"slPct\":").append(sim.slPct()).append(",");
                json.append("\"terminalMeanPrice\":").append(sim.terminalMeanPrice()).append(",");
                json.append("\"terminalMedianPrice\":").append(sim.terminalMedianPrice()).append(",");
                json.append("\"theoreticalMeanPrice\":").append(sim.theoreticalMeanPrice()).append(",");
                json.append("\"meanConvergenceErrorPct\":").append(sim.meanConvergenceErrorPct()).append(",");
                json.append("\"var95Pct\":").append(sim.var95Pct()).append(",");
                json.append("\"cvar95Pct\":").append(sim.cvar95Pct()).append(",");
                json.append("\"probProfitPct\":").append(sim.probProfitPct()).append(",");
                json.append("\"probTakeProfitPct\":").append(sim.probTakeProfitPct()).append(",");
                json.append("\"probStopLossPct\":").append(sim.probStopLossPct()).append(",");
                json.append("\"executionTimeNs\":").append(sim.executionTimeNs()).append(",");

                appendDoubleArray(json, "p5Path", sim.p5Path()); json.append(",");
                appendDoubleArray(json, "p25Path", sim.p25Path()); json.append(",");
                appendDoubleArray(json, "p50Path", sim.p50Path()); json.append(",");
                appendDoubleArray(json, "p75Path", sim.p75Path()); json.append(",");
                appendDoubleArray(json, "p95Path", sim.p95Path()); json.append(",");

                json.append("\"samplePaths\":[");
                for (int i = 0; i < sim.samplePaths().size(); i++) {
                    appendRawDoubleArray(json, sim.samplePaths().get(i));
                    if (i < sim.samplePaths().size() - 1) json.append(",");
                }
                json.append("],");

                json.append("\"histogram\":[");
                for (int i = 0; i < sim.histogram().size(); i++) {
                    var b = sim.histogram().get(i);
                    json.append("{\"rangeStart\":").append(b.rangeStart())
                        .append(",\"rangeEnd\":").append(b.rangeEnd())
                        .append(",\"count\":").append(b.count()).append("}");
                    if (i < sim.histogram().size() - 1) json.append(",");
                }
                json.append("]");
                json.append("}");

                sendJsonResponse(exchange, 200, json.toString());
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\":\"" + escapeJson(String.valueOf(e.getMessage())) + "\"}");
            }
        }
    }

    private class MetricTspHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String symbolsParam = params.getOrDefault("symbols", "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,DOGEUSDT,AVAXUSDT,LINKUSDT");
            String interval = params.getOrDefault("interval", "1h").trim();

            List<String> requested = new ArrayList<>();
            for (String s : symbolsParam.split(",")) {
                String clean = s.trim().toUpperCase();
                if (!clean.isEmpty() && !requested.contains(clean)) {
                    requested.add(clean);
                }
                if (requested.size() >= 12) break;
            }
            if (requested.size() < 2) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Provide at least 2 symbols for Metric TSP\"}");
                return;
            }

            try {
                // Fetch klines concurrently across all selected assets
                List<CompletableFuture<List<DoubleTriePredictor.SimpleCandle>>> futures = new ArrayList<>();
                for (String sym : requested) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=120", sym, interval);
                            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").GET().build();
                            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                            return parseKlines(resp.body());
                        } catch (Exception ex) {
                            return Collections.emptyList();
                        }
                    }));
                }

                List<String> validSymbols = new ArrayList<>();
                List<List<DoubleTriePredictor.SimpleCandle>> validCandles = new ArrayList<>();
                int minLen = Integer.MAX_VALUE;
                for (int i = 0; i < requested.size(); i++) {
                    List<DoubleTriePredictor.SimpleCandle> c = futures.get(i).join();
                    if (c.size() >= 20) {
                        validSymbols.add(requested.get(i));
                        validCandles.add(c);
                        minLen = Math.min(minLen, c.size());
                    }
                }

                if (validSymbols.size() < 2) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Insufficient historical series across symbols\"}");
                    return;
                }

                List<double[]> logReturns = new ArrayList<>(validSymbols.size());
                for (List<DoubleTriePredictor.SimpleCandle> c : validCandles) {
                    int offset = c.size() - minLen;
                    double[] ret = new double[minLen - 1];
                    for (int k = 1; k < minLen; k++) {
                        double p0 = Math.max(c.get(offset + k - 1).close(), 1e-9);
                        double p1 = Math.max(c.get(offset + k).close(), 1e-9);
                        ret[k - 1] = Math.log(p1 / p0);
                    }
                    logReturns.add(ret);
                }

                var tsp = tspEngine.solveFromReturnSeries(validSymbols, logReturns);

                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"interval\":\"").append(escapeJson(interval)).append("\",");
                json.append("\"assetCount\":").append(tsp.nodes().size()).append(",");
                json.append("\"mstCost\":").append(tsp.mstCost()).append(",");
                json.append("\"approxCost\":").append(tsp.approxCost()).append(",");
                json.append("\"nearestNeighborCost\":").append(tsp.nearestNeighborCost()).append(",");
                json.append("\"exactOptimalCost\":").append(tsp.exactOptimalCost()).append(",");
                json.append("\"approxRatioVsOpt\":").append(tsp.approxRatioVsOpt()).append(",");
                json.append("\"approxBoundVsMst\":").append(tsp.approxBoundVsMst()).append(",");
                json.append("\"validMetric\":").append(tsp.validMetric()).append(",");
                json.append("\"approxTimeNs\":").append(tsp.approxTimeNs()).append(",");
                json.append("\"exactTimeNs\":").append(tsp.exactTimeNs()).append(",");

                json.append("\"nodes\":[");
                for (int i = 0; i < tsp.nodes().size(); i++) {
                    var node = tsp.nodes().get(i);
                    json.append("{\"index\":").append(node.index())
                        .append(",\"symbol\":\"").append(escapeJson(node.symbol())).append("\"")
                        .append(",\"x\":").append(node.x())
                        .append(",\"y\":").append(node.y()).append("}");
                    if (i < tsp.nodes().size() - 1) json.append(",");
                }
                json.append("],");

                json.append("\"mstEdges\":[");
                for (int i = 0; i < tsp.mstEdges().size(); i++) {
                    var e = tsp.mstEdges().get(i);
                    json.append("{\"from\":").append(e.from())
                        .append(",\"to\":").append(e.to())
                        .append(",\"weight\":").append(e.weight()).append("}");
                    if (i < tsp.mstEdges().size() - 1) json.append(",");
                }
                json.append("],");

                appendIntList(json, "approxTour", tsp.approxTour()); json.append(",");
                appendIntList(json, "nearestNeighborTour", tsp.nearestNeighborTour()); json.append(",");
                appendIntList(json, "exactOptimalTour", tsp.exactOptimalTour()); json.append(",");

                json.append("\"distanceMatrix\":[");
                for (int i = 0; i < tsp.distanceMatrix().length; i++) {
                    appendRawDoubleArray(json, tsp.distanceMatrix()[i]);
                    if (i < tsp.distanceMatrix().length - 1) json.append(",");
                }
                json.append("]");
                json.append("}");

                sendJsonResponse(exchange, 200, json.toString());
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\":\"" + escapeJson(String.valueOf(e.getMessage())) + "\"}");
            }
        }
    }

    private class NewsFeedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String focusCoin = CryptoKeywordAnalyzer.normalizeSymbol(params.getOrDefault("coin", "ALL"));
            if (focusCoin.isEmpty()) focusCoin = "ALL";
            String coinsParam = params.getOrDefault("coins", String.join(",", CryptoKeywordAnalyzer.WATCHLIST_COINS));
            PriorityMode mode = PriorityMode.fromString(params.getOrDefault("mode", "BALANCED"));
            int topK = Math.max(1, Math.min(100, parseIntOrDefault(params.get("topK"), 20)));
            boolean includeGoogle = Boolean.parseBoolean(params.getOrDefault("includeGoogleNews", "true"));
            boolean forceRefresh = Boolean.parseBoolean(params.getOrDefault("refresh", "false"));
            String customKwParam = params.getOrDefault("customKeywords", "");

            List<String> trackedCoins = new ArrayList<>();
            for (String s : coinsParam.split(",")) {
                String norm = CryptoKeywordAnalyzer.normalizeSymbol(s);
                if (!norm.isEmpty() && !trackedCoins.contains(norm)) trackedCoins.add(norm);
            }
            if (!"ALL".equals(focusCoin) && !trackedCoins.contains(focusCoin)) {
                trackedCoins.add(focusCoin);
            }

            List<String> customKeywords = new ArrayList<>();
            for (String kw : customKwParam.split(",")) {
                String clean = kw.trim();
                if (!clean.isEmpty()) customKeywords.add(clean);
            }

            try {
                long tStart = System.nanoTime();
                RssFeedFetcher.FetchBundle bundle = rssFeedFetcher.fetchAll(focusCoin, includeGoogle, forceRefresh);
                long nowEpochSec = System.currentTimeMillis() / 1000L;

                // Single pass for corpus-wide coin hit counts + relevance filtering
                Map<String, Integer> coinCounts = new LinkedHashMap<>();
                for (String c : CryptoKeywordAnalyzer.WATCHLIST_COINS) coinCounts.put(c, 0);
                if (!"ALL".equals(focusCoin) && !coinCounts.containsKey(focusCoin)) coinCounts.put(focusCoin, 0);

                List<ScoredNewsItem> relevantAll = new ArrayList<>();
                int filteredOutCount = 0;
                for (RawNewsItem raw : bundle.items()) {
                    ScoredNewsItem scoredAll = keywordAnalyzer.analyzeAndScore(raw, "ALL", trackedCoins, mode, customKeywords, nowEpochSec);
                    if (scoredAll == null) {
                        filteredOutCount++;
                        continue;
                    }
                    relevantAll.add(scoredAll);
                    for (String matched : scoredAll.matchedCoins()) {
                        coinCounts.put(matched, coinCounts.getOrDefault(matched, 0) + 1);
                    }
                }

                // Target corpus for the Priority Queue (either ALL or focused coin with focus bonus)
                List<ScoredNewsItem> queueInputs;
                if ("ALL".equals(focusCoin)) {
                    queueInputs = relevantAll;
                } else {
                    queueInputs = new ArrayList<>();
                    for (RawNewsItem raw : bundle.items()) {
                        ScoredNewsItem focused = keywordAnalyzer.analyzeAndScore(raw, focusCoin, trackedCoins, mode, customKeywords, nowEpochSec);
                        if (focused != null) queueInputs.add(focused);
                    }
                }

                // Keyword frequencies across active corpus
                Map<String, Integer> kwCountMap = new HashMap<>();
                Map<String, KeywordMatch> kwMetaMap = new HashMap<>();
                for (ScoredNewsItem s : queueInputs) {
                    for (KeywordMatch km : s.keywordMatches()) {
                        kwCountMap.merge(km.keyword(), 1, Integer::sum);
                        kwMetaMap.putIfAbsent(km.keyword(), km);
                    }
                }
                List<KeywordFrequency> topKeywords = new ArrayList<>();
                for (Map.Entry<String, Integer> e : kwCountMap.entrySet()) {
                    KeywordMatch meta = kwMetaMap.get(e.getKey());
                    topKeywords.add(new KeywordFrequency(e.getKey(), meta.tier(), meta.category(), e.getValue(), meta.weight()));
                }
                topKeywords.sort((a, b) -> {
                    int cCmp = Integer.compare(b.count(), a.count());
                    return cCmp != 0 ? cCmp : Double.compare(b.weight(), a.weight());
                });
                if (topKeywords.size() > 24) topKeywords = topKeywords.subList(0, 24);

                // Unit 2 Binary Max-Heap Construction (O(N)) + Top-K Extraction (O(K log N))
                long heapT0 = System.nanoTime();
                CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue(Math.max(16, queueInputs.size()));
                pq.buildHeap(queueInputs);
                List<HeapNodeView> heapSnapshot = pq.snapshotHeapNodes(15);
                int heapSize = pq.size();
                int heapHeight = pq.getHeapHeight();
                List<ScoredNewsItem> rankedTopK = pq.extractTopK(topK);
                long liveHeapNs = System.nanoTime() - heapT0;

                // Empirical scaled benchmark vs Full Sort Baseline
                PriorityBenchmark scaledBenchmark = pq.benchmarkTopK(5000, Math.min(topK, 20));
                long totalMs = (System.nanoTime() - tStart) / 1_000_000L;

                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"focusCoin\":\"").append(escapeJson(focusCoin)).append("\",");
                json.append("\"mode\":\"").append(mode.name()).append("\",");
                json.append("\"modeLabel\":\"").append(escapeJson(mode.label)).append("\",");
                json.append("\"topK\":").append(topK).append(",");
                json.append("\"rawFetchedCount\":").append(bundle.items().size()).append(",");
                json.append("\"filteredOutCount\":").append(filteredOutCount).append(",");
                json.append("\"cryptoRelevantCount\":").append(relevantAll.size()).append(",");
                json.append("\"heapSize\":").append(heapSize).append(",");
                json.append("\"heapHeight\":").append(heapHeight).append(",");
                json.append("\"liveHeapExtractTimeNs\":").append(liveHeapNs).append(",");
                json.append("\"totalPipelineMs\":").append(totalMs).append(",");
                json.append("\"generatedEpochSec\":").append(nowEpochSec).append(",");

                // Coin counts
                json.append("\"coinCounts\":{");
                int cIdx = 0;
                for (Map.Entry<String, Integer> e : coinCounts.entrySet()) {
                    json.append("\"").append(escapeJson(e.getKey())).append("\":").append(e.getValue());
                    if (cIdx++ < coinCounts.size() - 1) json.append(",");
                }
                json.append("},");

                // Top keywords
                json.append("\"keywordFrequencies\":[");
                for (int i = 0; i < topKeywords.size(); i++) {
                    var kf = topKeywords.get(i);
                    json.append("{\"keyword\":\"").append(escapeJson(kf.keyword())).append("\",")
                        .append("\"tier\":").append(kf.tier()).append(",")
                        .append("\"category\":\"").append(escapeJson(kf.category())).append("\",")
                        .append("\"count\":").append(kf.count()).append(",")
                        .append("\"weight\":").append(kf.weight()).append("}");
                    if (i < topKeywords.size() - 1) json.append(",");
                }
                json.append("],");

                // Binary Max-Heap snapshot
                json.append("\"heapNodes\":[");
                for (int i = 0; i < heapSnapshot.size(); i++) {
                    var hn = heapSnapshot.get(i);
                    json.append("{\"heapIndex\":").append(hn.heapIndex()).append(",")
                        .append("\"newsId\":\"").append(escapeJson(hn.newsId())).append("\",")
                        .append("\"title\":\"").append(escapeJson(hn.title())).append("\",")
                        .append("\"primaryCoin\":\"").append(escapeJson(hn.primaryCoin())).append("\",")
                        .append("\"priorityScore\":").append(hn.priorityScore()).append(",")
                        .append("\"parentIndex\":").append(hn.parentIndex()).append(",")
                        .append("\"leftIndex\":").append(hn.leftIndex()).append(",")
                        .append("\"rightIndex\":").append(hn.rightIndex()).append("}");
                    if (i < heapSnapshot.size() - 1) json.append(",");
                }
                json.append("],");

                // Benchmark
                json.append("\"benchmark\":{")
                    .append("\"itemCount\":").append(scaledBenchmark.itemCount()).append(",")
                    .append("\"topK\":").append(scaledBenchmark.topK()).append(",")
                    .append("\"heapTimeNs\":").append(scaledBenchmark.heapTimeNs()).append(",")
                    .append("\"sortTimeNs\":").append(scaledBenchmark.sortTimeNs()).append(",")
                    .append("\"speedupFactor\":").append(scaledBenchmark.speedupFactor())
                    .append("},");

                // Feed statuses
                json.append("\"feedStatuses\":[");
                for (int i = 0; i < bundle.statuses().size(); i++) {
                    var fs = bundle.statuses().get(i);
                    json.append("{\"sourceName\":\"").append(escapeJson(fs.sourceName())).append("\",")
                        .append("\"url\":\"").append(escapeJson(fs.url())).append("\",")
                        .append("\"status\":\"").append(escapeJson(fs.status())).append("\",")
                        .append("\"itemCount\":").append(fs.itemCount()).append(",")
                        .append("\"latencyMs\":").append(fs.latencyMs()).append("}");
                    if (i < bundle.statuses().size() - 1) json.append(",");
                }
                json.append("],");

                // Ranked items
                json.append("\"items\":[");
                for (int i = 0; i < rankedTopK.size(); i++) {
                    appendScoredNewsJson(json, rankedTopK.get(i), i + 1);
                    if (i < rankedTopK.size() - 1) json.append(",");
                }
                json.append("]");
                json.append("}");

                sendJsonResponse(exchange, 200, json.toString());
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\":\"" + escapeJson(String.valueOf(e.getMessage())) + "\"}");
            }
        }
    }

    private class NewsKeywordCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String text = params.getOrDefault("text", "");
            String coin = params.getOrDefault("coin", "ALL");
            PriorityMode mode = PriorityMode.fromString(params.getOrDefault("mode", "BALANCED"));
            String customKwParam = params.getOrDefault("customKeywords", "");

            List<String> customKeywords = new ArrayList<>();
            for (String kw : customKwParam.split(",")) {
                String clean = kw.trim();
                if (!clean.isEmpty()) customKeywords.add(clean);
            }

            KeywordCheckResult res = keywordAnalyzer.checkText(text, coin, mode, customKeywords);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"text\":\"").append(escapeJson(res.text())).append("\",");
            json.append("\"cryptoRelevant\":").append(res.cryptoRelevant()).append(",");
            json.append("\"sentimentHint\":\"").append(escapeJson(res.sentimentHint())).append("\",");
            json.append("\"coinScore\":").append(res.coinScore()).append(",");
            json.append("\"keywordScore\":").append(res.keywordScore()).append(",");
            json.append("\"customBoostScore\":").append(res.customBoostScore()).append(",");
            json.append("\"estimatedPriorityScore\":").append(res.estimatedPriorityScore()).append(",");
            appendStringList(json, "matchedCoins", res.matchedCoins());
            json.append(",");
            json.append("\"keywordMatches\":");
            appendKeywordMatchesJson(json, res.keywordMatches());
            json.append("}");

            sendJsonResponse(exchange, 200, json.toString());
        }
    }

    private class NewsBenchmarkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            int size = Math.max(20, Math.min(200_000, parseIntOrDefault(params.get("size"), 5000)));
            int topK = Math.max(1, Math.min(size, parseIntOrDefault(params.get("topK"), 20)));

            CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue();
            PriorityBenchmark bm = pq.benchmarkTopK(size, topK);

            String json = """
            {
                "itemCount": %d,
                "topK": %d,
                "heapTimeNs": %d,
                "sortTimeNs": %d,
                "speedupFactor": %.2f
            }
            """.formatted(bm.itemCount(), bm.topK(), bm.heapTimeNs(), bm.sortTimeNs(), bm.speedupFactor());
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
        String query = uri.getRawQuery();
        if (query == null) return map;
        for (String param : query.split("&")) {
            int eqIdx = param.indexOf('=');
            if (eqIdx > 0) {
                String k = URLDecoder.decode(param.substring(0, eqIdx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(param.substring(eqIdx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static void appendDoubleArray(StringBuilder json, String name, double[] arr) {
        json.append("\"").append(name).append("\":");
        appendRawDoubleArray(json, arr);
    }

    private static void appendRawDoubleArray(StringBuilder json, double[] arr) {
        json.append("[");
        for (int i = 0; i < arr.length; i++) {
            json.append(arr[i]);
            if (i < arr.length - 1) json.append(",");
        }
        json.append("]");
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void appendStringList(StringBuilder json, String name, List<String> list) {
        json.append("\"").append(name).append("\":[");
        for (int i = 0; i < list.size(); i++) {
            json.append("\"").append(escapeJson(list.get(i))).append("\"");
            if (i < list.size() - 1) json.append(",");
        }
        json.append("]");
    }

    private static void appendKeywordMatchesJson(StringBuilder json, List<KeywordMatch> matches) {
        json.append("[");
        for (int i = 0; i < matches.size(); i++) {
            var km = matches.get(i);
            json.append("{")
                .append("\"keyword\":\"").append(escapeJson(km.keyword())).append("\",")
                .append("\"tier\":").append(km.tier()).append(",")
                .append("\"category\":\"").append(escapeJson(km.category())).append("\",")
                .append("\"weight\":").append(km.weight()).append(",")
                .append("\"matchedInTitle\":").append(km.matchedInTitle())
                .append("}");
            if (i < matches.size() - 1) json.append(",");
        }
        json.append("]");
    }

    private static void appendScoredNewsJson(StringBuilder json, ScoredNewsItem s, int rank) {
        RawNewsItem it = s.item();
        json.append("{")
            .append("\"rank\":").append(rank).append(",")
            .append("\"id\":\"").append(escapeJson(it.id())).append("\",")
            .append("\"title\":\"").append(escapeJson(it.title())).append("\",")
            .append("\"summary\":\"").append(escapeJson(it.summary())).append("\",")
            .append("\"link\":\"").append(escapeJson(it.link())).append("\",")
            .append("\"sourceName\":\"").append(escapeJson(it.sourceName())).append("\",")
            .append("\"imageUrl\":\"").append(escapeJson(it.imageUrl())).append("\",")
            .append("\"publishedEpochSec\":").append(it.publishedEpochSec()).append(",")
            .append("\"sentimentHint\":\"").append(escapeJson(s.sentimentHint())).append("\",")
            .append("\"coinScore\":").append(s.coinScore()).append(",")
            .append("\"keywordScore\":").append(s.keywordScore()).append(",")
            .append("\"recencyScore\":").append(s.recencyScore()).append(",")
            .append("\"sourceScore\":").append(s.sourceScore()).append(",")
            .append("\"customBoostScore\":").append(s.customBoostScore()).append(",")
            .append("\"priorityScore\":").append(s.priorityScore()).append(",");
        appendStringList(json, "matchedCoins", s.matchedCoins());
        json.append(",\"keywordMatches\":");
        appendKeywordMatchesJson(json, s.keywordMatches());
        json.append("}");
    }

    private static void appendIntList(StringBuilder json, String name, List<Integer> list) {
        json.append("\"").append(name).append("\":[");
        for (int i = 0; i < list.size(); i++) {
            json.append(list.get(i));
            if (i < list.size() - 1) json.append(",");
        }
        json.append("]");
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

