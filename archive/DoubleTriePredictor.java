import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Double Trie Predictive Autofill / Autocomplete Engine for Candlestick Series.
 * 
 * Conceptual Architecture:
 * - Like a search engine prefix trie that autofills "inter..." -> "internet",
 *   this Double Trie takes the recent prefix of candles and autofills the next N future candles!
 * 
 * Double Trie Structure:
 * 1. Direction Trie: Models directional price transitions (Bullish 'G', Bearish 'R', Doji 'D').
 * 2. Movement / Magnitude Trie: Models volatility expansion/compression (Small 'S', Medium 'M', Large 'L', Extreme 'X').
 * 3. Joint Dual-Node Transition Memory: Stores historical frequency, average return delta %,
 *    wick ratios, and synthesis parameters to project ghost OHLC candles into the future.
 */
public class DoubleTriePredictor {

    public enum Direction {
        BULLISH("G", "Bullish (Up)"),
        BEARISH("R", "Bearish (Down)"),
        DOJI("D", "Doji (Neutral)");

        public final String code;
        public final String label;
        Direction(String code, String label) { this.code = code; this.label = label; }
    }

    public enum Magnitude {
        SMALL("S", "Small / Compression (<0.5 ATR)"),
        MEDIUM("M", "Medium / Standard (0.5-1.2 ATR)"),
        LARGE("L", "Large / Expansion (1.2-2.2 ATR)"),
        EXTREME("X", "Extreme / Volatility Spike (>2.2 ATR)");

        public final String code;
        public final String label;
        Magnitude(String code, String label) { this.code = code; this.label = label; }
    }

    public record CandleToken(Direction direction, Magnitude magnitude) {
        public String key() {
            return direction.code + magnitude.code;
        }

        public String display() {
            return direction.code + "_" + magnitude.code;
        }

        @Override
        public String toString() {
            return display();
        }
    }

    public record SimpleCandle(long timeSeconds, double open, double high, double low, double close, double volume) {
        public double body() { return Math.abs(close - open); }
        public double range() { return Math.max(high - low, 0.00000001); }
        public double upperWick() { return high - Math.max(open, close); }
        public double lowerWick() { return Math.min(open, close) - low; }
        public boolean isBullish() { return close > open; }
        public boolean isBearish() { return close < open; }
    }

    public record PredictedCandle(
            int stepIndex,
            long predictedTimeSeconds,
            CandleToken token,
            double open,
            double high,
            double low,
            double close,
            double expectedDeltaPct,
            double confidencePct,
            int historicalOccurrences
    ) {}

    public record AutofillForecast(
            List<CandleToken> matchedPrefix,
            int prefixMatchCount,
            int fallbackDepth,
            List<PredictedCandle> predictedPath,
            double cumulativeTargetPrice,
            double cumulativeReturnPct,
            double overallConfidencePct,
            String primaryVerdict
    ) {}

    // -----------------------------------------------------------------------------------------
    // 1. DIRECTION TRIE (Sub-Trie 1: Sequence Grammar of Market Direction)
    // -----------------------------------------------------------------------------------------
    static class DirectionNode {
        final Direction direction;
        int visits = 0;
        final Map<Direction, DirectionNode> children = new EnumMap<>(Direction.class);

        DirectionNode(Direction direction) {
            this.direction = direction;
        }
    }

    // -----------------------------------------------------------------------------------------
    // 2. MAGNITUDE TRIE (Sub-Trie 2: Volatility Expansion & Contraction Cycles)
    // -----------------------------------------------------------------------------------------
    static class MagnitudeNode {
        final Magnitude magnitude;
        int visits = 0;
        final Map<Magnitude, MagnitudeNode> children = new EnumMap<>(Magnitude.class);

        MagnitudeNode(Magnitude magnitude) {
            this.magnitude = magnitude;
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3. DUAL-COMPOUND TRIE NODE (Joint Direction + Magnitude Memory & OHLC Synthesis)
    // -----------------------------------------------------------------------------------------
    static class DualNode {
        final CandleToken token;
        int visits = 0;
        final Map<CandleToken, DualNode> children = new HashMap<>();

        // Statistics of the subsequent bar following this prefix state
        final Map<CandleToken, Integer> nextTransitions = new HashMap<>();
        double sumNextDeltaPct = 0.0;
        double sumNextUpperWickRatio = 0.0;
        double sumNextLowerWickRatio = 0.0;
        double sumNextBodyRangeRatio = 0.0;

        DualNode(CandleToken token) {
            this.token = token;
        }

        void recordFollowThrough(CandleToken nextToken, double deltaPct, double upperRatio, double lowerRatio, double bodyRatio) {
            nextTransitions.put(nextToken, nextTransitions.getOrDefault(nextToken, 0) + 1);
            sumNextDeltaPct += deltaPct;
            sumNextUpperWickRatio += upperRatio;
            sumNextLowerWickRatio += lowerRatio;
            sumNextBodyRangeRatio += bodyRatio;
        }

        CandleToken getMostProbableNext() {
            if (nextTransitions.isEmpty()) return null;
            return nextTransitions.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        double getTransitionConfidence(CandleToken next) {
            if (visits == 0 || !nextTransitions.containsKey(next)) return 0.0;
            return (nextTransitions.get(next) * 100.0) / visits;
        }
    }

    private final DirectionNode directionRoot = new DirectionNode(null);
    private final MagnitudeNode magnitudeRoot = new MagnitudeNode(null);
    private final DualNode dualRoot = new DualNode(null);
    private final int maxPrefixDepth;

    public DoubleTriePredictor() {
        this(6); // Default lookback depth of up to 6 bars
    }

    public DoubleTriePredictor(int maxPrefixDepth) {
        this.maxPrefixDepth = maxPrefixDepth;
    }

    /**
     * Converts a raw candle into a quantized (Direction, Magnitude) CandleToken.
     */
    public static CandleToken tokenize(SimpleCandle candle, double atr) {
        Direction dir;
        double body = candle.body();
        double range = candle.range();

        if (body / range < 0.08) {
            dir = Direction.DOJI;
        } else if (candle.isBullish()) {
            dir = Direction.BULLISH;
        } else {
            dir = Direction.BEARISH;
        }

        Magnitude mag;
        double ratio = body / Math.max(atr, 0.00000001);
        if (ratio < 0.45) {
            mag = Magnitude.SMALL;
        } else if (ratio < 1.15) {
            mag = Magnitude.MEDIUM;
        } else if (ratio < 2.25) {
            mag = Magnitude.LARGE;
        } else {
            mag = Magnitude.EXTREME;
        }

        return new CandleToken(dir, mag);
    }

    /**
     * Trains the Double Trie by sliding across historical candle sequences.
     */
    public void train(List<SimpleCandle> candles) {
        if (candles == null || candles.size() < 20) return;

        double[] atrs = calculateAtrSeries(candles, 14);
        List<CandleToken> tokens = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            tokens.add(tokenize(candles.get(i), atrs[i]));
        }

        // Insert n-grams up to maxPrefixDepth + 1 into both single Tries and the Dual Trie
        for (int i = 0; i < tokens.size() - 1; i++) {
            DirectionNode dNode = directionRoot;
            MagnitudeNode mNode = magnitudeRoot;
            DualNode dualNode = dualRoot;

            int end = Math.min(tokens.size() - 1, i + maxPrefixDepth);

            for (int j = i; j <= end; j++) {
                CandleToken tok = tokens.get(j);
                Direction d = tok.direction();
                Magnitude m = tok.magnitude();

                // 1. Train Direction Trie
                dNode = dNode.children.computeIfAbsent(d, DirectionNode::new);
                dNode.visits++;

                // 2. Train Magnitude Trie
                mNode = mNode.children.computeIfAbsent(m, MagnitudeNode::new);
                mNode.visits++;

                // 3. Train Dual-Compound Trie
                dualNode = dualNode.children.computeIfAbsent(tok, DualNode::new);
                dualNode.visits++;

                // Record follow-through statistics if there is a subsequent bar
                if (j + 1 < tokens.size()) {
                    SimpleCandle curr = candles.get(j);
                    SimpleCandle next = candles.get(j + 1);
                    CandleToken nextTok = tokens.get(j + 1);

                    double deltaPct = ((next.close() - curr.close()) / curr.close()) * 100.0;
                    double upperRatio = next.upperWick() / next.range();
                    double lowerRatio = next.lowerWick() / next.range();
                    double bodyRatio = next.body() / next.range();

                    dualNode.recordFollowThrough(nextTok, deltaPct, upperRatio, lowerRatio, bodyRatio);
                }
            }
        }
    }

    /**
     * Auto-suggests and autofills the next `futureSteps` candles following the current prefix.
     */
    public AutofillForecast autofill(List<SimpleCandle> recentCandles, double currentAtr, int futureSteps) {
        if (recentCandles == null || recentCandles.isEmpty() || futureSteps <= 0) return null;

        List<CandleToken> prefixTokens = new ArrayList<>();
        int startIdx = Math.max(0, recentCandles.size() - maxPrefixDepth);
        for (int i = startIdx; i < recentCandles.size(); i++) {
            prefixTokens.add(tokenize(recentCandles.get(i), currentAtr));
        }

        // Walk the Prefix Trie with Hierarchical Suffix Backoff (if exact deep prefix is rare, fall back to last K-1)
        DualNode activeNode = null;
        List<CandleToken> matchedSubPrefix = new ArrayList<>();
        int fallbackDepth = 0;

        for (int shift = 0; shift < prefixTokens.size(); shift++) {
            List<CandleToken> candidate = prefixTokens.subList(shift, prefixTokens.size());
            DualNode curr = dualRoot;
            boolean match = true;

            for (CandleToken t : candidate) {
                curr = curr.children.get(t);
                if (curr == null || curr.visits < 2) {
                    match = false;
                    break;
                }
            }

            if (match && curr != null && !curr.nextTransitions.isEmpty()) {
                activeNode = curr;
                matchedSubPrefix = new ArrayList<>(candidate);
                fallbackDepth = shift;
                break;
            }
        }

        if (activeNode == null) {
            // Absolute fallback: use the single most recent candle token
            CandleToken lastTok = prefixTokens.get(prefixTokens.size() - 1);
            activeNode = dualRoot.children.get(lastTok);
            matchedSubPrefix = List.of(lastTok);
            if (activeNode == null) return null;
        }

        int prefixMatchCount = activeNode.visits;

        // Auto-complete next N steps
        List<PredictedCandle> predictedPath = new ArrayList<>();
        SimpleCandle lastKnownCandle = recentCandles.get(recentCandles.size() - 1);
        double simulatedPrice = lastKnownCandle.close();
        long simulatedTime = lastKnownCandle.timeSeconds();
        long stepDuration = recentCandles.size() > 1 ? (recentCandles.get(1).timeSeconds() - recentCandles.get(0).timeSeconds()) : 60;

        DualNode walker = activeNode;
        double totalConfidenceSum = 0.0;

        for (int step = 1; step <= futureSteps; step++) {
            CandleToken nextToken = walker.getMostProbableNext();
            if (nextToken == null) {
                // If deep branch ends, look up unigram frequency from root
                nextToken = dualRoot.getMostProbableNext();
                if (nextToken == null) break;
            }

            int occurrences = walker.nextTransitions.getOrDefault(nextToken, 1);
            double conf = walker.getTransitionConfidence(nextToken);
            totalConfidenceSum += conf;

            // Compute expected price movement
            int n = Math.max(1, walker.visits);
            double avgDeltaPct = walker.sumNextDeltaPct / n;
            if (nextToken.direction() == Direction.BULLISH && avgDeltaPct <= 0) avgDeltaPct = Math.abs(avgDeltaPct) + 0.15;
            if (nextToken.direction() == Direction.BEARISH && avgDeltaPct >= 0) avgDeltaPct = -Math.abs(avgDeltaPct) - 0.15;

            double expectedOpen = simulatedPrice;
            double expectedClose = expectedOpen * (1.0 + (avgDeltaPct / 100.0));
            double estRange = Math.max(currentAtr * getMagnitudeMultiplier(nextToken.magnitude()), Math.abs(expectedClose - expectedOpen) * 1.25);

            double expectedHigh = Math.max(expectedOpen, expectedClose) + (estRange * 0.20);
            double expectedLow = Math.min(expectedOpen, expectedClose) - (estRange * 0.20);

            simulatedTime += stepDuration;
            simulatedPrice = expectedClose;

            predictedPath.add(new PredictedCandle(
                    step,
                    simulatedTime,
                    nextToken,
                    expectedOpen,
                    expectedHigh,
                    expectedLow,
                    expectedClose,
                    avgDeltaPct,
                    conf,
                    occurrences
            ));

            // Move walker to next child node if exists
            DualNode child = walker.children.get(nextToken);
            if (child != null && !child.nextTransitions.isEmpty()) {
                walker = child;
            } else {
                DualNode fromRoot = dualRoot.children.get(nextToken);
                walker = fromRoot != null ? fromRoot : walker;
            }
        }

        double cumulativeReturn = ((simulatedPrice - lastKnownCandle.close()) / lastKnownCandle.close()) * 100.0;
        double overallConf = predictedPath.isEmpty() ? 0.0 : totalConfidenceSum / predictedPath.size();

        String verdict;
        if (cumulativeReturn > 0.5 && overallConf >= 60.0) {
            verdict = String.format(Locale.US, "STRONG BULLISH AUTOFILL (%+.2f%% over next %d bars)", cumulativeReturn, futureSteps);
        } else if (cumulativeReturn < -0.5 && overallConf >= 60.0) {
            verdict = String.format(Locale.US, "STRONG BEARISH AUTOFILL (%+.2f%% over next %d bars)", cumulativeReturn, futureSteps);
        } else {
            verdict = String.format(Locale.US, "NEUTRAL / CHOP AUTOFILL (%+.2f%% expected fluctuation)", cumulativeReturn);
        }

        return new AutofillForecast(
                matchedSubPrefix,
                prefixMatchCount,
                fallbackDepth,
                predictedPath,
                simulatedPrice,
                cumulativeReturn,
                overallConf,
                verdict
        );
    }

    private static double getMagnitudeMultiplier(Magnitude m) {
        return switch (m) {
            case SMALL -> 0.35;
            case MEDIUM -> 0.85;
            case LARGE -> 1.65;
            case EXTREME -> 2.80;
        };
    }

    private static double[] calculateAtrSeries(List<SimpleCandle> candles, int period) {
        double[] atrs = new double[candles.size()];
        if (candles.isEmpty()) return atrs;
        atrs[0] = candles.get(0).range();

        double trSum = 0.0;
        for (int i = 1; i < candles.size(); i++) {
            SimpleCandle c = candles.get(i);
            SimpleCandle p = candles.get(i - 1);
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

    // -----------------------------------------------------------------------------------------
    // STANDALONE CLI TEST RUNNER & REAL-TIME DEMO
    // -----------------------------------------------------------------------------------------
    public static void main(String[] args) {
        System.out.println("==========================================================================================");
        System.out.println("      DOUBLE TRIE CANDLESTICK AUTOFILL & AUTOSUGGESTION PREDICTOR                         ");
        System.out.println("==========================================================================================");

        String symbol = args.length > 0 ? args[0].toUpperCase() : "BTCUSDT";
        String interval = args.length > 1 ? args[1] : "15m";
        int futurePoints = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        System.out.printf("Fetching 500 historical %s candles for %s from Binance to train Double Trie...%n", interval, symbol);

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=500", symbol, interval);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            List<SimpleCandle> allCandles = parseKlinesJson(resp.body());
            System.out.printf("Loaded %d bars (from $%s to $%s)%n%n",
                    allCandles.size(), formatPrice(allCandles.get(0).close()), formatPrice(allCandles.get(allCandles.size() - 1).close()));

            // Split into Train (first 450) and Test Active Prefix (last 50)
            int split = Math.max(allCandles.size() - 5, 20);
            List<SimpleCandle> trainCandles = allCandles.subList(0, split);
            List<SimpleCandle> activePrefixCandles = allCandles.subList(Math.max(0, split - 5), split);

            DoubleTriePredictor predictor = new DoubleTriePredictor(5);
            long t0 = System.currentTimeMillis();
            predictor.train(trainCandles);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.printf(">> Double Trie successfully trained on %d historical bar transitions in %d ms!%n", trainCandles.size(), elapsed);

            double[] atrs = calculateAtrSeries(allCandles, 14);
            double currentAtr = atrs[split - 1];

            System.out.println("\n------------------------------------------------------------------------------------------");
            System.out.printf("QUERYING PREFIX TRIE TO AUTOFILL NEXT %d DATAPOINTS AFTER CURRENT BAR...%n", futurePoints);
            System.out.println("------------------------------------------------------------------------------------------");

            AutofillForecast forecast = predictor.autofill(activePrefixCandles, currentAtr, futurePoints);

            if (forecast != null) {
                System.out.print("Current Active Prefix Tokens Matched: ");
                for (CandleToken t : forecast.matchedPrefix()) {
                    System.out.printf("[%s] ", t.display());
                }
                System.out.printf("%nHistorical Memory Occurrences of Prefix: %d times%n", forecast.prefixMatchCount());
                System.out.printf("Trie Search Resolution: %s%n", forecast.fallbackDepth() == 0 ? "EXACT FULL MATCH" : "SUFFIX BACKOFF MATCH");
                System.out.printf("Autofill Composite Confidence: %.1f%%%n", forecast.overallConfidencePct());
                System.out.printf("Trie Prediction Verdict: %s%n%n", forecast.primaryVerdict());

                System.out.printf("%-6s | %-8s | %-12s | %-12s | %-12s | %-12s | %-10s | %-10s%n",
                        "Step", "Token", "Exp Open", "Exp High", "Exp Low", "Exp Close", "Exp Delta", "Confidence");
                System.out.println("-------------------------------------------------------------------------------------------------");
                for (PredictedCandle pc : forecast.predictedPath()) {
                    System.out.printf(" +%-4d | %-8s | $%10s | $%10s | $%10s | $%10s | %+7.2f%%   | %5.1f%%%n",
                            pc.stepIndex(),
                            pc.token().display(),
                            formatPrice(pc.open()),
                            formatPrice(pc.high()),
                            formatPrice(pc.low()),
                            formatPrice(pc.close()),
                            pc.expectedDeltaPct(),
                            pc.confidencePct());
                }
                System.out.println("-------------------------------------------------------------------------------------------------");
                System.out.printf("Target Forecast Price: $%s (Cumulative Projected Move: %+.2f%%)%n%n",
                        formatPrice(forecast.cumulativeTargetPrice()), forecast.cumulativeReturnPct());
            } else {
                System.out.println("Insufficient prefix data to autofill next bars.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String formatPrice(double price) {
        if (price >= 1000.0) return String.format(Locale.US, "%.2f", price);
        if (price >= 1.0) return String.format(Locale.US, "%.4f", price);
        if (price >= 0.01) return String.format(Locale.US, "%.6f", price);
        return String.format(Locale.US, "%.8f", price);
    }

    private static List<SimpleCandle> parseKlinesJson(String json) {
        List<SimpleCandle> list = new ArrayList<>();
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
                list.add(new SimpleCandle(timeSec, open, high, low, close, volume));
            }
        }
        return list;
    }
}

