package algo.trie;

import java.util.*;

/**
 * Unit 4: Text Search & Indexing - Double Trie Structure with Hierarchical Suffix Backoff.
 * 
 * Conceptual Model:
 * - Tokenizes financial time series into compound tokens: (Direction in {G, R, D}, Magnitude in {S, M, L, X}).
 * - 1. Direction Trie: Models directional price transitions (Bullish 'G', Bearish 'R', Doji 'D').
 * - 2. Magnitude Trie: Models volatility expansion/compression (Small 'S', Medium 'M', Large 'L', Extreme 'X').
 * - 3. Dual-Compound Trie: Models joint distribution P(Token_{t+1} | Token_{t-k..t}) and synthetic OHLC projection.
 * - Suffix Backoff Search: If the exact prefix of length k is unobserved, gracefully falls back to length k-1, k-2.. down to unigrams.
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
        SMALL("S", "Small (<0.5 ATR)"),
        MEDIUM("M", "Medium (0.5-1.2 ATR)"),
        LARGE("L", "Large (1.2-2.2 ATR)"),
        EXTREME("X", "Extreme (>2.2 ATR)");

        public final String code;
        public final String label;
        Magnitude(String code, String label) { this.code = code; this.label = label; }
    }

    public record CandleToken(Direction direction, Magnitude magnitude) {
        public String key() { return direction.code + magnitude.code; }
        public String display() { return direction.code + "_" + magnitude.code; }
        @Override public String toString() { return display(); }
    }

    public record SimpleCandle(long timeSeconds, double open, double high, double low, double close, double volume) {
        public double body() { return Math.abs(close - open); }
        public double range() { return Math.max(high - low, 0.00000001); }
        public double upperWick() { return high - Math.max(open, close); }
        public double lowerWick() { return Math.min(open, close) - low; }
        public boolean isBullish() { return close > open; }
    }

    public record PredictedCandle(
            int stepIndex,
            long predictedTimeSec,
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

    public static class DirectionNode {
        public final Direction direction;
        public final Map<Direction, DirectionNode> children = new HashMap<>();
        public int visits = 0;
        public DirectionNode(Direction direction) { this.direction = direction; }
    }

    public static class MagnitudeNode {
        public final Magnitude magnitude;
        public final Map<Magnitude, MagnitudeNode> children = new HashMap<>();
        public int visits = 0;
        public MagnitudeNode(Magnitude magnitude) { this.magnitude = magnitude; }
    }

    public static class DualNode {
        public final CandleToken token;
        public final Map<CandleToken, DualNode> children = new HashMap<>();
        public int visits = 0;
        public final Map<CandleToken, Integer> nextTransitions = new HashMap<>();
        public double sumNextDeltaPct = 0.0;
        public double sumNextUpperWickRatio = 0.0;
        public double sumNextLowerWickRatio = 0.0;

        public DualNode(CandleToken token) { this.token = token; }

        public CandleToken getMostProbableNext() {
            if (nextTransitions.isEmpty()) return null;
            CandleToken best = null;
            int maxCount = -1;
            for (Map.Entry<CandleToken, Integer> e : nextTransitions.entrySet()) {
                if (e.getValue() > maxCount) {
                    maxCount = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        }

        public double getTransitionConfidence(CandleToken next) {
            int total = 0;
            for (int count : nextTransitions.values()) total += count;
            if (total == 0) return 0.0;
            return ((double) nextTransitions.getOrDefault(next, 0) / total) * 100.0;
        }
    }

    private final DirectionNode directionRoot = new DirectionNode(null);
    private final MagnitudeNode magnitudeRoot = new MagnitudeNode(null);
    private final DualNode dualRoot = new DualNode(null);
    private final int maxPrefixDepth;

    public DoubleTriePredictor() { this(5); }
    public DoubleTriePredictor(int maxPrefixDepth) {
        this.maxPrefixDepth = Math.max(2, maxPrefixDepth);
    }

    public CandleToken tokenize(SimpleCandle candle, double atr) {
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
        if (ratio < 0.45) mag = Magnitude.SMALL;
        else if (ratio < 1.15) mag = Magnitude.MEDIUM;
        else if (ratio < 2.25) mag = Magnitude.LARGE;
        else mag = Magnitude.EXTREME;

        return new CandleToken(dir, mag);
    }

    public void train(List<SimpleCandle> candles) {
        if (candles == null || candles.size() < 10) return;
        double[] atrs = calculateAtrSeries(candles, 14);
        List<CandleToken> tokens = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            tokens.add(tokenize(candles.get(i), atrs[i]));
        }

        for (int i = 0; i < tokens.size() - 1; i++) {
            DirectionNode dNode = directionRoot;
            MagnitudeNode mNode = magnitudeRoot;
            DualNode dualNode = dualRoot;

            int end = Math.min(tokens.size() - 1, i + maxPrefixDepth);
            for (int j = i; j <= end; j++) {
                CandleToken tok = tokens.get(j);
                dNode = dNode.children.computeIfAbsent(tok.direction(), DirectionNode::new);
                dNode.visits++;

                mNode = mNode.children.computeIfAbsent(tok.magnitude(), MagnitudeNode::new);
                mNode.visits++;

                dualNode = dualNode.children.computeIfAbsent(tok, DualNode::new);
                dualNode.visits++;

                if (j + 1 < tokens.size()) {
                    SimpleCandle curr = candles.get(j);
                    SimpleCandle next = candles.get(j + 1);
                    CandleToken nextTok = tokens.get(j + 1);

                    double deltaPct = ((next.close() - curr.close()) / curr.close()) * 100.0;
                    double upperRatio = next.upperWick() / next.range();
                    double lowerRatio = next.lowerWick() / next.range();

                    dualNode.nextTransitions.merge(nextTok, 1, Integer::sum);
                    dualNode.sumNextDeltaPct += deltaPct;
                    dualNode.sumNextUpperWickRatio += upperRatio;
                    dualNode.sumNextLowerWickRatio += lowerRatio;
                }
            }
        }
    }

    public AutofillForecast autofill(List<SimpleCandle> recentCandles, double currentAtr, int futureSteps) {
        if (recentCandles == null || recentCandles.isEmpty()) return null;
        if (futureSteps <= 0) futureSteps = 5;

        List<CandleToken> recentTokens = new ArrayList<>();
        for (SimpleCandle c : recentCandles) {
            recentTokens.add(tokenize(c, currentAtr));
        }

        DualNode matchedNode = null;
        List<CandleToken> matchedSubPrefix = null;
        int fallbackDepth = 0;

        for (int subLen = Math.min(recentTokens.size(), maxPrefixDepth); subLen >= 1; subLen--) {
            List<CandleToken> candidate = recentTokens.subList(recentTokens.size() - subLen, recentTokens.size());
            DualNode curr = dualRoot;
            boolean valid = true;
            for (CandleToken t : candidate) {
                curr = curr.children.get(t);
                if (curr == null) { valid = false; break; }
            }
            if (valid && curr != null && !curr.nextTransitions.isEmpty()) {
                matchedNode = curr;
                matchedSubPrefix = candidate;
                break;
            }
            fallbackDepth++;
        }

        if (matchedNode == null) {
            matchedNode = dualRoot;
            matchedSubPrefix = Collections.emptyList();
        }

        List<PredictedCandle> predictedPath = new ArrayList<>();
        DualNode walker = matchedNode;
        SimpleCandle lastKnownCandle = recentCandles.get(recentCandles.size() - 1);
        double simulatedPrice = lastKnownCandle.close();
        long simulatedTime = lastKnownCandle.timeSeconds();
        long stepDuration = recentCandles.size() >= 2
                ? (lastKnownCandle.timeSeconds() - recentCandles.get(recentCandles.size() - 2).timeSeconds())
                : 900;

        double totalConfidenceSum = 0.0;

        for (int step = 1; step <= futureSteps; step++) {
            CandleToken nextToken = walker.getMostProbableNext();
            if (nextToken == null) {
                nextToken = dualRoot.getMostProbableNext();
                if (nextToken == null) break;
            }

            int occurrences = walker.nextTransitions.getOrDefault(nextToken, 1);
            double conf = walker.getTransitionConfidence(nextToken);
            totalConfidenceSum += conf;

            int n = Math.max(1, walker.visits);
            double avgDeltaPct = walker.sumNextDeltaPct / n;
            if (nextToken.direction() == Direction.BULLISH && avgDeltaPct <= 0) avgDeltaPct = Math.abs(avgDeltaPct) + 0.15;
            if (nextToken.direction() == Direction.BEARISH && avgDeltaPct >= 0) avgDeltaPct = -Math.abs(avgDeltaPct) - 0.15;

            double expectedOpen = simulatedPrice;
            double expectedClose = expectedOpen * (1.0 + (avgDeltaPct / 100.0));
            double mult = switch (nextToken.magnitude()) {
                case SMALL -> 0.35;
                case MEDIUM -> 0.85;
                case LARGE -> 1.65;
                case EXTREME -> 2.80;
            };
            double estRange = Math.max(currentAtr * mult, Math.abs(expectedClose - expectedOpen) * 1.25);
            double expectedHigh = Math.max(expectedOpen, expectedClose) + (estRange * 0.20);
            double expectedLow = Math.min(expectedOpen, expectedClose) - (estRange * 0.20);

            simulatedTime += stepDuration;
            simulatedPrice = expectedClose;

            predictedPath.add(new PredictedCandle(
                    step, simulatedTime, nextToken, expectedOpen, expectedHigh, expectedLow, expectedClose, avgDeltaPct, conf, occurrences
            ));

            DualNode child = walker.children.get(nextToken);
            walker = (child != null && !child.nextTransitions.isEmpty()) ? child : dualRoot;
        }

        double cumulativeReturn = ((simulatedPrice - lastKnownCandle.close()) / lastKnownCandle.close()) * 100.0;
        double overallConf = predictedPath.isEmpty() ? 0.0 : totalConfidenceSum / predictedPath.size();

        String verdict;
        if (cumulativeReturn > 0.5 && overallConf >= 60.0) {
            verdict = String.format(Locale.US, "STRONG BULLISH AUTOFILL (%+.2f%% over next %d bars)", cumulativeReturn, futureSteps);
        } else if (cumulativeReturn < -0.5 && overallConf >= 60.0) {
            verdict = String.format(Locale.US, "STRONG BEARISH AUTOFILL (%+.2f%% over next %d bars)", cumulativeReturn, futureSteps);
        } else {
            verdict = String.format(Locale.US, "NEUTRAL / CHOP AUTOFILL (%+.2f%% fluctuation)", cumulativeReturn);
        }

        return new AutofillForecast(
                matchedSubPrefix, matchedNode.visits, fallbackDepth, predictedPath, simulatedPrice, cumulativeReturn, overallConf, verdict
        );
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
}

