package algo.priority;

import algo.priority.NewsModels.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Crypto Keyword Classifier, Coin Entity Matcher & Relevance Gate.
 *
 * Responsibilities:
 * 1. Matches all 14 core MarketCents watchlist coins (BTC, ETH, SOL, BNB, XRP, DOGE, SUI, NEAR,
 *    PEPE, AVAX, TRX, LINK, ADA, SHIB) plus any user-selected ticker using unambiguous ecosystem
 *    aliases and case-sensitive uppercase ticker guards (preventing English word collisions like
 *    "nearly", "linked", "solution", or "suit").
 * 2. Evaluates 3-tier weighted crypto & market-catalyst keywords (Regulatory/Security/Protocol,
 *    On-Chain/Market Structure, and Crypto Domain Confirmation).
 * 3. Rejects non-crypto noise from multi-topic RSS feeds via a strict relevance gate.
 * 4. Computes composite priority scores parameterized by PriorityMode and custom boost keywords.
 */
public class CryptoKeywordAnalyzer {

    public static final List<String> WATCHLIST_COINS = List.of(
            "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE",
            "SUI", "NEAR", "PEPE", "AVAX", "TRX", "LINK", "ADA", "SHIB"
    );

    private record CoinSpec(
            String symbol,
            List<Pattern> caseInsensitivePatterns,
            Pattern strictTickerPattern
    ) {}

    private record KeywordSpec(
            String keyword,
            int tier,
            String category,
            double weight,
            String polarity,
            Pattern pattern
    ) {}

    private static final List<CoinSpec> COIN_SPECS = buildCoinSpecs();
    private static final Map<String, CoinSpec> COIN_SPEC_MAP = buildCoinSpecMap();
    private static final List<KeywordSpec> KEYWORD_SPECS = buildKeywordSpecs();

    private static List<CoinSpec> buildCoinSpecs() {
        List<CoinSpec> specs = new ArrayList<>();
        specs.add(coin("BTC", false, "bitcoin", "satoshi", "lightning network", "taproot", "ordinals", "btcusdt"));
        specs.add(coin("ETH", false, "ethereum", "ether", "vitalik", "erc-20", "erc20", "pectra", "ethusdt"));
        specs.add(coin("SOL", true, "solana", "solflare", "jito", "jupiter dex", "firedancer", "raydium", "pump.fun", "solusdt"));
        specs.add(coin("BNB", false, "binance coin", "bnb chain", "opbnb", "bnbusdt"));
        specs.add(coin("XRP", false, "ripple", "xrpl", "xrp ledger", "rlusd", "xrpusdt"));
        specs.add(coin("DOGE", false, "dogecoin", "doge-1", "dogeusdt"));
        specs.add(coin("SUI", true, "sui network", "sui blockchain", "sui crypto", "sui token", "sui price", "mysten", "suilend", "cetus", "suiusdt"));
        specs.add(coin("NEAR", true, "near protocol", "near blockchain", "near crypto", "near token", "nightshade", "illia polosukhin", "nearusdt"));
        specs.add(coin("PEPE", true, "pepecoin", "pepe coin", "pepe token", "pepe crypto", "pepe meme", "pepe price", "pepeusdt"));
        specs.add(coin("AVAX", false, "avalanche", "ava labs", "avaxusdt"));
        specs.add(coin("TRX", false, "tron", "trc-20", "trc20", "justin sun", "sunpump", "trxusdt"));
        specs.add(coin("LINK", true, "chainlink", "sergey nazarov", "link token", "ccip", "linkusdt"));
        specs.add(coin("ADA", true, "cardano", "charles hoskinson", "ouroboros", "adausdt"));
        specs.add(coin("SHIB", false, "shiba inu", "shibarium", "shibusdt"));
        return Collections.unmodifiableList(specs);
    }

    private static Map<String, CoinSpec> buildCoinSpecMap() {
        Map<String, CoinSpec> map = new LinkedHashMap<>();
        for (CoinSpec spec : COIN_SPECS) {
            map.put(spec.symbol(), spec);
        }
        return Collections.unmodifiableMap(map);
    }

    private static CoinSpec coin(String symbol, boolean strictCaseTicker, String... aliases) {
        List<Pattern> patterns = new ArrayList<>();
        for (String alias : aliases) {
            patterns.add(wordBoundary(alias, true));
        }
        if (!strictCaseTicker) {
            patterns.add(wordBoundary(symbol, true));
        }
        // Strict ticker pattern always allows $TICKER case-insensitively or exact uppercase TICKER
        Pattern strictPat = Pattern.compile("(?<![A-Za-z0-9])(\\$" + Pattern.quote(symbol) + "|" + Pattern.quote(symbol) + ")(?![A-Za-z0-9])");
        return new CoinSpec(symbol, Collections.unmodifiableList(patterns), strictPat);
    }

    private static List<KeywordSpec> buildKeywordSpecs() {
        List<KeywordSpec> list = new ArrayList<>();

        // Tier 1: High-Impact Regulatory, Institutional, Security & Protocol Catalysts (24 - 35 pts)
        addKw(list, "spot etf", 1, "REGULATORY", 34, "BULLISH");
        addKw(list, "etf", 1, "REGULATORY", 30, "BULLISH");
        addKw(list, "sec", 1, "REGULATORY", 28, "NEUTRAL");
        addKw(list, "cftc", 1, "REGULATORY", 28, "NEUTRAL");
        addKw(list, "blackrock", 1, "INSTITUTIONAL", 28, "BULLISH");
        addKw(list, "fidelity", 1, "INSTITUTIONAL", 25, "BULLISH");
        addKw(list, "grayscale", 1, "INSTITUTIONAL", 24, "NEUTRAL");
        addKw(list, "approval", 1, "REGULATORY", 26, "BULLISH");
        addKw(list, "lawsuit", 1, "REGULATORY", 27, "BEARISH");
        addKw(list, "settlement", 1, "REGULATORY", 24, "NEUTRAL");
        addKw(list, "fomc", 1, "MACRO", 26, "NEUTRAL");
        addKw(list, "interest rate", 1, "MACRO", 25, "NEUTRAL");
        addKw(list, "rate cut", 1, "MACRO", 27, "BULLISH");
        addKw(list, "mica", 1, "REGULATORY", 24, "NEUTRAL");
        addKw(list, "treasury", 1, "INSTITUTIONAL", 22, "BULLISH");

        addKw(list, "hack", 1, "SECURITY_RISK", 35, "RISK");
        addKw(list, "exploit", 1, "SECURITY_RISK", 35, "RISK");
        addKw(list, "breach", 1, "SECURITY_RISK", 33, "RISK");
        addKw(list, "rug pull", 1, "SECURITY_RISK", 34, "RISK");
        addKw(list, "bankruptcy", 1, "SECURITY_RISK", 34, "RISK");
        addKw(list, "insolvency", 1, "SECURITY_RISK", 34, "RISK");
        addKw(list, "liquidation", 1, "SECURITY_RISK", 30, "RISK");
        addKw(list, "depeg", 1, "SECURITY_RISK", 34, "RISK");
        addKw(list, "outage", 1, "SECURITY_RISK", 30, "RISK");
        addKw(list, "halt", 1, "SECURITY_RISK", 28, "RISK");
        addKw(list, "vulnerability", 1, "SECURITY_RISK", 28, "RISK");
        addKw(list, "scam", 1, "SECURITY_RISK", 26, "RISK");

        addKw(list, "halving", 1, "PROTOCOL", 32, "BULLISH");
        addKw(list, "delisting", 1, "EXCHANGE", 32, "BEARISH");
        addKw(list, "listing", 1, "EXCHANGE", 28, "BULLISH");
        addKw(list, "mainnet", 1, "PROTOCOL", 28, "BULLISH");
        addKw(list, "hard fork", 1, "PROTOCOL", 28, "NEUTRAL");
        addKw(list, "upgrade", 1, "PROTOCOL", 24, "BULLISH");
        addKw(list, "airdrop", 1, "PROTOCOL", 24, "BULLISH");
        addKw(list, "token unlock", 1, "SUPPLY", 27, "BEARISH");
        addKw(list, "binance", 1, "EXCHANGE", 24, "NEUTRAL");
        addKw(list, "coinbase", 1, "EXCHANGE", 24, "NEUTRAL");

        // Tier 2: Market Structure, Derivatives & On-Chain Signals (13 - 20 pts)
        addKw(list, "all-time high", 2, "MARKET_FLOW", 20, "BULLISH");
        addKw(list, "ath", 2, "MARKET_FLOW", 18, "BULLISH");
        addKw(list, "short squeeze", 2, "MARKET_FLOW", 20, "BULLISH");
        addKw(list, "whale", 2, "ONCHAIN", 18, "NEUTRAL");
        addKw(list, "inflow", 2, "MARKET_FLOW", 18, "BULLISH");
        addKw(list, "outflow", 2, "MARKET_FLOW", 18, "BEARISH");
        addKw(list, "open interest", 2, "DERIVATIVES", 17, "NEUTRAL");
        addKw(list, "funding rate", 2, "DERIVATIVES", 16, "NEUTRAL");
        addKw(list, "breakout", 2, "MARKET_FLOW", 16, "BULLISH");
        addKw(list, "rally", 2, "MARKET_FLOW", 15, "BULLISH");
        addKw(list, "surge", 2, "MARKET_FLOW", 15, "BULLISH");
        addKw(list, "plunge", 2, "MARKET_FLOW", 17, "BEARISH");
        addKw(list, "crash", 2, "MARKET_FLOW", 19, "BEARISH");
        addKw(list, "sell-off", 2, "MARKET_FLOW", 17, "BEARISH");
        addKw(list, "stablecoin", 2, "ONCHAIN", 16, "NEUTRAL");
        addKw(list, "usdt", 2, "ONCHAIN", 15, "NEUTRAL");
        addKw(list, "usdc", 2, "ONCHAIN", 15, "NEUTRAL");
        addKw(list, "defi", 2, "ONCHAIN", 15, "NEUTRAL");
        addKw(list, "tvl", 2, "ONCHAIN", 15, "NEUTRAL");
        addKw(list, "staking", 2, "ONCHAIN", 14, "BULLISH");
        addKw(list, "layer 2", 2, "PROTOCOL", 15, "NEUTRAL");
        addKw(list, "rollup", 2, "PROTOCOL", 14, "NEUTRAL");
        addKw(list, "bridge", 2, "ONCHAIN", 14, "NEUTRAL");
        addKw(list, "custody", 2, "INSTITUTIONAL", 14, "BULLISH");
        addKw(list, "burn", 2, "SUPPLY", 15, "BULLISH");
        addKw(list, "hashrate", 2, "ONCHAIN", 14, "NEUTRAL");
        addKw(list, "mining", 2, "ONCHAIN", 13, "NEUTRAL");

        // Tier 3: Crypto Domain Confirmation Terms (6 - 10 pts)
        addKw(list, "cryptocurrency", 3, "DOMAIN", 10, "NEUTRAL");
        addKw(list, "crypto", 3, "DOMAIN", 8, "NEUTRAL");
        addKw(list, "blockchain", 3, "DOMAIN", 9, "NEUTRAL");
        addKw(list, "web3", 3, "DOMAIN", 8, "NEUTRAL");
        addKw(list, "altcoin", 3, "DOMAIN", 10, "NEUTRAL");
        addKw(list, "memecoin", 3, "DOMAIN", 10, "NEUTRAL");
        addKw(list, "on-chain", 3, "DOMAIN", 10, "NEUTRAL");
        addKw(list, "smart contract", 3, "DOMAIN", 9, "NEUTRAL");
        addKw(list, "tokenization", 3, "DOMAIN", 10, "BULLISH");
        addKw(list, "rwa", 3, "DOMAIN", 10, "BULLISH");
        addKw(list, "dex", 3, "DOMAIN", 9, "NEUTRAL");
        addKw(list, "cex", 3, "DOMAIN", 9, "NEUTRAL");
        addKw(list, "wallet", 3, "DOMAIN", 8, "NEUTRAL");
        addKw(list, "validator", 3, "DOMAIN", 9, "NEUTRAL");
        addKw(list, "nft", 3, "DOMAIN", 7, "NEUTRAL");
        addKw(list, "dao", 3, "DOMAIN", 7, "NEUTRAL");
        addKw(list, "token", 3, "DOMAIN", 6, "NEUTRAL");

        return Collections.unmodifiableList(list);
    }

    private static void addKw(List<KeywordSpec> list, String keyword, int tier, String category, double weight, String polarity) {
        list.add(new KeywordSpec(keyword, tier, category, weight, polarity, wordBoundary(keyword, true)));
    }

    private static Pattern wordBoundary(String term, boolean caseInsensitive) {
        int flags = caseInsensitive ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(term) + "(?![A-Za-z0-9])", flags);
    }

    /**
     * Evaluates an ingested RSS article against target coins, crypto keyword lexicons, and priority mode.
     * Returns null if the article fails the crypto-relevance gate (or target coin filter when narrowed).
     */
    public ScoredNewsItem analyzeAndScore(
            RawNewsItem item,
            String focusCoin,
            List<String> trackedCoins,
            PriorityMode mode,
            List<String> customKeywords,
            long nowEpochSec
    ) {
        if (item == null || item.title() == null || item.title().isBlank()) return null;

        String title = item.title();
        String summary = item.summary() == null ? "" : item.summary();
        String combinedBody = summary + " " + String.join(" ", item.categories() == null ? List.of() : item.categories());

        // 1. Match coins (watchlist + focus coin + any extra tracked coins)
        Set<String> candidateCoins = new LinkedHashSet<>(WATCHLIST_COINS);
        if (trackedCoins != null) {
            for (String c : trackedCoins) {
                String norm = normalizeSymbol(c);
                if (!norm.isEmpty()) candidateCoins.add(norm);
            }
        }
        String cleanFocus = normalizeSymbol(focusCoin);
        if (!cleanFocus.isEmpty() && !"ALL".equals(cleanFocus)) {
            candidateCoins.add(cleanFocus);
        }

        List<String> matchedCoins = new ArrayList<>();
        int titleCoinHits = 0;
        int bodyCoinHits = 0;
        boolean focusMatchedInTitle = false;
        boolean focusMatchedInBody = false;

        for (String sym : candidateCoins) {
            boolean inTitle = matchesCoin(title, sym);
            boolean inBody = !inTitle && matchesCoin(combinedBody, sym);
            if (inTitle || inBody) {
                matchedCoins.add(sym);
                if (inTitle) titleCoinHits++;
                else bodyCoinHits++;
                if (sym.equals(cleanFocus)) {
                    if (inTitle) focusMatchedInTitle = true;
                    else focusMatchedInBody = true;
                }
            }
        }

        // If user filtered to a specific coin, enforce match unless none match at all? Strict coin filter is cleaner.
        if (!cleanFocus.isEmpty() && !"ALL".equals(cleanFocus) && !matchedCoins.contains(cleanFocus)) {
            return null;
        }

        // 2. Match weighted crypto keywords
        List<KeywordMatch> kwMatches = new ArrayList<>();
        double rawKeywordScore = 0.0;
        int bullishPts = 0;
        int bearishPts = 0;
        int riskHits = 0;
        int tier1Hits = 0;
        boolean hasRegulatory = false;

        for (KeywordSpec spec : KEYWORD_SPECS) {
            boolean inTitle = spec.pattern().matcher(title).find();
            boolean inBody = !inTitle && spec.pattern().matcher(combinedBody).find();
            if (inTitle || inBody) {
                double effectiveWeight = spec.weight() * (inTitle ? 1.4 : 1.0);
                kwMatches.add(new KeywordMatch(spec.keyword(), spec.tier(), spec.category(), effectiveWeight, inTitle));
                rawKeywordScore += effectiveWeight;

                if (spec.tier() == 1) tier1Hits++;
                if ("REGULATORY".equals(spec.category()) || "MACRO".equals(spec.category())) hasRegulatory = true;
                switch (spec.polarity()) {
                    case "BULLISH" -> bullishPts += (inTitle ? 2 : 1);
                    case "BEARISH" -> bearishPts += (inTitle ? 2 : 1);
                    case "RISK" -> {
                        riskHits++;
                        bearishPts += (inTitle ? 2 : 1);
                    }
                    default -> {}
                }
            }
        }

        // 3. Custom boost keywords
        double customBoostScore = 0.0;
        if (customKeywords != null) {
            for (String rawCustom : customKeywords) {
                String clean = rawCustom == null ? "" : rawCustom.trim();
                if (clean.length() < 2) continue;
                Pattern pat = wordBoundary(clean, true);
                boolean inTitle = pat.matcher(title).find();
                boolean inBody = !inTitle && pat.matcher(combinedBody).find();
                if (inTitle || inBody) {
                    double w = inTitle ? 30.0 : 18.0;
                    customBoostScore += w;
                    kwMatches.add(new KeywordMatch(clean.toLowerCase(), 1, "CUSTOM_BOOST", w, inTitle));
                }
            }
        }

        // 4. Crypto-Relevance Gate: reject off-topic non-crypto articles
        boolean isCryptoRelevant = !matchedCoins.isEmpty() || rawKeywordScore >= 8.0 || customBoostScore > 0.0;
        if (!isCryptoRelevant) {
            return null;
        }

        // Sort keyword matches in descending weight for clean UI display
        kwMatches.sort((a, b) -> Double.compare(b.weight(), a.weight()));

        // 5. Component score calculations
        double coinScore = Math.min(75.0, titleCoinHits * 34.0 + bodyCoinHits * 18.0);
        if (focusMatchedInTitle) coinScore += 28.0;
        else if (focusMatchedInBody) coinScore += 14.0;

        double keywordScore = Math.min(95.0, rawKeywordScore);
        if (mode == PriorityMode.CATALYST && tier1Hits > 0) {
            keywordScore += 18.0;
        }
        if (mode == PriorityMode.RISK && riskHits > 0) {
            keywordScore += 32.0;
        }

        double ageHours = item.publishedEpochSec() > 0
                ? Math.max(0.0, (nowEpochSec - item.publishedEpochSec()) / 3600.0)
                : 18.0;
        double recencyScore = 100.0 * Math.exp(-0.045 * ageHours);

        double sourceScore = item.sourceCredibility();

        PriorityMode activeMode = mode == null ? PriorityMode.BALANCED : mode;
        double priorityScore =
                activeMode.coinWeight * coinScore +
                activeMode.keywordWeight * keywordScore +
                activeMode.recencyWeight * recencyScore +
                activeMode.sourceWeight * sourceScore +
                customBoostScore;

        String sentimentHint;
        if (riskHits > 0) sentimentHint = "RISK_ALERT";
        else if (bullishPts > bearishPts + 1) sentimentHint = "BULLISH";
        else if (bearishPts > bullishPts + 1) sentimentHint = "BEARISH";
        else if (hasRegulatory) sentimentHint = "MACRO_REGULATORY";
        else sentimentHint = "NEUTRAL";

        return new ScoredNewsItem(
                item,
                Collections.unmodifiableList(matchedCoins),
                Collections.unmodifiableList(kwMatches),
                sentimentHint,
                round2(coinScore),
                round2(keywordScore),
                round2(recencyScore),
                round2(sourceScore),
                round2(customBoostScore),
                round2(priorityScore)
        );
    }

    /**
     * Evaluates an arbitrary headline or snippet for the interactive Keyword Checker endpoint/UI.
     */
    public KeywordCheckResult checkText(String text, String focusCoin, PriorityMode mode, List<String> customKeywords) {
        if (text == null || text.isBlank()) {
            return new KeywordCheckResult("", false, List.of(), List.of(), "NEUTRAL", 0, 0, 0, 0);
        }
        long now = System.currentTimeMillis() / 1000L;
        RawNewsItem synthetic = new RawNewsItem("check", text.trim(), "", "", "Manual Probe", 20.0, "", now, List.of());

        // Use ALL for discovery unless user explicitly pinned a coin, then check relevance
        ScoredNewsItem scored = analyzeAndScore(synthetic, "ALL", WATCHLIST_COINS, mode, customKeywords, now);
        if (scored == null) {
            return new KeywordCheckResult(text.trim(), false, List.of(), List.of(), "OFF_TOPIC", 0, 0, 0, 0);
        }

        // Apply focus coin boost preview if focusCoin matches
        String cleanFocus = normalizeSymbol(focusCoin);
        double focusBonus = (!cleanFocus.isEmpty() && !"ALL".equals(cleanFocus) && scored.matchedCoins().contains(cleanFocus)) ? 28.0 : 0.0;
        return new KeywordCheckResult(
                text.trim(),
                true,
                scored.matchedCoins(),
                scored.keywordMatches(),
                scored.sentimentHint(),
                round2(scored.coinScore() + focusBonus),
                scored.keywordScore(),
                scored.customBoostScore(),
                round2(scored.priorityScore() + focusBonus * (mode == null ? PriorityMode.BALANCED : mode).coinWeight)
        );
    }

    public static boolean matchesCoin(String text, String symbol) {
        if (text == null || text.isBlank() || symbol == null || symbol.isBlank()) return false;
        String clean = normalizeSymbol(symbol);
        CoinSpec spec = COIN_SPEC_MAP.get(clean);
        if (spec != null) {
            if (spec.strictTickerPattern().matcher(text).find()) return true;
            for (Pattern p : spec.caseInsensitivePatterns()) {
                if (p.matcher(text).find()) return true;
            }
            return false;
        }
        // Fallback for arbitrary Binance symbol (require $TICKER or uppercase TICKER)
        Pattern dynamicTicker = Pattern.compile("(?<![A-Za-z0-9])(\\$" + Pattern.quote(clean) + "|" + Pattern.quote(clean) + ")(?![A-Za-z0-9])");
        return dynamicTicker.matcher(text).find();
    }

    public static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String upper = symbol.trim().toUpperCase();
        if (upper.endsWith("USDT") && upper.length() > 4) {
            upper = upper.substring(0, upper.length() - 4);
        }
        return upper;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
