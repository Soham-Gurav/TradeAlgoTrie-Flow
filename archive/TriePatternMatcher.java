import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trie (Prefix Tree) for simultaneous multi-pattern candlestick sequence matching.
 */
public class TriePatternMatcher {

    public record MultiMatch(String patternName, String patternTokens, String date, int endIndex, double price) {}

    static class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        String patternName = null;
        String patternTokens = null;
    }

    private final TrieNode root = new TrieNode();

    /**
     * Inserts a named candlestick pattern into the Trie dictionary.
     */
    public void insert(String name, String tokens) {
        TrieNode current = root;
        for (char ch : tokens.toCharArray()) {
            current = current.children.computeIfAbsent(ch, c -> new TrieNode());
        }
        current.patternName = name;
        current.patternTokens = tokens;
    }

    /**
     * Scans candle stream and finds all dictionary pattern matches at every position.
     */
    public List<MultiMatch> searchAll(List<Candle> candles) {
        List<MultiMatch> matches = new ArrayList<>();
        if (candles == null || candles.isEmpty()) return matches;

        StringBuilder textBuilder = new StringBuilder();
        for (Candle c : candles) {
            textBuilder.append(c.toToken());
        }
        String text = textBuilder.toString();

        for (int i = 0; i < text.length(); i++) {
            TrieNode node = root;
            for (int j = i; j < text.length(); j++) {
                char ch = text.charAt(j);
                node = node.children.get(ch);
                if (node == null) {
                    break;
                }
                if (node.patternName != null) {
                    Candle c = candles.get(j);
                    matches.add(new MultiMatch(node.patternName, node.patternTokens, c.date(), j, c.close()));
                }
            }
        }
        return matches;
    }
}

