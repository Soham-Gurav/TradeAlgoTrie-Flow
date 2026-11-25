package algo.priority;

import java.util.List;

/**
 * Shared domain records and priority modes for the Unit 2 Crypto News Priority Queue engine.
 */
public final class NewsModels {

    private NewsModels() {}

    public enum PriorityMode {
        BALANCED("Balanced Alpha", 1.00, 1.00, 0.70, 0.80),
        BREAKING("Breaking Velocity", 0.85, 0.80, 1.45, 0.75),
        CATALYST("High-Impact Catalysts", 1.05, 1.45, 0.55, 0.85),
        RISK("Risk & Exploits First", 0.95, 1.30, 0.75, 0.80);

        public final String label;
        public final double coinWeight;
        public final double keywordWeight;
        public final double recencyWeight;
        public final double sourceWeight;

        PriorityMode(String label, double coinWeight, double keywordWeight, double recencyWeight, double sourceWeight) {
            this.label = label;
            this.coinWeight = coinWeight;
            this.keywordWeight = keywordWeight;
            this.recencyWeight = recencyWeight;
            this.sourceWeight = sourceWeight;
        }

        public static PriorityMode fromString(String raw) {
            if (raw == null || raw.isBlank()) return BALANCED;
            try {
                return PriorityMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return BALANCED;
            }
        }
    }

    public record RawNewsItem(
            String id,
            String title,
            String summary,
            String link,
            String sourceName,
            double sourceCredibility,
            String imageUrl,
            long publishedEpochSec,
            List<String> categories
    ) {}

    public record KeywordMatch(
            String keyword,
            int tier,
            String category,
            double weight,
            boolean matchedInTitle
    ) {}

    public record ScoredNewsItem(
            RawNewsItem item,
            List<String> matchedCoins,
            List<KeywordMatch> keywordMatches,
            String sentimentHint,
            double coinScore,
            double keywordScore,
            double recencyScore,
            double sourceScore,
            double customBoostScore,
            double priorityScore
    ) {}

    public record HeapNodeView(
            int heapIndex,
            String newsId,
            String title,
            String primaryCoin,
            double priorityScore,
            int parentIndex,
            int leftIndex,
            int rightIndex
    ) {}

    public record PriorityBenchmark(
            int itemCount,
            int topK,
            long heapTimeNs,
            long sortTimeNs,
            double speedupFactor
    ) {}

    public record KeywordFrequency(
            String keyword,
            int tier,
            String category,
            int count,
            double weight
    ) {}

    public record FeedFetchStatus(
            String sourceName,
            String url,
            String status,
            int itemCount,
            long latencyMs
    ) {}

    public record KeywordCheckResult(
            String text,
            boolean cryptoRelevant,
            List<String> matchedCoins,
            List<KeywordMatch> keywordMatches,
            String sentimentHint,
            double coinScore,
            double keywordScore,
            double customBoostScore,
            double estimatedPriorityScore
    ) {}
}
