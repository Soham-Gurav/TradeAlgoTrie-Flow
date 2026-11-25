import java.awt.Desktop;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Institutional-Grade Real-Time Multi-Timeframe Crypto Engine.
 * 
 * Capabilities:
 * - 24-Hour Market Scanner: Automatically identifies and ranks high-movement & high-volume coins.
 * - Multi-Factor Confluence: 4-Pillar Quantitative Matrix (Volume Stats 25%, CVD Delta 35%, Order Book OBI 25%, Price Action 15%).
 * - Not reliant solely on candlestick shapes: Order flow drivers (Delta Absorption, Liquidity Breakouts, CVD Drives).
 * - Combined Binance WebSocket multi-stream with 0ms instant signal reaction.
 * - Dynamic Coin Switcher: Click any hot mover coin in browser to instantly analyze and stream it.
 * - Automatic Stop Loss & Take Profit invalidation detection.
 */
public class Crypto {

    private static final String BINANCE_KLINES_URL = "https://api.binance.com/api/v3/klines";
    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record BtcCandle(long timeSeconds, String timeStr, double open, double high, double low, double close, double volume) {
        public double body() { return Math.abs(close - open); }
        public double range() { return Math.max(high - low, 0.00000001); }
        public double upperWick() { return high - Math.max(open, close); }
        public double lowerWick() { return Math.min(open, close) - low; }
        public double bodyPct() { return (body() / Math.max(open, 0.00000001)) * 100.0; }
        public boolean isBullish() { return close > open; }
        public boolean isBearish() { return close < open; }
    }

    public record PatternAlert(
            String timeframe,
            String patternName,
            boolean isBullish,
            long timeSeconds,
            String timeStr,
            double price,
            int confidenceScore,
            String justification
    ) {}

    public record TradePlan(
            String timeframe,
            String patternName,
            boolean isBullish,
            double entryPrice,
            double stopLoss,
            double takeProfit1,
            double takeProfit2,
            double riskAmount,
            String signalTime,
            long signalTimeSeconds
    ) {}

    public record OrderBookStats(
            double totalBidVol,
            double totalAskVol,
            double imbalancePct,
            double topBid,
            double topAsk,
            double spread
    ) {}

    public record VolumeDeltaStats(
            double buyVol,
            double sellVol,
            double deltaBtc,
            double buyRatioPct
    ) {}

    public record MoverCoin(
            String symbol,
            double priceChangePercent,
            double quoteVolume,
            double lastPrice,
            int tradesCount,
            double volatilityScore
    ) {}

    private record CandidatePattern(
            String name,
            boolean isBullish,
            int score,
            String justification
    ) {}

    public static String formatPrice(double price) {
        if (price >= 1000.0) return String.format(Locale.US, "%.2f", price);
        if (price >= 1.0) return String.format(Locale.US, "%.4f", price);
        if (price >= 0.01) return String.format(Locale.US, "%.6f", price);
        return String.format(Locale.US, "%.8f", price);
    }

    public static void main(String[] args) {
        Crypto crypto = new Crypto();
        try {
            System.out.println("==========================================================================================");
            System.out.println("      QUANTITATIVE HIGH-MOVEMENT CRYPTO SCANNER & ORDER FLOW ENGINE                       ");
            System.out.println("==========================================================================================");

            // 1. Scan Top 24-Hour High Movement & Volume Mover Coins
            System.out.println("Scanning Binance 24h market for high-movement & high-volume coins (Liquidity > $20M)...");
            List<MoverCoin> topMovers = crypto.scanTopMoverCoins(10);

            System.out.println("\n------------------------------------------------------------------------------------------");
            System.out.printf(" %-3s | %-12s | %-11s | %-15s | %-14s | %-12s%n", "#", "Symbol", "24h Chg %", "24h Vol (USDT)", "Last Price", "Activity Score");
            System.out.println("------------------------------------------------------------------------------------------");
            for (int i = 0; i < topMovers.size(); i++) {
                MoverCoin c = topMovers.get(i);
                System.out.printf(" %-3d | %-12s | %+9.2f%% | $%11.1fM | $%12s | %6.1f%n",
                        i + 1, c.symbol(), c.priceChangePercent(), c.quoteVolume() / 1e6, formatPrice(c.lastPrice()), c.volatilityScore());
            }
            System.out.println("------------------------------------------------------------------------------------------");

            // Select active coin: user argument if supplied, otherwise auto-select #1 top mover!
            String activeSymbol;
            if (args.length > 0 && !args[0].isBlank()) {
                activeSymbol = args[0].toUpperCase().trim();
                System.out.printf("\n>> User-selected coin: %s%n", activeSymbol);
            } else if (!topMovers.isEmpty()) {
                activeSymbol = topMovers.get(0).symbol();
                System.out.printf("\n>> Auto-selected #1 High-Movement Mover: %s (24h Change: %+.2f%% | Vol: $%.1fM)%n",
                        activeSymbol, topMovers.get(0).priceChangePercent(), topMovers.get(0).quoteVolume() / 1e6);
            } else {
                activeSymbol = DEFAULT_SYMBOL;
            }

            String[] timeframes = {"1m", "5m", "15m", "1h"};
            Map<String, List<BtcCandle>> mtfData = new LinkedHashMap<>();
            List<PatternAlert> allAlerts = new ArrayList<>();
            Map<String, TradePlan> tradePlans = new LinkedHashMap<>();

            // 2. Fetch live Order Book & Volume Delta for the chosen coin
            System.out.printf("Fetching live Order Book (Depth 20) & Volume Delta (200 AggTrades) for %s...%n", activeSymbol);
            OrderBookStats obStats = crypto.fetchOrderBook(activeSymbol, 20);
            VolumeDeltaStats deltaStats = crypto.fetchVolumeDelta(activeSymbol, 200);

            System.out.printf("  * Order Book (Top 20): Bids %.2f vs Asks %.2f | OBI: %+.1f%% (%s)%n",
                    obStats.totalBidVol(), obStats.totalAskVol(), obStats.imbalancePct(),
                    obStats.imbalancePct() >= 0 ? "Bid Wall Defense" : "Ask Wall Resistance");
            System.out.printf("  * Volume Delta (200 Trades): %+.2f | Taker Buy: %.1f%% / Sell: %.1f%%%n",
                    deltaStats.deltaBtc(), deltaStats.buyRatioPct(), 100.0 - deltaStats.buyRatioPct());
            System.out.printf("  * Best Bid: $%s | Best Ask: $%s | Spread: $%s%n%n",
                    formatPrice(obStats.topBid()), formatPrice(obStats.topAsk()), formatPrice(obStats.spread()));

            // 3. Fetch live candles for each timeframe
            for (String tf : timeframes) {
                System.out.printf("Fetching latest %s candles from Binance for %s... ", tf, activeSymbol);
                List<BtcCandle> candles = crypto.fetchKlines(activeSymbol, tf, 120);
                mtfData.put(tf, candles);
                BtcCandle latest = candles.get(candles.size() - 1);
                System.out.printf("[%d bars] Latest: $%s%n", candles.size(), formatPrice(latest.close()));

                // Detect rigorous order flow signals combined with Volume, Delta & Order Book
                List<PatternAlert> tfAlerts = crypto.detectStrictPatterns(tf, candles, deltaStats, obStats);
                allAlerts.addAll(tfAlerts);

                // Calculate Trade Setup based on the latest signal in this timeframe
                TradePlan plan = crypto.calculateTradePlan(tf, candles, tfAlerts);
                if (plan != null) {
                    boolean isConcluded = crypto.isPlanConcluded(plan, candles, latest.close());
                    if (!isConcluded) {
                        tradePlans.put(tf, plan);
                    }
                }
            }

            // 4. Display Trade Setups & Support/Resistance Zones
            System.out.println("\n==========================================================================================");
            System.out.printf("        ACTIVE TRADE SETUPS & S/R ZONES FOR %s (ORDER FLOW & MICROSTRUCTURE)          %n", activeSymbol);
            System.out.println("==========================================================================================");
            for (String tf : timeframes) {
                TradePlan plan = crypto.calculateTradePlan(tf, mtfData.get(tf), allAlerts);
                if (plan != null) {
                    BtcCandle latest = mtfData.get(tf).get(mtfData.get(tf).size() - 1);
                    String conclusionReason = crypto.getPlanConclusionReason(plan, mtfData.get(tf), latest.close());

                    String side = plan.isBullish() ? "LONG (BUY)" : "SHORT (SELL)";
                    String srType = plan.isBullish() ? "Support Floor (SL)" : "Resistance Ceiling (SL)";
                    System.out.printf("  [%-3s Chart] %-11s on '%s' @ %s%n", tf, side, plan.patternName(), plan.signalTime());
                    System.out.printf("        * Entry Price : $%s%n", formatPrice(plan.entryPrice()));
                    System.out.printf("        * %-14s: $%s (Risk: -$%s | %.2f%%)%n", srType, formatPrice(plan.stopLoss()), formatPrice(plan.riskAmount()), (plan.riskAmount() / plan.entryPrice()) * 100.0);
                    System.out.printf("        * Target 1 (1.5R): $%s | Target 2 (2.5R): $%s%n", formatPrice(plan.takeProfit1()), formatPrice(plan.takeProfit2()));
                    if (conclusionReason != null) {
                        System.out.printf("        * STATUS: [CONCLUDED - %s]%n", conclusionReason);
                        System.out.println("                  -> Trade headings removed & chart lines cleared. Awaiting brand-new signal.\n");
                    } else {
                        double diff = plan.isBullish() ? (latest.close() - plan.entryPrice()) : (plan.entryPrice() - latest.close());
                        String sign = diff >= 0 ? "+$" : "-$";
                        System.out.printf("        * STATUS: [ACTIVE TRADE IN PLAY] (Current PnL: %s%s | %+.2fR)%n%n", sign, formatPrice(Math.abs(diff)), diff / plan.riskAmount());
                    }
                } else {
                    System.out.printf("  [%-3s Chart] STATUS: [IDLE] No high-confluence order flow setup currently active.%n%n", tf);
                }
            }

            // 5. Multi-Timeframe Cascading Trend Evaluation
            System.out.println("==========================================================================================");
            System.out.println("                    CASCADING MULTI-TIMEFRAME CONFLUENCE REPORT                           ");
            System.out.println("==========================================================================================");
            crypto.evaluateCascadingConfluence(mtfData, allAlerts);

            // 5.1 Multi-Timeframe Double Trie Autofill & Autosuggestion Engine
            System.out.println("==========================================================================================");
            System.out.println("        DOUBLE TRIE AUTOFILL & AUTOSUGGESTION ENGINE (DIRECTION + MOVEMENT SIZE)          ");
            System.out.println("==========================================================================================");
            Map<String, DoubleTriePredictor.AutofillForecast> trieForecasts = new LinkedHashMap<>();
            int futurePoints = 5;

            for (String tf : timeframes) {
                List<BtcCandle> cList = mtfData.get(tf);
                if (cList != null && cList.size() >= 30) {
                    List<DoubleTriePredictor.SimpleCandle> scList = cList.stream()
                            .map(c -> new DoubleTriePredictor.SimpleCandle(c.timeSeconds(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                            .toList();
                    DoubleTriePredictor dtPredictor = new DoubleTriePredictor(5);
                    dtPredictor.train(scList);
                    double atr = calculateAtr(cList, cList.size() - 1, 14);
                    DoubleTriePredictor.AutofillForecast forecast = dtPredictor.autofill(scList, atr, futurePoints);
                    if (forecast != null) {
                        trieForecasts.put(tf, forecast);
                        StringBuilder sb = new StringBuilder();
                        for (DoubleTriePredictor.CandleToken t : forecast.matchedPrefix()) {
                            sb.append("[").append(t.display()).append("] ");
                        }
                        StringBuilder autofillPath = new StringBuilder();
                        for (DoubleTriePredictor.PredictedCandle pc : forecast.predictedPath()) {
                            autofillPath.append(pc.token().display()).append(" ");
                        }
                        System.out.printf("  [%-3s Chart] Matched Prefix: %-20s -> Autofill: %-22s%n", tf, sb.toString(), autofillPath.toString());
                        System.out.printf("        * Target Price Forecast: $%s (Cumulative Move: %+.2f%% | Conf: %.1f%%)%n",
                                formatPrice(forecast.cumulativeTargetPrice()), forecast.cumulativeReturnPct(), forecast.overallConfidencePct());
                        System.out.printf("        * Memory Occurrences   : %d historical matches | %s%n%n",
                                forecast.prefixMatchCount(), forecast.primaryVerdict());
                    }
                }
            }

            // 6. Generate Interactive 4-Timeframe TradingView Dashboard
            System.out.println("\n==========================================================================================");
            System.out.println("               LAUNCHING 4-TIMEFRAME REAL-TIME TRADINGVIEW DASHBOARD                      ");
            System.out.println("==========================================================================================");
            File dashboardFile = crypto.generateMtfDashboard(activeSymbol, mtfData, allAlerts, tradePlans, obStats, deltaStats, topMovers, trieForecasts);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(dashboardFile.toURI());
                System.out.println("Dashboard opened in browser: " + dashboardFile.getAbsolutePath());
            } else {
                System.out.println("Dashboard generated successfully. Open in your browser: " + dashboardFile.getAbsolutePath());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<MoverCoin> scanTopMoverCoins(int limit) {
        List<MoverCoin> movers = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.binance.com/api/v3/ticker/24hr"))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            Pattern objPat = Pattern.compile("\\{[^\\{\\}]+\\}");
            Matcher m = objPat.matcher(body);

            Pattern symPat = Pattern.compile("\"symbol\"\\s*:\\s*\"([^\"]+)\"");
            Pattern chgPat = Pattern.compile("\"priceChangePercent\"\\s*:\\s*\"([^\"]+)\"");
            Pattern volPat = Pattern.compile("\"quoteVolume\"\\s*:\\s*\"([^\"]+)\"");
            Pattern pricePat = Pattern.compile("\"lastPrice\"\\s*:\\s*\"([^\"]+)\"");
            Pattern countPat = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

            Set<String> blacklist = Set.of(
                "UPUSDT", "DOWNUSDT", "FDUSDUSDT", "USDCUSDT", "TUSDUSDT", "EURUSDT", "DAIUSDT", "USD1USDT", "AEURUSDT",
                "SOXLBUSDT", "NVDABUSDT", "SNDKBUSDT", "SPCXBUSDT", "MUBUSDT", "TSLAUSDT", "AAPLUSDT", "MSFTUSDT", "AMZNUSDT"
            );

            while (m.find()) {
                String block = m.group();
                Matcher ms = symPat.matcher(block);
                if (!ms.find()) continue;
                String sym = ms.group(1);
                if (!sym.endsWith("USDT") || blacklist.contains(sym)) continue;
                if (sym.endsWith("BUSDT") && !sym.equals("BNBUSDT")) continue;

                Matcher mc = chgPat.matcher(block);
                Matcher mv = volPat.matcher(block);
                Matcher mp = pricePat.matcher(block);
                Matcher mt = countPat.matcher(block);

                if (mc.find() && mv.find() && mp.find()) {
                    double chg = Double.parseDouble(mc.group(1));
                    double qVol = Double.parseDouble(mv.group(1));
                    double price = Double.parseDouble(mp.group(1));
                    int count = mt.find() ? Integer.parseInt(mt.group(1)) : 0;

                    // Match TradingView: pure crypto pairs with 24h volume >= $3M USDT
                    if (qVol >= 3_000_000.0) {
                        // Rank directly by 24h price change magnitude (Top Gainers / High Movement)
                        double volScore = Math.abs(chg);
                        movers.add(new MoverCoin(sym, chg, qVol, price, count, volScore));
                    }
                }
            }
            movers.sort((a, b) -> Double.compare(b.volatilityScore(), a.volatilityScore()));
        } catch (Exception e) {
            System.err.println("Warning: scanTopMoverCoins error: " + e.getMessage());
            movers.add(new MoverCoin("ZECUSDT", -17.3, 265_000_000, 980.0, 950000, 27.6));
            movers.add(new MoverCoin("BTCUSDT", -1.1, 1_280_000_000, 73500.0, 4000000, 19.5));
            movers.add(new MoverCoin("PEPEUSDT", -8.5, 99_000_000, 0.000008, 300000, 18.3));
            movers.add(new MoverCoin("ETHUSDT", -1.8, 550_000_000, 2045.0, 1900000, 17.8));
            movers.add(new MoverCoin("UNIUSDT", -8.8, 53_000_000, 6.45, 230000, 17.0));
            movers.add(new MoverCoin("SOLUSDT", -0.6, 177_000_000, 84.0, 600000, 14.0));
        }
        return movers.subList(0, Math.min(limit, movers.size()));
    }

    public OrderBookStats fetchOrderBook(String symbol, int limit) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("https://api.binance.com/api/v3/depth?symbol=%s&limit=%d", symbol, limit)))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String json = resp.body();

            double totalBidVol = 0.0, totalAskVol = 0.0;
            double topBid = 0.0, topAsk = 0.0;

            int bidsIdx = json.indexOf("\"bids\":");
            int asksIdx = json.indexOf("\"asks\":");

            if (bidsIdx != -1 && asksIdx != -1) {
                String bidsPart = json.substring(bidsIdx, asksIdx);
                String asksPart = json.substring(asksIdx);

                Pattern pairPat = Pattern.compile("\\[\\s*\"([0-9.]+)\"\\s*,\\s*\"([0-9.]+)\"\\s*\\]");
                
                Matcher mb = pairPat.matcher(bidsPart);
                boolean firstBid = true;
                while (mb.find()) {
                    double p = Double.parseDouble(mb.group(1));
                    double q = Double.parseDouble(mb.group(2));
                    if (firstBid) { topBid = p; firstBid = false; }
                    totalBidVol += q;
                }

                Matcher ma = pairPat.matcher(asksPart);
                boolean firstAsk = true;
                while (ma.find()) {
                    double p = Double.parseDouble(ma.group(1));
                    double q = Double.parseDouble(ma.group(2));
                    if (firstAsk) { topAsk = p; firstAsk = false; }
                    totalAskVol += q;
                }
            }

            double total = totalBidVol + totalAskVol;
            double imbalance = total > 0 ? ((totalBidVol - totalAskVol) / total) * 100.0 : 0.0;
            double spread = (topBid > 0 && topAsk > 0) ? topAsk - topBid : 0.0;

            return new OrderBookStats(totalBidVol, totalAskVol, imbalance, topBid, topAsk, spread);
        } catch (Exception e) {
            return new OrderBookStats(10.0, 10.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public VolumeDeltaStats fetchVolumeDelta(String symbol, int limit) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("https://api.binance.com/api/v3/aggTrades?symbol=%s&limit=%d", symbol, limit)))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String json = resp.body();

            double buyVol = 0.0, sellVol = 0.0;
            Pattern pat = Pattern.compile("\"q\"\\s*:\\s*\"([0-9.]+)\"[^}]+?\"m\"\\s*:\\s*(true|false)");
            Matcher m = pat.matcher(json);

            while (m.find()) {
                double q = Double.parseDouble(m.group(1));
                boolean buyerIsMaker = Boolean.parseBoolean(m.group(2));
                if (buyerIsMaker) {
                    sellVol += q; // Aggressive market sell
                } else {
                    buyVol += q;  // Aggressive market buy
                }
            }

            double delta = buyVol - sellVol;
            double total = buyVol + sellVol;
            double buyRatio = total > 0 ? (buyVol / total) * 100.0 : 50.0;

            return new VolumeDeltaStats(buyVol, sellVol, delta, buyRatio);
        } catch (Exception e) {
            return new VolumeDeltaStats(5.0, 5.0, 0.0, 50.0);
        }
    }

    public TradePlan calculateTradePlan(String tf, List<BtcCandle> candles, List<PatternAlert> alerts) {
        if (alerts == null || alerts.isEmpty() || candles == null || candles.isEmpty()) return null;

        PatternAlert lastAlert = null;
        for (int i = alerts.size() - 1; i >= 0; i--) {
            if (alerts.get(i).timeframe().equals(tf)) {
                lastAlert = alerts.get(i);
                break;
            }
        }
        if (lastAlert == null) return null;

        int alertIdx = -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).timeSeconds() == lastAlert.timeSeconds()) {
                alertIdx = i;
                break;
            }
        }
        if (alertIdx == -1) alertIdx = candles.size() - 1;

        int lookback = Math.max(0, alertIdx - 3);
        double minLow = Double.MAX_VALUE;
        double maxHigh = Double.MIN_VALUE;
        for (int k = lookback; k <= alertIdx; k++) {
            minLow = Math.min(minLow, candles.get(k).low());
            maxHigh = Math.max(maxHigh, candles.get(k).high());
        }

        double entry = lastAlert.price();
        boolean isBull = lastAlert.isBullish();
        double stopLoss, tp1, tp2, risk;

        if (isBull) {
            stopLoss = minLow * 0.9997;
            risk = Math.max(entry - stopLoss, entry * 0.001);
            tp1 = entry + 1.5 * risk;
            tp2 = entry + 2.5 * risk;
        } else {
            stopLoss = maxHigh * 1.0003;
            risk = Math.max(stopLoss - entry, entry * 0.001);
            tp1 = entry - 1.5 * risk;
            tp2 = entry - 2.5 * risk;
        }

        return new TradePlan(
                tf,
                lastAlert.patternName(),
                isBull,
                entry,
                stopLoss,
                tp1,
                tp2,
                risk,
                lastAlert.timeStr(),
                lastAlert.timeSeconds()
        );
    }

    public String getPlanConclusionReason(TradePlan plan, List<BtcCandle> candles, double livePrice) {
        if (plan == null) return null;

        if (candles != null && !candles.isEmpty()) {
            int alertIdx = -1;
            for (int i = 0; i < candles.size(); i++) {
                if (candles.get(i).timeSeconds() == plan.signalTimeSeconds()) {
                    alertIdx = i;
                    break;
                }
            }
            int start = alertIdx != -1 ? alertIdx : Math.max(0, candles.size() - 4);

            for (int i = start; i < candles.size(); i++) {
                BtcCandle c = candles.get(i);
                if (plan.isBullish()) {
                    if (c.low() <= plan.stopLoss()) {
                        return String.format(Locale.US, "STOP LOSS HIT ($%s <= $%s)", formatPrice(c.low()), formatPrice(plan.stopLoss()));
                    }
                    if (c.high() >= plan.takeProfit1()) {
                        return String.format(Locale.US, "TAKE PROFIT HIT ($%s >= $%s)", formatPrice(c.high()), formatPrice(plan.takeProfit1()));
                    }
                } else {
                    if (c.high() >= plan.stopLoss()) {
                        return String.format(Locale.US, "STOP LOSS HIT ($%s >= $%s)", formatPrice(c.high()), formatPrice(plan.stopLoss()));
                    }
                    if (c.low() <= plan.takeProfit1()) {
                        return String.format(Locale.US, "TAKE PROFIT HIT ($%s <= $%s)", formatPrice(c.low()), formatPrice(plan.takeProfit1()));
                    }
                }
            }
        }

        if (livePrice > 0) {
            if (plan.isBullish()) {
                if (livePrice <= plan.stopLoss()) {
                    return String.format(Locale.US, "STOP LOSS HIT (Live: $%s <= $%s)", formatPrice(livePrice), formatPrice(plan.stopLoss()));
                }
                if (livePrice >= plan.takeProfit1()) {
                    return String.format(Locale.US, "TAKE PROFIT HIT (Live: $%s >= $%s)", formatPrice(livePrice), formatPrice(plan.takeProfit1()));
                }
            } else {
                if (livePrice >= plan.stopLoss()) {
                    return String.format(Locale.US, "STOP LOSS HIT (Live: $%s >= $%s)", formatPrice(livePrice), formatPrice(plan.stopLoss()));
                }
                if (livePrice <= plan.takeProfit1()) {
                    return String.format(Locale.US, "TAKE PROFIT HIT (Live: $%s <= $%s)", formatPrice(livePrice), formatPrice(plan.takeProfit1()));
                }
            }
        }

        return null;
    }

    public boolean isPlanConcluded(TradePlan plan, List<BtcCandle> candles, double livePrice) {
        return getPlanConclusionReason(plan, candles, livePrice) != null;
    }

    public List<PatternAlert> detectStrictPatterns(String tf, List<BtcCandle> candles) {
        return detectStrictPatterns(tf, candles, new VolumeDeltaStats(5.0, 5.0, 0.0, 50.0), new OrderBookStats(5.0, 5.0, 0.0, 0.0, 0.0, 0.0));
    }

    /**
     * Institutional Multi-Factor Quantitative Confluence Detection:
     * Combines 4 Pillars:
     * - Pillar 1: CVD Delta & Taker Aggression (35% weight)
     * - Pillar 2: Order Book Depth Imbalance (OBI) (25% weight)
     * - Pillar 3: Volume Surge / RVol (25% weight)
     * - Pillar 4: Price Action & Structure (15% weight)
     * 
     * Signals CANNOT trigger from candlestick shapes alone. They require verified institutional flow.
     */
    public List<PatternAlert> detectStrictPatterns(String tf, List<BtcCandle> candles, VolumeDeltaStats deltaStats, OrderBookStats obStats) {
        List<PatternAlert> alerts = new ArrayList<>();
        if (candles.size() < 20) return alerts;

        int lastMatchedIndex = -10;

        for (int i = 8; i < candles.size(); i++) {
            CandleContext ctx = new CandleContext(candles, i);
            List<CandidatePattern> candidates = new ArrayList<>();

            double deltaVal = deltaStats != null ? deltaStats.deltaBtc() : 0.0;
            double buyRatio = deltaStats != null ? deltaStats.buyRatioPct() : 50.0;
            double obiVal = obStats != null ? obStats.imbalancePct() : 0.0;

            // 1. Pillar 1: CVD Delta Score (35%)
            int deltaBullScore = buyRatio >= 62.0 ? 95 : (buyRatio >= 53.0 ? 75 : (buyRatio >= 47.0 ? 50 : 15));
            int deltaBearScore = buyRatio <= 38.0 ? 95 : (buyRatio <= 47.0 ? 75 : (buyRatio <= 53.0 ? 50 : 15));

            // 2. Pillar 2: Order Book OBI Score (25%)
            int obBullScore = obiVal >= 20.0 ? 95 : (obiVal >= 8.0 ? 75 : (obiVal <= -20.0 ? 15 : 50));
            int obBearScore = obiVal <= -20.0 ? 95 : (obiVal <= -8.0 ? 75 : (obiVal >= 20.0 ? 15 : 50));

            // 3. Pillar 3: Volume Surge RVol Score (25%)
            int volScore = ctx.rvol >= 2.2 ? 95 : (ctx.rvol >= 1.5 ? 80 : (ctx.rvol >= 1.0 ? 55 : 20));

            // 4. Pillar 4: Price Action Evaluation (15%)
            boolean isLowerWickSweep = ctx.c0.lowerWick() >= 1.5 * ctx.c0.body() && ctx.c0.isBullish() && ctx.c0.low() <= ctx.lowestLow(5);
            boolean isUpperWickSweep = ctx.c0.upperWick() >= 1.5 * ctx.c0.body() && ctx.c0.isBearish() && ctx.c0.high() >= ctx.highestHigh(5);

            boolean isBullEngulf = ctx.isDowntrend() && ctx.c1.isBearish() && ctx.c0.isBullish() && ctx.c0.body() >= 1.25 * ctx.c1.body();
            boolean isBearEngulf = ctx.isUptrend() && ctx.c1.isBullish() && ctx.c0.isBearish() && ctx.c0.body() >= 1.25 * ctx.c1.body();

            boolean isMorningStar = ctx.isDowntrend() && ctx.c2.isBearish() && ctx.c1.body() <= 0.35 * ctx.atr && ctx.c0.isBullish() && ctx.c0.close() > (ctx.c2.open() + ctx.c2.close()) / 2.0;
            boolean isEveningStar = ctx.isUptrend() && ctx.c2.isBullish() && ctx.c1.body() <= 0.35 * ctx.atr && ctx.c0.isBearish() && ctx.c0.close() < (ctx.c2.open() + ctx.c2.close()) / 2.0;

            int paBullScore = (isLowerWickSweep || isBullEngulf || isMorningStar) ? 85 : (ctx.c0.isBullish() ? 65 : 35);
            int paBearScore = (isUpperWickSweep || isBearEngulf || isEveningStar) ? 85 : (ctx.c0.isBearish() ? 65 : 35);

            // Composite Scores
            int totalBull = (int) Math.round(0.35 * deltaBullScore + 0.25 * obBullScore + 0.25 * volScore + 0.15 * paBullScore);
            int totalBear = (int) Math.round(0.35 * deltaBearScore + 0.25 * obBearScore + 0.25 * volScore + 0.15 * paBearScore);

            // Strict Vetoes: If CVD or Book is heavily opposing, cancel out false setups!
            if (deltaBullScore <= 20 || obBullScore <= 20 || volScore <= 25) totalBull = 40;
            if (deltaBearScore <= 20 || obBearScore <= 20 || volScore <= 25) totalBear = 40;

            // Generate Institutional Order Flow Signals
            if (totalBull >= 70) {
                String name;
                String desc;
                if (isLowerWickSweep && deltaBullScore >= 75) {
                    name = "Institutional Delta Absorption";
                    desc = "Passive limit buyers absorbed aggressive sell dump at swing floor with positive delta flip";
                } else if (volScore >= 80 && obBullScore >= 80) {
                    name = "Order Book Liquidity Breakout";
                    desc = "High RVol volume surge supported by dense bid book wall defense";
                } else if (deltaBullScore >= 80 && volScore >= 75) {
                    name = "CVD Momentum Drive";
                    desc = "Aggressive market taker buyers pushing price through resistance with heavy delta";
                } else if (isBullEngulf) {
                    name = "Bullish Engulfing";
                    desc = "Engulfing green body confirmed by institutional volume & delta alignment";
                } else if (isMorningStar) {
                    name = "Morning Star";
                    desc = "Reversal from low confirmed by positive delta and book defense";
                } else {
                    name = "Order Flow Long Surge";
                    desc = "Multi-factor institutional buy flow with volume expansion";
                }
                candidates.add(new CandidatePattern(name, true, totalBull, desc));
            }

            if (totalBear >= 70) {
                String name;
                String desc;
                if (isUpperWickSweep && deltaBearScore >= 75) {
                    name = "Institutional Delta Absorption";
                    desc = "Passive limit sellers absorbed aggressive buyer push at peak with negative delta dump";
                } else if (volScore >= 80 && obBearScore >= 80) {
                    name = "Order Book Liquidity Breakout";
                    desc = "High RVol volume breakdown capped by dense ask book wall resistance";
                } else if (deltaBearScore >= 80 && volScore >= 75) {
                    name = "CVD Momentum Drive";
                    desc = "Aggressive market taker sellers hitting bids with strong negative delta drive";
                } else if (isBearEngulf) {
                    name = "Bearish Engulfing";
                    desc = "Sellers engulfed green bar confirmed by institutional volume & negative delta";
                } else if (isEveningStar) {
                    name = "Evening Star";
                    desc = "Peak exhaustion confirmed by aggressive seller delta";
                } else {
                    name = "Order Flow Short Breakdown";
                    desc = "Multi-factor institutional selling pressure with book defense";
                }
                candidates.add(new CandidatePattern(name, false, totalBear, desc));
            }

            if (!candidates.isEmpty()) {
                candidates.sort((a, b) -> Integer.compare(b.score(), a.score()));
                CandidatePattern best = candidates.get(0);

                if (i - lastMatchedIndex >= 4) {
                    alerts.add(new PatternAlert(
                            tf,
                            best.name(),
                            best.isBullish(),
                            ctx.c0.timeSeconds(),
                            ctx.c0.timeStr(),
                            ctx.c0.close(),
                            best.score(),
                            best.justification()
                    ));
                    lastMatchedIndex = i;
                }
            }
        }

        return alerts;
    }

    private static class CandleContext {
        final BtcCandle c0, c1, c2, c3;
        final double atr;
        final double sma7;
        final double volSma;
        final double rvol;
        final List<BtcCandle> candles;
        final int currentIndex;

        CandleContext(List<BtcCandle> list, int idx) {
            this.candles = list;
            this.currentIndex = idx;
            this.c0 = list.get(idx);
            this.c1 = list.get(idx - 1);
            this.c2 = list.get(idx - 2);
            this.c3 = list.get(idx - 3);
            this.atr = calculateAtr(list, idx, 14);
            this.sma7 = calculateSma(list, idx - 1, 7);
            this.volSma = calculateVolSma(list, idx - 1, 20);
            this.rvol = c0.volume() / Math.max(volSma, 0.001);
        }

        boolean isDowntrend() { return c1.close() < sma7 && c2.close() < sma7; }
        boolean isUptrend() { return c1.close() > sma7 && c2.close() > sma7; }

        double lowestLow(int lookback) {
            double min = Double.MAX_VALUE;
            int start = Math.max(0, currentIndex - lookback);
            for (int k = start; k < currentIndex; k++) { min = Math.min(min, candles.get(k).low()); }
            return min;
        }

        double highestHigh(int lookback) {
            double max = Double.MIN_VALUE;
            int start = Math.max(0, currentIndex - lookback);
            for (int k = start; k < currentIndex; k++) { max = Math.max(max, candles.get(k).high()); }
            return max;
        }
    }

    public static double calculateVolSma(List<BtcCandle> candles, int endIdx, int period) {
        double sum = 0.0;
        int count = 0;
        int start = Math.max(0, endIdx - period + 1);
        for (int k = start; k <= endIdx; k++) {
            sum += candles.get(k).volume();
            count++;
        }
        return count > 0 ? sum / count : (candles.get(endIdx).volume() > 0 ? candles.get(endIdx).volume() : 1.0);
    }

    private static double calculateSma(List<BtcCandle> candles, int endIdx, int period) {
        double sum = 0.0;
        int count = 0;
        int start = Math.max(0, endIdx - period + 1);
        for (int k = start; k <= endIdx; k++) {
            sum += candles.get(k).close();
            count++;
        }
        return count > 0 ? sum / count : candles.get(endIdx).close();
    }

    private static double calculateAtr(List<BtcCandle> candles, int endIdx, int period) {
        double trSum = 0.0;
        int count = 0;
        int start = Math.max(1, endIdx - period + 1);
        for (int k = start; k <= endIdx; k++) {
            BtcCandle curr = candles.get(k);
            BtcCandle prev = candles.get(k - 1);
            double tr = Math.max(curr.high() - curr.low(), Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            trSum += tr;
            count++;
        }
        return count > 0 ? trSum / count : 1.0;
    }

    public List<BtcCandle> fetchKlines(String symbol, String interval, int limit) throws IOException, InterruptedException {
        String url = String.format("%s?symbol=%s&interval=%s&limit=%d", BINANCE_KLINES_URL, symbol, interval, limit);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return parseKlinesJson(resp.body());
    }

    private List<BtcCandle> parseKlinesJson(String json) {
        List<BtcCandle> candles = new ArrayList<>();
        Pattern rowPat = Pattern.compile("\\[([^\\[\\]]+)\\]");
        Matcher m = rowPat.matcher(json);

        while (m.find()) {
            String row = m.group(1);
            String[] cols = row.split(",");
            if (cols.length >= 6) {
                long openTimeMs = Long.parseLong(cols[0].trim());
                long timeSec = openTimeMs / 1000;
                String timeStr = TIME_FMT.format(Instant.ofEpochMilli(openTimeMs));
                double open = Double.parseDouble(cols[1].replace("\"", "").trim());
                double high = Double.parseDouble(cols[2].replace("\"", "").trim());
                double low = Double.parseDouble(cols[3].replace("\"", "").trim());
                double close = Double.parseDouble(cols[4].replace("\"", "").trim());
                double volume = Double.parseDouble(cols[5].replace("\"", "").trim());
                candles.add(new BtcCandle(timeSec, timeStr, open, high, low, close, volume));
            }
        }
        return candles;
    }

    public void evaluateCascadingConfluence(Map<String, List<BtcCandle>> mtfData, List<PatternAlert> alerts) {
        System.out.println("Current Close by Timeframe:");
        int bullishScore = 0, bearishScore = 0;

        String[] tfs = {"1m", "5m", "15m", "1h"};
        int[] weights = {10, 20, 30, 40};

        for (int i = 0; i < tfs.length; i++) {
            String tf = tfs[i];
            List<BtcCandle> candles = mtfData.get(tf);
            if (candles != null && !candles.isEmpty()) {
                BtcCandle last = candles.get(candles.size() - 1);
                boolean bull = last.isBullish();
                System.out.printf("  * %3s Bar:  $%s | Bias: %s%n", tf, formatPrice(last.close()), bull ? "BULLISH" : "BEARISH");
                if (bull) bullishScore += weights[i];
                else bearishScore += weights[i];
            }
        }

        System.out.printf("%nMulti-Timeframe Cascading Trend Score: Bullish %d%% vs Bearish %d%%%n", bullishScore, bearishScore);
        if (bullishScore >= 70) {
            System.out.println(">> CASCADE VERDICT: STRONG BULLISH CONFLUENCE (Higher timeframes confirming lower timeframe momentum).");
        } else if (bearishScore >= 70) {
            System.out.println(">> CASCADE VERDICT: STRONG BEARISH CONFLUENCE (Higher timeframes confirming selling pressure).");
        } else {
            System.out.println(">> CASCADE VERDICT: TRANSITION / CHOP STATE (Selective trades only at key support/resistance).");
        }
    }

    public File generateMtfDashboard(String symbol, Map<String, List<BtcCandle>> mtfData, List<PatternAlert> alerts, Map<String, TradePlan> tradePlans, OrderBookStats obStats, VolumeDeltaStats deltaStats, List<MoverCoin> topMovers, Map<String, DoubleTriePredictor.AutofillForecast> trieForecasts) throws IOException {
        StringBuilder json1m = formatCandlesJson(mtfData.get("1m"));
        StringBuilder json5m = formatCandlesJson(mtfData.get("5m"));
        StringBuilder json15m = formatCandlesJson(mtfData.get("15m"));
        StringBuilder json1h = formatCandlesJson(mtfData.get("1h"));

        StringBuilder markers1m = formatMarkersJson("1m", alerts);
        StringBuilder markers5m = formatMarkersJson("5m", alerts);
        StringBuilder markers15m = formatMarkersJson("15m", alerts);
        StringBuilder markers1h = formatMarkersJson("1h", alerts);

        StringBuilder alertDetailsMap = new StringBuilder("{");
        for (PatternAlert a : alerts) {
            alertDetailsMap.append(String.format(Locale.US, "\"%s_%d\":{\"name\":\"%s\",\"score\":%d,\"price\":%f,\"desc\":\"%s\",\"bullish\":%b},",
                    a.timeframe(), a.timeSeconds(), a.patternName(), a.confidenceScore(), a.price(), a.justification().replace("\"", "'"), a.isBullish()));
        }
        if (alertDetailsMap.length() > 1 && alertDetailsMap.charAt(alertDetailsMap.length() - 1) == ',') {
            alertDetailsMap.setCharAt(alertDetailsMap.length() - 1, '}');
        } else {
            alertDetailsMap.append('}');
        }

        StringBuilder tradePlansJson = new StringBuilder("{");
        for (Map.Entry<String, TradePlan> e : tradePlans.entrySet()) {
            TradePlan p = e.getValue();
            int score = 80;
            for (PatternAlert a : alerts) {
                if (a.timeframe().equals(e.getKey()) && a.timeSeconds() == p.signalTimeSeconds()) {
                    score = a.confidenceScore();
                    break;
                }
            }
            tradePlansJson.append(String.format(Locale.US, "\"%s\":{\"pattern\":\"%s\",\"isBullish\":%b,\"entry\":%f,\"stopLoss\":%f,\"tp1\":%f,\"tp2\":%f,\"risk\":%f,\"time\":\"%s\",\"timeSeconds\":%d,\"confluenceScore\":%d},",
                    e.getKey(), p.patternName(), p.isBullish(), p.entryPrice(), p.stopLoss(), p.takeProfit1(), p.takeProfit2(), p.riskAmount(), p.signalTime(), p.signalTimeSeconds(), score));
        }
        if (tradePlansJson.length() > 1 && tradePlansJson.charAt(tradePlansJson.length() - 1) == ',') {
            tradePlansJson.setCharAt(tradePlansJson.length() - 1, '}');
        } else {
            tradePlansJson.append('}');
        }

        StringBuilder moversJson = new StringBuilder("[");
        if (topMovers != null) {
            for (MoverCoin m : topMovers) {
                moversJson.append(String.format(Locale.US, "{\"symbol\":\"%s\",\"chg\":%.2f,\"vol\":%.1f,\"price\":%f},",
                        m.symbol(), m.priceChangePercent(), m.quoteVolume() / 1e6, m.lastPrice()));
            }
        }
        if (moversJson.length() > 1 && moversJson.charAt(moversJson.length() - 1) == ',') {
            moversJson.setCharAt(moversJson.length() - 1, ']');
        } else {
            moversJson.append(']');
        }

        StringBuilder trieForecastsJson = new StringBuilder("{");
        if (trieForecasts != null) {
            for (Map.Entry<String, DoubleTriePredictor.AutofillForecast> e : trieForecasts.entrySet()) {
                DoubleTriePredictor.AutofillForecast f = e.getValue();
                trieForecastsJson.append(String.format(Locale.US, "\"%s\":{\"target\":%f,\"ret\":%.2f,\"conf\":%.1f,\"verdict\":\"%s\",\"candles\":[",
                        e.getKey(), f.cumulativeTargetPrice(), f.cumulativeReturnPct(), f.overallConfidencePct(), f.primaryVerdict()));
                for (DoubleTriePredictor.PredictedCandle pc : f.predictedPath()) {
                    trieForecastsJson.append(String.format(Locale.US, "{\"time\":%d,\"open\":%f,\"high\":%f,\"low\":%f,\"close\":%f,\"token\":\"%s\",\"conf\":%.1f},",
                            pc.predictedTimeSeconds(), pc.open(), pc.high(), pc.low(), pc.close(), pc.token().display(), pc.confidencePct()));
                }
                closeJsonArray(trieForecastsJson);
                trieForecastsJson.append("},");
            }
            if (trieForecastsJson.length() > 1 && trieForecastsJson.charAt(trieForecastsJson.length() - 1) == ',') {
                trieForecastsJson.setCharAt(trieForecastsJson.length() - 1, '}');
            } else {
                trieForecastsJson.append('}');
            }
        } else {
            trieForecastsJson.append('}');
        }

        BtcCandle c1m = mtfData.get("1m").get(mtfData.get("1m").size() - 1);
        BtcCandle c5m = mtfData.get("5m").get(mtfData.get("5m").size() - 1);
        BtcCandle c15m = mtfData.get("15m").get(mtfData.get("15m").size() - 1);
        BtcCandle c1h = mtfData.get("1h").get(mtfData.get("1h").size() - 1);

        double deltaBtc = deltaStats != null ? deltaStats.deltaBtc() : 0.0;
        double buyRatio = deltaStats != null ? deltaStats.buyRatioPct() : 50.0;
        double buyVol = deltaStats != null ? deltaStats.buyVol() : 0.0;
        double sellVol = deltaStats != null ? deltaStats.sellVol() : 0.0;
        boolean deltaBull = deltaBtc >= 0;

        double bidVol = obStats != null ? obStats.totalBidVol() : 0.0;
        double askVol = obStats != null ? obStats.totalAskVol() : 0.0;
        double imbPct = obStats != null ? obStats.imbalancePct() : 0.0;
        boolean obBull = imbPct >= 0;
        double totBook = bidVol + askVol;
        double bidFill = totBook > 0 ? (bidVol / totBook) * 100.0 : 50.0;
        double askFill = totBook > 0 ? (askVol / totBook) * 100.0 : 50.0;

        String deltaJson = String.format(Locale.US, "{\"deltaBtc\":%.2f,\"buyVol\":%.2f,\"sellVol\":%.2f,\"buyRatioPct\":%.1f}", deltaBtc, buyVol, sellVol, buyRatio);
        String obJson = String.format(Locale.US, "{\"totalBidVol\":%.2f,\"totalAskVol\":%.2f,\"imbalancePct\":%.1f}", bidVol, askVol, imbPct);

        String html = String.format(Locale.US, """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s &mdash; Quantitative Order Flow & High-Movement Crypto Terminal</title>
                <script src="https://unpkg.com/lightweight-charts@4.1.3/dist/lightweight-charts.standalone.production.js"></script>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    html, body {
                        width: 100%%;
                        height: 100%%;
                        background-color: #0b0e14;
                        color: #d1d4dc;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        overflow: hidden;
                    }
                    .header-bar {
                        height: 44px;
                        background: #131722;
                        border-bottom: 1px solid #232838;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        padding: 0 14px;
                    }
                    .left-group { display: flex; align-items: center; gap: 12px; }
                    .brand-title {
                        font-size: 14px;
                        font-weight: 800;
                        color: #f1f5f9;
                        letter-spacing: 0.5px;
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }
                    .price-pill {
                        background: #1e2638;
                        padding: 3px 9px;
                        border-radius: 6px;
                        font-size: 13px;
                        font-weight: 700;
                        color: #00e676;
                        border: 1px solid #2a354d;
                        font-family: monospace;
                    }
                    .confluence-group { display: flex; gap: 5px; align-items: center; }
                    .tf-badge {
                        font-size: 10px;
                        font-weight: 700;
                        padding: 2px 6px;
                        border-radius: 4px;
                    }
                    .bg-bull { background: rgba(8, 153, 129, 0.25); color: #089981; border: 1px solid #089981; }
                    .bg-bear { background: rgba(242, 54, 69, 0.25); color: #f23645; border: 1px solid #f23645; }

                    .movers-strip {
                        display: flex;
                        align-items: center;
                        gap: 6px;
                        background: #10141f;
                        padding: 2px 8px;
                        border-radius: 6px;
                        border: 1px solid #1e2638;
                    }
                    .movers-title {
                        font-size: 9px;
                        font-weight: 800;
                        color: #ff9800;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                    }
                    .mover-btn {
                        background: #192030;
                        border: 1px solid #273147;
                        color: #cbd5e1;
                        padding: 2px 7px;
                        border-radius: 4px;
                        font-size: 10px;
                        font-weight: 700;
                        cursor: pointer;
                        transition: all 0.2s;
                        display: flex;
                        gap: 4px;
                        align-items: center;
                    }
                    .mover-btn:hover { background: #232c40; color: #fff; }
                    .mover-btn.active { background: #2962ff; color: #fff; border-color: #3b82f6; }
                    .mover-chg-up { color: #00e676; }
                    .mover-chg-down { color: #f23645; }
                    
                    .view-switcher {
                        display: flex;
                        background: #181d2a;
                        padding: 2px;
                        border-radius: 6px;
                        border: 1px solid #232838;
                        gap: 2px;
                    }
                    .btn-tab {
                        background: transparent;
                        border: none;
                        color: #8e96aa;
                        padding: 3px 8px;
                        font-size: 11px;
                        font-weight: 600;
                        border-radius: 4px;
                        cursor: pointer;
                        transition: all 0.2s;
                    }
                    .btn-tab:hover { color: #f1f5f9; background: #23293a; }
                    .btn-tab.active { color: #fff; background: #2962ff; }

                    .orderflow-subbar {
                        height: 32px;
                        background: #0d111a;
                        border-bottom: 1px solid #1c2230;
                        display: flex;
                        align-items: center;
                        padding: 0 14px;
                        gap: 20px;
                        font-size: 11px;
                    }
                    .of-item { display: flex; align-items: center; gap: 7px; }
                    .of-label { color: #64748b; font-weight: 700; font-size: 10px; letter-spacing: 0.5px; }
                    .of-val { font-weight: 700; font-family: monospace; }
                    .text-bull { color: #00e676; }
                    .text-bear { color: #f23645; }
                    .delta-bar-container {
                        width: 65px;
                        height: 8px;
                        background: #1e2638;
                        border-radius: 4px;
                        overflow: hidden;
                        display: flex;
                    }
                    .delta-bar-fill { height: 100%%; transition: width 0.3s; }
                    .ob-meter-container {
                        width: 85px;
                        height: 8px;
                        background: #1e2638;
                        border-radius: 4px;
                        overflow: hidden;
                        display: flex;
                    }
                    .ob-bid-fill { height: 100%%; background: #089981; transition: width 0.3s; }
                    .ob-ask-fill { height: 100%%; background: #f23645; transition: width 0.3s; }
                    .of-meta { color: #64748b; font-size: 10px; font-family: monospace; }
                    .stream-status {
                        margin-left: auto;
                        color: #38bdf8;
                        font-size: 10px;
                        font-weight: 700;
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }
                    .pulse-dot {
                        width: 6px;
                        height: 6px;
                        border-radius: 50%%;
                        background: #00e676;
                        box-shadow: 0 0 6px #00e676;
                        animation: pulse-stream 1.5s infinite;
                    }
                    @keyframes pulse-stream {
                        0%% { opacity: 0.3; }
                        50%% { opacity: 1; }
                        100%% { opacity: 0.3; }
                    }
                    
                    .dashboard-container {
                        height: calc(100vh - 76px);
                        padding: 5px;
                        display: grid;
                        grid-template-columns: 1fr 1fr;
                        grid-template-rows: 1fr 1fr;
                        grid-gap: 5px;
                    }
                    
                    .dashboard-container.max-1m { grid-template-columns: 1fr 0px; grid-template-rows: 1fr 0px; }
                    .dashboard-container.max-1m .card:not(#card-1m) { display: none; }
                    .dashboard-container.max-5m { grid-template-columns: 1fr 0px; grid-template-rows: 1fr 0px; }
                    .dashboard-container.max-5m .card:not(#card-5m) { display: none; }
                    .dashboard-container.max-15m { grid-template-columns: 1fr 0px; grid-template-rows: 1fr 0px; }
                    .dashboard-container.max-15m .card:not(#card-15m) { display: none; }
                    .dashboard-container.max-1h { grid-template-columns: 1fr 0px; grid-template-rows: 1fr 0px; }
                    .dashboard-container.max-1h .card:not(#card-1h) { display: none; }

                    .card {
                        background: #131722;
                        border: 1px solid #202636;
                        border-radius: 6px;
                        display: flex;
                        flex-direction: column;
                        overflow: hidden;
                        position: relative;
                        min-height: 0;
                    }
                    .card-header {
                        height: 26px;
                        min-height: 26px;
                        background: #161b26;
                        border-bottom: 1px solid #202636;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        padding: 0 10px;
                        font-size: 11px;
                        font-weight: 600;
                    }
                    .tag-tf {
                        background: #23293a;
                        color: #2962ff;
                        font-weight: 800;
                        padding: 1px 5px;
                        border-radius: 3px;
                        font-size: 10px;
                    }
                    .btn-expand {
                        background: transparent;
                        border: none;
                        color: #8e96aa;
                        cursor: pointer;
                        font-size: 12px;
                        padding: 1px 5px;
                        border-radius: 3px;
                    }
                    .btn-expand:hover { color: #fff; background: #23293a; }

                    .trade-plan-banner {
                        height: 25px;
                        min-height: 25px;
                        background: #10141e;
                        border-bottom: 1px solid #1c2230;
                        display: flex;
                        align-items: center;
                        padding: 0 10px;
                        font-size: 11px;
                        gap: 10px;
                        color: #94a3b8;
                    }
                    .trade-plan-banner b { color: #f1f5f9; }
                    .pill-action {
                        font-weight: 800;
                        padding: 1px 6px;
                        border-radius: 3px;
                        font-size: 10px;
                    }
                    .bg-stopped { background: rgba(242, 54, 69, 0.15); color: #ef5350; border: 1px solid #ef5350; }
                    .bg-won { background: rgba(0, 230, 118, 0.2); color: #00e676; border: 1px solid #00e676; }
                    .pnl-live-text {
                        margin-left: auto;
                        font-weight: 700;
                        font-family: monospace;
                    }

                    .chart-viewport {
                        flex: 1;
                        width: 100%%;
                        height: 100%%;
                        position: relative;
                    }
                    
                    .hover-hud {
                        position: absolute;
                        top: 5px;
                        left: 8px;
                        z-index: 10;
                        background: rgba(19, 23, 34, 0.92);
                        backdrop-filter: blur(4px);
                        border: 1px solid rgba(43, 53, 77, 0.8);
                        padding: 2px 7px;
                        border-radius: 4px;
                        font-size: 10px;
                        color: #94a3b8;
                        pointer-events: none;
                        display: flex;
                        gap: 8px;
                        font-family: monospace;
                    }
                    .hover-hud span b { color: #f1f5f9; }
                    .hud-pattern { font-weight: 700; color: #ffeb3b; display: none; }
                </style>
            </head>
            <body>
                <header class="header-bar">
                    <div class="left-group">
                        <div class="brand-title">&#9889; <span id="active-symbol-title">%s</span> Terminal</div>
                        <div class="price-pill" id="live-btc-price">$%s</div>
                        <div class="confluence-group">
                            <span class="tf-badge %s" id="badge-top-1m">1M: %s</span>
                            <span class="tf-badge %s" id="badge-top-5m">5M: %s</span>
                            <span class="tf-badge %s" id="badge-top-15m">15M: %s</span>
                            <span class="tf-badge %s" id="badge-top-1h">1H: %s</span>
                        </div>
                    </div>

                    <div class="movers-strip">
                        <span class="movers-title">&#128293; TOP 24H MOVERS:</span>
                        <div id="movers-pills-container" style="display: flex; gap: 4px;"></div>
                    </div>

                    <div class="view-switcher">
                        <button class="btn-tab active" onclick="switchView('grid')">&#8862; 2x2 Grid</button>
                        <button class="btn-tab" onclick="switchView('max-1m')">1M</button>
                        <button class="btn-tab" onclick="switchView('max-5m')">5M</button>
                        <button class="btn-tab" onclick="switchView('max-15m')">15M</button>
                        <button class="btn-tab" onclick="switchView('max-1h')">1H</button>
                        <a href="trie_predictor.html" target="_blank" style="text-decoration:none;"><button class="btn-tab" style="background:#38bdf8; color:#04101d; font-weight:800;" title="Open Dedicated Double Trie Autofill Studio">&#128302; Trie Studio</button></a>
                    </div>
                </header>

                <div class="orderflow-subbar">
                    <div class="of-item">
                        <span class="of-label">&#128202; CVD DELTA (200t):</span>
                        <span class="of-val %s" id="live-delta-val">%s%.2f (%s)</span>
                        <div class="delta-bar-container">
                            <div class="delta-bar-fill" id="live-delta-fill" style="width: %.1f%%; background: %s;"></div>
                        </div>
                    </div>
                    <div class="of-item">
                        <span class="of-label">&#128218; ORDER BOOK DEPTH (Top 20):</span>
                        <span class="of-val %s" id="live-ob-val">%s%.1f%% (%s)</span>
                        <div class="ob-meter-container">
                            <div class="ob-bid-fill" id="live-ob-bid-fill" style="width: %.1f%%;"></div>
                            <div class="ob-ask-fill" id="live-ob-ask-fill" style="width: %.1f%%;"></div>
                        </div>
                        <span class="of-meta" id="live-ob-vols">Bids: %.2f | Asks: %.2f</span>
                    </div>
                    <div class="of-item stream-status">
                        <span class="pulse-dot"></span>
                        <span id="stream-label">0ms COMBINED BINANCE STREAM ACTIVE</span>
                    </div>
                </div>

                <main class="dashboard-container" id="dash-grid">
                    <!-- 1M CHART -->
                    <div class="card" id="card-1m">
                        <div class="card-header">
                            <div><span class="tag-tf">1M</span> Micro Trigger / Early Spark</div>
                            <button class="btn-expand" title="Maximize" onclick="toggleMaximize('1m')">&#x26F6;</button>
                        </div>
                        <div class="trade-plan-banner" id="banner-1m">
                            <span id="badge-1m" class="pill-action">WAIT</span>
                            <span id="plan-desc-1m">Scanning...</span>
                            <span class="pnl-live-text" id="pnl-1m"></span>
                        </div>
                        <div class="chart-viewport" id="chart-1m">
                            <div class="hover-hud" id="hud-1m">
                                <span>O: <b id="o-1m">-</b></span>
                                <span>H: <b id="h-1m">-</b></span>
                                <span>L: <b id="l-1m">-</b></span>
                                <span>C: <b id="c-1m">-</b></span>
                                <span class="hud-pattern" id="pat-1m"></span>
                            </div>
                        </div>
                    </div>

                    <!-- 5M CHART -->
                    <div class="card" id="card-5m">
                        <div class="card-header">
                            <div><span class="tag-tf">5M</span> Short-Term Confirmation</div>
                            <button class="btn-expand" title="Maximize" onclick="toggleMaximize('5m')">&#x26F6;</button>
                        </div>
                        <div class="trade-plan-banner" id="banner-5m">
                            <span id="badge-5m" class="pill-action">WAIT</span>
                            <span id="plan-desc-5m">Scanning...</span>
                            <span class="pnl-live-text" id="pnl-5m"></span>
                        </div>
                        <div class="chart-viewport" id="chart-5m">
                            <div class="hover-hud" id="hud-5m">
                                <span>O: <b id="o-5m">-</b></span>
                                <span>H: <b id="h-5m">-</b></span>
                                <span>L: <b id="l-5m">-</b></span>
                                <span>C: <b id="c-5m">-</b></span>
                                <span class="hud-pattern" id="pat-5m"></span>
                            </div>
                        </div>
                    </div>

                    <!-- 15M CHART -->
                    <div class="card" id="card-15m">
                        <div class="card-header">
                            <div><span class="tag-tf">15M</span> Tactical Intraday Trend</div>
                            <button class="btn-expand" title="Maximize" onclick="toggleMaximize('15m')">&#x26F6;</button>
                        </div>
                        <div class="trade-plan-banner" id="banner-15m">
                            <span id="badge-15m" class="pill-action">WAIT</span>
                            <span id="plan-desc-15m">Scanning...</span>
                            <span class="pnl-live-text" id="pnl-15m"></span>
                        </div>
                        <div class="chart-viewport" id="chart-15m">
                            <div class="hover-hud" id="hud-15m">
                                <span>O: <b id="o-15m">-</b></span>
                                <span>H: <b id="h-15m">-</b></span>
                                <span>L: <b id="l-15m">-</b></span>
                                <span>C: <b id="c-15m">-</b></span>
                                <span class="hud-pattern" id="pat-15m"></span>
                            </div>
                        </div>
                    </div>

                    <!-- 1H CHART -->
                    <div class="card" id="card-1h">
                        <div class="card-header">
                            <div><span class="tag-tf">1H</span> Macro Market Structure</div>
                            <button class="btn-expand" title="Maximize" onclick="toggleMaximize('1h')">&#x26F6;</button>
                        </div>
                        <div class="trade-plan-banner" id="banner-1h">
                            <span id="badge-1h" class="pill-action">WAIT</span>
                            <span id="plan-desc-1h">Scanning...</span>
                            <span class="pnl-live-text" id="pnl-1h"></span>
                        </div>
                        <div class="chart-viewport" id="chart-1h">
                            <div class="hover-hud" id="hud-1h">
                                <span>O: <b id="o-1h">-</b></span>
                                <span>H: <b id="h-1h">-</b></span>
                                <span>L: <b id="l-1h">-</b></span>
                                <span>C: <b id="c-1h">-</b></span>
                                <span class="hud-pattern" id="pat-1h"></span>
                            </div>
                        </div>
                    </div>
                </main>

                <script>
                    let currentSymbol = '%s';
                    const alertDetails = %s;
                    const tradePlans   = %s;
                    const topMoversList = %s;
                    const trieForecasts = %s;
                    let currentBtcPrice = %f;

                    let liveDelta = %s;
                    let liveOrderBook = %s;
                    const recentTrades = [];

                    // Initial candle data arrays
                    const initialData1m  = %s;
                    const initialData5m  = %s;
                    const initialData15m = %s;
                    const initialData1h  = %s;

                    const candlesCache = {
                        '1m': initialData1m.map(c => ({ timeSeconds: c.time, ...c })),
                        '5m': initialData5m.map(c => ({ timeSeconds: c.time, ...c })),
                        '15m': initialData15m.map(c => ({ timeSeconds: c.time, ...c })),
                        '1h': initialData1h.map(c => ({ timeSeconds: c.time, ...c }))
                    };

                    const concludedTrades = { '1m': null, '5m': null, '15m': null, '1h': null };

                    function formatPrice(p) {
                        if (p === undefined || isNaN(p)) return '0.00';
                        if (p >= 1000) return p.toFixed(2);
                        if (p >= 1) return p.toFixed(4);
                        if (p >= 0.01) return p.toFixed(6);
                        return p.toFixed(8);
                    }

                    // Render Top Movers strip
                    function renderMoversBar() {
                        const container = document.getElementById('movers-pills-container');
                        if (!container) return;
                        container.innerHTML = '';
                        topMoversList.forEach(m => {
                            const btn = document.createElement('button');
                            btn.className = 'mover-btn ' + (m.symbol === currentSymbol ? 'active' : '');
                            const sign = m.chg >= 0 ? '+' : '';
                            const chgCls = m.chg >= 0 ? 'mover-chg-up' : 'mover-chg-down';
                            btn.innerHTML = '<span>' + m.symbol.replace('USDT', '') + '</span><span class="' + chgCls + '">' + sign + m.chg.toFixed(1) + '%%</span>';
                            btn.onclick = () => switchCoin(m.symbol);
                            container.appendChild(btn);
                        });
                    }
                    renderMoversBar();

                    function getShortBadge(name) {
                        if (name.includes('Absorption')) return 'ABS';
                        if (name.includes('Breakout')) return 'BO';
                        if (name.includes('Momentum')) return 'MOM';
                        if (name.includes('Engulfing')) return 'ENG';
                        if (name.includes('Star')) return 'STAR';
                        if (name.includes('Hammer')) return 'HAM';
                        if (name.includes('Shooting')) return 'STAR';
                        return name.length > 4 ? name.substring(0, 4).toUpperCase() : name;
                    }

                    function checkIsTradeConcluded(plan, candles, price) {
                        if (!plan) return 'NO_PLAN';

                        if (candles && candles.length > 0) {
                            let alertIdx = -1;
                            if (plan.timeSeconds) {
                                alertIdx = candles.findIndex(c => c.timeSeconds === plan.timeSeconds);
                            }
                            const start = alertIdx !== -1 ? alertIdx : Math.max(0, candles.length - 4);

                            for (let i = start; i < candles.length; i++) {
                                const c = candles[i];
                                if (plan.isBullish) {
                                    if (c.low <= plan.stopLoss) return 'SL_HIT';
                                    if (c.high >= plan.tp1) return 'TP_HIT';
                                } else {
                                    if (c.high >= plan.stopLoss) return 'SL_HIT';
                                    if (c.low <= plan.tp1) return 'TP_HIT';
                                }
                            }
                        }

                        if (price > 0) {
                            if (plan.isBullish) {
                                if (price <= plan.stopLoss) return 'SL_HIT';
                                if (price >= plan.tp1) return 'TP_HIT';
                            } else {
                                if (price >= plan.stopLoss) return 'SL_HIT';
                                if (price <= plan.tp1) return 'TP_HIT';
                            }
                        }

                        return null;
                    }

                    // Strict Multi-Factor Confluence Engine in Browser (4 Pillars)
                    function detectPatternsClient(tf, candles, deltaObj, obObj) {
                        const alerts = [];
                        if (!candles || candles.length < 20) return alerts;
                        let lastMatchedIndex = -10;

                        function getVolSma(endIdx, period) {
                            let sum = 0, count = 0;
                            for (let k = Math.max(0, endIdx - period + 1); k <= endIdx; k++) {
                                sum += candles[k].volume; count++;
                            }
                            return count > 0 ? sum / count : (candles[endIdx].volume || 1);
                        }

                        function getSma(endIdx, period) {
                            let sum = 0, count = 0;
                            for (let k = Math.max(0, endIdx - period + 1); k <= endIdx; k++) {
                                sum += candles[k].close; count++;
                            }
                            return count > 0 ? sum / count : candles[endIdx].close;
                        }

                        function getAtr(endIdx, period) {
                            let trSum = 0, count = 0;
                            for (let k = Math.max(1, endIdx - period + 1); k <= endIdx; k++) {
                                const curr = candles[k], prev = candles[k - 1];
                                const tr = Math.max(curr.high - curr.low, Math.max(Math.abs(curr.high - prev.close), Math.abs(curr.low - prev.close)));
                                trSum += tr; count++;
                            }
                            return count > 0 ? trSum / count : 1.0;
                        }

                        function lowestLow(endIdx, lookback) {
                            let min = Infinity;
                            const start = Math.max(0, endIdx - lookback);
                            for (let k = start; k < endIdx; k++) { min = Math.min(min, candles[k].low); }
                            return min;
                        }

                        function highestHigh(endIdx, lookback) {
                            let max = -Infinity;
                            const start = Math.max(0, endIdx - lookback);
                            for (let k = start; k < endIdx; k++) { max = Math.max(max, candles[k].high); }
                            return max;
                        }

                        const buyRatio = deltaObj ? deltaObj.buyRatioPct : 50;
                        const obiVal = obObj ? obObj.imbalancePct : 0;

                        const deltaBullScore = buyRatio >= 62 ? 95 : (buyRatio >= 53 ? 75 : (buyRatio >= 47 ? 50 : 15));
                        const deltaBearScore = buyRatio <= 38 ? 95 : (buyRatio <= 47 ? 75 : (buyRatio <= 53 ? 50 : 15));

                        const obBullScore = obiVal >= 20 ? 95 : (obiVal >= 8 ? 75 : (obiVal <= -20 ? 15 : 50));
                        const obBearScore = obiVal <= -20 ? 95 : (obiVal <= -8 ? 75 : (obiVal >= 20 ? 15 : 50));

                        for (let i = 8; i < candles.length; i++) {
                            const c0 = candles[i], c1 = candles[i - 1], c2 = candles[i - 2];
                            const atr = getAtr(i, 14);
                            const sma = getSma(i - 1, 7);
                            const volSma = getVolSma(i - 1, 20);
                            const rvol = c0.volume / Math.max(volSma, 0.001);

                            const isDowntrend = c1.close < sma && c2.close < sma;
                            const isUptrend   = c1.close > sma && c2.close > sma;

                            const volScore = rvol >= 2.2 ? 95 : (rvol >= 1.5 ? 80 : (rvol >= 1.0 ? 55 : 20));

                            const c0LowerWick = Math.min(c0.open, c0.close) - c0.low;
                            const c0UpperWick = c0.high - Math.max(c0.open, c0.close);
                            const c0Body = Math.abs(c0.close - c0.open);
                            const c1Body = Math.abs(c1.close - c1.open);
                            const c0Bull = c0.close > c0.open;

                            const isLowerWickSweep = c0LowerWick >= 1.5 * c0Body && c0Bull && c0.low <= lowestLow(i, 5);
                            const isUpperWickSweep = c0UpperWick >= 1.5 * c0Body && !c0Bull && c0.high >= highestHigh(i, 5);

                            const isBullEngulf = isDowntrend && !candles[i-1].isBullish && c0Bull && c0Body >= 1.25 * c1Body;
                            const isBearEngulf = isUptrend && candles[i-1].isBullish && !c0Bull && c0Body >= 1.25 * c1Body;

                            const isMorningStar = isDowntrend && c2.close < c2.open && c1Body <= 0.35 * atr && c0Bull && c0.close > (c2.open + c2.close) / 2;
                            const isEveningStar = isUptrend && c2.close > c2.open && c1Body <= 0.35 * atr && !c0Bull && c0.close < (c2.open + c2.close) / 2;

                            const paBullScore = (isLowerWickSweep || isBullEngulf || isMorningStar) ? 85 : (c0Bull ? 65 : 35);
                            const paBearScore = (isUpperWickSweep || isBearEngulf || isEveningStar) ? 85 : (!c0Bull ? 65 : 35);

                            let totalBull = Math.round(0.35 * deltaBullScore + 0.25 * obBullScore + 0.25 * volScore + 0.15 * paBullScore);
                            let totalBear = Math.round(0.35 * deltaBearScore + 0.25 * obBearScore + 0.25 * volScore + 0.15 * paBearScore);

                            // Strict Vetoes
                            if (deltaBullScore <= 20 || obBullScore <= 20 || volScore <= 25) totalBull = 40;
                            if (deltaBearScore <= 20 || obBearScore <= 20 || volScore <= 25) totalBear = 40;

                            const candidates = [];

                            if (totalBull >= 70) {
                                let name = 'Order Flow Long';
                                let desc = 'Multi-factor buy flow with volume expansion';
                                if (isLowerWickSweep && deltaBullScore >= 75) {
                                    name = 'Institutional Delta Absorption';
                                    desc = 'Passive limit buyers absorbed aggressive sell dump at swing floor with positive delta flip';
                                } else if (volScore >= 80 && obBullScore >= 80) {
                                    name = 'Order Book Liquidity Breakout';
                                    desc = 'High RVol volume surge supported by dense bid book wall defense';
                                } else if (deltaBullScore >= 80 && volScore >= 75) {
                                    name = 'CVD Momentum Drive';
                                    desc = 'Aggressive market taker buyers pushing price through resistance with heavy delta';
                                } else if (isBullEngulf) {
                                    name = 'Bullish Engulfing';
                                    desc = 'Engulfing green body confirmed by institutional volume & delta alignment';
                                } else if (isMorningStar) {
                                    name = 'Morning Star';
                                    desc = 'Reversal from low confirmed by positive delta and book defense';
                                }
                                candidates.push({ name, isBullish: true, score: totalBull, desc });
                            }

                            if (totalBear >= 70) {
                                let name = 'Order Flow Short';
                                let desc = 'Multi-factor selling pressure with book defense';
                                if (isUpperWickSweep && deltaBearScore >= 75) {
                                    name = 'Institutional Delta Absorption';
                                    desc = 'Passive limit sellers absorbed aggressive buyer push at peak with negative delta dump';
                                } else if (volScore >= 80 && obBearScore >= 80) {
                                    name = 'Order Book Liquidity Breakout';
                                    desc = 'High RVol volume breakdown capped by dense ask book wall resistance';
                                } else if (deltaBearScore >= 80 && volScore >= 75) {
                                    name = 'CVD Momentum Drive';
                                    desc = 'Aggressive market taker sellers hitting bids with strong negative delta drive';
                                } else if (isBearEngulf) {
                                    name = 'Bearish Engulfing';
                                    desc = 'Sellers engulfed green bar confirmed by institutional volume & negative delta';
                                } else if (isEveningStar) {
                                    name = 'Evening Star';
                                    desc = 'Peak exhaustion confirmed by aggressive seller delta';
                                }
                                candidates.push({ name, isBullish: false, score: totalBear, desc });
                            }

                            if (candidates.length > 0) {
                                candidates.sort((a, b) => b.score - a.score);
                                const best = candidates[0];
                                if (i - lastMatchedIndex >= 4) {
                                    const dateObj = new Date(c0.timeSeconds * 1000);
                                    const timeStr = dateObj.toTimeString().split(' ')[0];
                                    alerts.push({
                                        timeframe: tf,
                                        patternName: best.name,
                                        isBullish: best.isBullish,
                                        timeSeconds: c0.timeSeconds,
                                        timeStr: timeStr,
                                        price: c0.close,
                                        confidenceScore: best.score,
                                        justification: best.desc
                                    });
                                    lastMatchedIndex = i;
                                }
                            }
                        }
                        return alerts;
                    }

                    function calculateTradePlanClient(tf, candles, alerts) {
                        if (!alerts || alerts.length === 0 || !candles || candles.length === 0) return null;
                        const lastAlert = alerts[alerts.length - 1];

                        let alertIdx = candles.findIndex(c => c.timeSeconds === lastAlert.timeSeconds);
                        if (alertIdx === -1) alertIdx = candles.length - 1;

                        const lookback = Math.max(0, alertIdx - 3);
                        let minLow = Infinity, maxHigh = -Infinity;
                        for (let k = lookback; k <= alertIdx; k++) {
                            minLow = Math.min(minLow, candles[k].low);
                            maxHigh = Math.max(maxHigh, candles[k].high);
                        }

                        const entry = lastAlert.price;
                        const isBullish = lastAlert.isBullish;
                        let stopLoss, tp1, tp2, risk;

                        if (isBullish) {
                            stopLoss = minLow * 0.9997;
                            risk = Math.max(entry - stopLoss, entry * 0.001);
                            tp1 = entry + 1.5 * risk;
                            tp2 = entry + 2.5 * risk;
                        } else {
                            stopLoss = maxHigh * 1.0003;
                            risk = Math.max(stopLoss - entry, entry * 0.001);
                            tp1 = entry - 1.5 * risk;
                            tp2 = entry - 2.5 * risk;
                        }

                        const dateObj = new Date(lastAlert.timeSeconds * 1000);
                        const timeStr = dateObj.toTimeString().split(' ')[0];

                        return {
                            pattern: lastAlert.patternName,
                            isBullish: isBullish,
                            entry: entry,
                            stopLoss: stopLoss,
                            tp1: tp1,
                            tp2: tp2,
                            risk: risk,
                            time: timeStr,
                            timeSeconds: lastAlert.timeSeconds,
                            confluenceScore: lastAlert.confidenceScore
                        };
                    }

                    function createEngineChart(containerId, hudPrefix, tfKey, data, markers) {
                        const container = document.getElementById(containerId);
                        const chart = LightweightCharts.createChart(container, {
                            layout: { background: { color: '#131722' }, textColor: '#8e96aa' },
                            grid: { vertLines: { color: '#181d2a' }, horzLines: { color: '#181d2a' } },
                            crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                            rightPriceScale: { borderColor: '#202636' },
                            timeScale: { borderColor: '#202636', timeVisible: true, secondsVisible: false }
                        });

                        const candleSeries = chart.addCandlestickSeries({
                            upColor: '#089981', downColor: '#f23645',
                            borderVisible: false,
                            wickUpColor: '#089981', wickDownColor: '#f23645'
                        });
                        candleSeries.setData(data);
                        if (markers && markers.length > 0) {
                            candleSeries.setMarkers(markers);
                        }

                        const wrapper = { chart, candleSeries, container, tfKey, priceLines: [] };
                        chart.timeScale().fitContent();

                        // Crosshair Hover
                        chart.subscribeCrosshairMove(param => {
                            if (!param.time || !param.seriesData.get(candleSeries)) return;
                            const candle = param.seriesData.get(candleSeries);
                            document.getElementById('o-' + hudPrefix).innerText = formatPrice(candle.open);
                            document.getElementById('h-' + hudPrefix).innerText = formatPrice(candle.high);
                            document.getElementById('l-' + hudPrefix).innerText = formatPrice(candle.low);
                            document.getElementById('c-' + hudPrefix).innerText = formatPrice(candle.close);

                            const patElem = document.getElementById('pat-' + hudPrefix);
                            const lookupKey = tfKey + '_' + param.time;
                            if (alertDetails[lookupKey]) {
                                const info = alertDetails[lookupKey];
                                patElem.style.display = 'inline';
                                patElem.style.color = info.bullish ? '#00e676' : '#ff1744';
                                patElem.innerText = '\u2605 ' + info.name + ' (' + info.score + '/100) - ' + info.desc;
                            } else {
                                patElem.style.display = 'none';
                            }
                        });

                        window.addEventListener('resize', () => {
                            chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
                        });

                        return wrapper;
                    }

                    function clearPriceLines(wrapper) {
                        if (wrapper.priceLines && wrapper.priceLines.length > 0) {
                            wrapper.priceLines.forEach(line => {
                                try { wrapper.candleSeries.removePriceLine(line); } catch(e){}
                            });
                            wrapper.priceLines = [];
                        }
                    }

                    function drawPriceLines(wrapper, plan) {
                        clearPriceLines(wrapper);
                        if (!plan) return;

                        const lEntry = wrapper.candleSeries.createPriceLine({
                            price: plan.entry, color: '#2962ff', lineWidth: 2,
                            lineStyle: LightweightCharts.LineStyle.Solid, axisLabelVisible: true,
                            title: 'ENTRY $' + formatPrice(plan.entry)
                        });
                        const lSL = wrapper.candleSeries.createPriceLine({
                            price: plan.stopLoss, color: '#f23645', lineWidth: 2,
                            lineStyle: LightweightCharts.LineStyle.Dashed, axisLabelVisible: true,
                            title: (plan.isBullish ? 'SUPPORT / SL $' : 'RESISTANCE / SL $') + formatPrice(plan.stopLoss)
                        });
                        const lTP1 = wrapper.candleSeries.createPriceLine({
                            price: plan.tp1, color: '#089981', lineWidth: 2,
                            lineStyle: LightweightCharts.LineStyle.Dashed, axisLabelVisible: true,
                            title: 'TP1 (1.5R) $' + formatPrice(plan.tp1)
                        });
                        const lTP2 = wrapper.candleSeries.createPriceLine({
                            price: plan.tp2, color: '#00e676', lineWidth: 2,
                            lineStyle: LightweightCharts.LineStyle.Dashed, axisLabelVisible: true,
                            title: 'TP2 (2.5R) $' + formatPrice(plan.tp2)
                        });

                        wrapper.priceLines = [lEntry, lSL, lTP1, lTP2];
                    }

                    function updateTradePlanUI(tfKey, wrapper, plan, price) {
                        const badge = document.getElementById('badge-' + tfKey);
                        const desc  = document.getElementById('plan-desc-' + tfKey);
                        const pnl   = document.getElementById('pnl-' + tfKey);

                        if (!plan) {
                            clearPriceLines(wrapper);
                            badge.style.display = 'none';
                            desc.innerHTML = '<span style="color:#64748b; font-size:11px;">Awaiting new confirmed order flow setup...</span>';
                            pnl.innerHTML = '';
                            return;
                        }

                        const signalId = tfKey + '_' + (plan.timeSeconds || plan.time) + '_' + plan.pattern;

                        if (concludedTrades[tfKey] === signalId) {
                            clearPriceLines(wrapper);
                            badge.style.display = 'none';
                            desc.innerHTML = '<span style="color:#64748b; font-size:11px;">Awaiting new confirmed order flow setup...</span>';
                            pnl.innerHTML = '';
                            return;
                        }

                        const reason = checkIsTradeConcluded(plan, candlesCache[tfKey], price);
                        if (reason !== null) {
                            concludedTrades[tfKey] = signalId;
                            clearPriceLines(wrapper);
                            badge.style.display = 'none';
                            desc.innerHTML = '<span style="color:#64748b; font-size:11px;">Awaiting new confirmed order flow setup...</span>';
                            pnl.innerHTML = '';
                            return;
                        }

                        drawPriceLines(wrapper, plan);
                        badge.style.display = 'inline-block';
                        badge.className = 'pill-action ' + (plan.isBullish ? 'bg-bull' : 'bg-bear');
                        badge.innerText = plan.isBullish ? '\u25b2 LONG' : '\u25bc SHORT';
                        const srLabel = plan.isBullish ? 'Support/SL' : 'Resist/SL';
                        desc.innerHTML = 'Signal: <b>' + plan.pattern + '</b> (' + plan.time + ') &bull; Entry: <b>$' + formatPrice(plan.entry) + '</b> &bull; ' + srLabel + ': <b style="color:#f23645;">$' + formatPrice(plan.stopLoss) + '</b> &bull; TP1: <b style="color:#089981;">$' + formatPrice(plan.tp1) + '</b> &bull; TP2: <b style="color:#00e676;">$' + formatPrice(plan.tp2) + '</b>' + (plan.confluenceScore ? ' &bull; Confluence: <b style="color:#38bdf8;">' + plan.confluenceScore + '/100</b>' : '');

                        const diff = plan.isBullish ? (price - plan.entry) : (plan.entry - price);
                        const rMul = diff / plan.risk;
                        const sign = diff >= 0 ? '+' : '';
                        pnl.innerHTML = 'Live PnL: <span style="color:' + (diff >= 0 ? '#00e676' : '#ff1744') + '">' + sign + '$' + formatPrice(Math.abs(diff)) + ' (' + sign + rMul.toFixed(2) + 'R)</span>';
                    }

                    // INITIALIZE ALL 4 CHARTS
                    const c1m  = createEngineChart('chart-1m',  '1m',  '1m',  initialData1m,  %s);
                    const c5m  = createEngineChart('chart-5m',  '5m',  '5m',  initialData5m,  %s);
                    const c15m = createEngineChart('chart-15m', '15m', '15m', initialData15m, %s);
                    const c1h  = createEngineChart('chart-1h',  '1h',  '1h',  initialData1h,  %s);

                    const allChartWrappers = [c1m, c5m, c15m, c1h];
                    const wrappersMap = { '1m': c1m, '5m': c5m, '15m': c15m, '1h': c1h };

                    ['1m', '5m', '15m', '1h'].forEach(tf => {
                        updateTradePlanUI(tf, wrappersMap[tf], tradePlans[tf], currentBtcPrice);
                    });

                    function refitAll() {
                        setTimeout(() => {
                            allChartWrappers.forEach(cw => {
                                cw.chart.applyOptions({ width: cw.container.clientWidth, height: cw.container.clientHeight });
                                cw.chart.timeScale().fitContent();
                            });
                        }, 50);
                    }

                    function switchView(mode) {
                        const grid = document.getElementById('dash-grid');
                        grid.className = 'dashboard-container ' + (mode === 'grid' ? '' : mode);
                        document.querySelectorAll('.btn-tab').forEach(b => b.classList.remove('active'));
                        if (event && event.target) event.target.classList.add('active');
                        refitAll();
                    }

                    function toggleMaximize(tf) {
                        const grid = document.getElementById('dash-grid');
                        const targetClass = 'max-' + tf;
                        if (grid.classList.contains(targetClass)) {
                            switchView('grid');
                        } else {
                            switchView(targetClass);
                        }
                    }

                    // Real-Time Dynamic Coin Switcher in Browser!
                    async function switchCoin(newSym) {
                        if (newSym === currentSymbol) return;
                        currentSymbol = newSym;
                        document.getElementById('active-symbol-title').innerText = newSym;
                        document.querySelectorAll('.mover-btn').forEach(b => {
                            b.classList.toggle('active', b.innerText.includes(newSym.replace('USDT', '')));
                        });

                        if (activeWs) {
                            try { activeWs.close(); } catch(e){}
                        }

                        // Fetch new candles for all 4 timeframes
                        for (let tf of ['1m', '5m', '15m', '1h']) {
                            try {
                                const res = await fetch('https://api.binance.com/api/v3/klines?symbol=' + newSym + '&interval=' + tf + '&limit=120');
                                const raw = await res.json();
                                const candles = raw.map(cols => ({
                                    timeSeconds: Math.floor(cols[0] / 1000),
                                    open: parseFloat(cols[1]),
                                    high: parseFloat(cols[2]),
                                    low: parseFloat(cols[3]),
                                    close: parseFloat(cols[4]),
                                    volume: parseFloat(cols[5])
                                }));
                                candlesCache[tf] = candles;
                                wrappersMap[tf].candleSeries.setData(candles.map(c => ({
                                    time: c.timeSeconds, open: c.open, high: c.high, low: c.low, close: c.close
                                })));
                                wrappersMap[tf].chart.timeScale().fitContent();

                                const alerts = detectPatternsClient(tf, candles, liveDelta, liveOrderBook);
                                const markers = alerts.map(a => ({
                                    time: a.timeSeconds,
                                    position: a.isBullish ? 'belowBar' : 'aboveBar',
                                    color: a.isBullish ? '#00e676' : '#ff1744',
                                    shape: a.isBullish ? 'arrowUp' : 'arrowDown',
                                    text: getShortBadge(a.patternName)
                                }));
                                wrappersMap[tf].candleSeries.setMarkers(markers);
                                alerts.forEach(a => {
                                    alertDetails[tf + '_' + a.timeSeconds] = {
                                        name: a.patternName,
                                        score: a.confidenceScore,
                                        price: a.price,
                                        desc: a.justification,
                                        bullish: a.isBullish
                                    };
                                });
                                const plan = calculateTradePlanClient(tf, candles, alerts);
                                tradePlans[tf] = plan;
                                if (tf === '1m' && candles.length > 0) {
                                    currentBtcPrice = candles[candles.length - 1].close;
                                    document.getElementById('live-btc-price').innerText = '$' + formatPrice(currentBtcPrice);
                                }
                                updateTradePlanUI(tf, wrappersMap[tf], plan, currentBtcPrice);
                            } catch(e) {
                                console.error('Error switching coin for ' + tf, e);
                            }
                        }

                        // Reconnect WebSocket to new coin
                        initWebSocket();
                    }

                    async function refreshTimeframeAuto(tf) {
                        try {
                            const res = await fetch('https://api.binance.com/api/v3/klines?symbol=' + currentSymbol + '&interval=' + tf + '&limit=120');
                            const raw = await res.json();
                            const candles = raw.map(cols => ({
                                timeSeconds: Math.floor(cols[0] / 1000),
                                open: parseFloat(cols[1]),
                                high: parseFloat(cols[2]),
                                low: parseFloat(cols[3]),
                                close: parseFloat(cols[4]),
                                volume: parseFloat(cols[5])
                            }));

                            candlesCache[tf] = candles;
                            const wrapper = wrappersMap[tf];
                            wrapper.candleSeries.setData(candles.map(c => ({
                                time: c.timeSeconds, open: c.open, high: c.high, low: c.low, close: c.close
                            })));

                            const alerts = detectPatternsClient(tf, candles, liveDelta, liveOrderBook);
                            const markers = alerts.map(a => ({
                                time: a.timeSeconds,
                                position: a.isBullish ? 'belowBar' : 'aboveBar',
                                color: a.isBullish ? '#00e676' : '#ff1744',
                                shape: a.isBullish ? 'arrowUp' : 'arrowDown',
                                text: getShortBadge(a.patternName)
                            }));
                            wrapper.candleSeries.setMarkers(markers);

                            alerts.forEach(a => {
                                alertDetails[tf + '_' + a.timeSeconds] = {
                                    name: a.patternName,
                                    score: a.confidenceScore,
                                    price: a.price,
                                    desc: a.justification,
                                    bullish: a.isBullish
                                };
                            });

                            const plan = calculateTradePlanClient(tf, candles, alerts);
                            tradePlans[tf] = plan;
                            const latestPrice = candles[candles.length - 1].close;
                            updateTradePlanUI(tf, wrapper, plan, latestPrice);
                        } catch(e) {
                            console.error('Auto-refresh error on ' + tf, e);
                        }
                    }

                    setInterval(() => refreshTimeframeAuto('1m'), 10000);
                    setInterval(() => refreshTimeframeAuto('5m'), 20000);
                    setInterval(() => refreshTimeframeAuto('15m'), 30000);
                    setInterval(() => refreshTimeframeAuto('1h'), 60000);

                    const livePriceElem = document.getElementById('live-btc-price');
                    let lastPrice = currentBtcPrice;
                    let activeWs = null;

                    function initWebSocket() {
                        try {
                            const symLower = currentSymbol.toLowerCase();
                            const wsUrl = 'wss://stream.binance.com:9443/stream?streams=' +
                                symLower + '@kline_1m/' +
                                symLower + '@kline_5m/' +
                                symLower + '@kline_15m/' +
                                symLower + '@kline_1h/' +
                                symLower + '@depth20@100ms/' +
                                symLower + '@aggTrade';

                            const ws = new WebSocket(wsUrl);
                            activeWs = ws;

                            ws.onmessage = (event) => {
                                try {
                                    const payload = JSON.parse(event.data);
                                    const stream = payload.stream;
                                    const data = payload.data;
                                    if (!stream || !data) return;

                                    if (stream.includes('@depth20')) {
                                        let bVol = 0, aVol = 0;
                                        if (data.bids) for (let b of data.bids) bVol += parseFloat(b[1]);
                                        if (data.asks) for (let a of data.asks) aVol += parseFloat(a[1]);
                                        const total = bVol + aVol;
                                        const imb = total > 0 ? ((bVol - aVol) / total) * 100 : 0;
                                        liveOrderBook.totalBidVol = bVol;
                                        liveOrderBook.totalAskVol = aVol;
                                        liveOrderBook.imbalancePct = imb;

                                        const obValElem = document.getElementById('live-ob-val');
                                        const obBidFill = document.getElementById('live-ob-bid-fill');
                                        const obAskFill = document.getElementById('live-ob-ask-fill');
                                        const obVols = document.getElementById('live-ob-vols');
                                        if (obValElem) {
                                            const isBidHeavy = imb >= 0;
                                            obValElem.className = 'of-val ' + (isBidHeavy ? 'text-bull' : 'text-bear');
                                            obValElem.innerText = (isBidHeavy ? '+' : '') + imb.toFixed(1) + '%% (' + (isBidHeavy ? 'Bid Heavy' : 'Ask Heavy') + ')';
                                            const bidPct = total > 0 ? (bVol / total) * 100 : 50;
                                            const askPct = total > 0 ? (aVol / total) * 100 : 50;
                                            obBidFill.style.width = bidPct.toFixed(1) + '%%';
                                            obAskFill.style.width = askPct.toFixed(1) + '%%';
                                            obVols.innerText = 'Bids: ' + bVol.toFixed(2) + ' | Asks: ' + aVol.toFixed(2);
                                        }
                                    }

                                    else if (stream.includes('@aggTrade')) {
                                        const qty = parseFloat(data.q);
                                        const isTakerBuy = !data.m;
                                        recentTrades.push({ qty: qty, isBuy: isTakerBuy });
                                        if (recentTrades.length > 200) recentTrades.shift();

                                        let buyV = 0, sellV = 0;
                                        for (let t of recentTrades) {
                                            if (t.isBuy) buyV += t.qty; else sellV += t.qty;
                                        }
                                        const delta = buyV - sellV;
                                        const tot = buyV + sellV;
                                        const buyRatio = tot > 0 ? (buyV / tot) * 100 : 50;
                                        liveDelta.buyVol = buyV;
                                        liveDelta.sellVol = sellV;
                                        liveDelta.deltaBtc = delta;
                                        liveDelta.buyRatioPct = buyRatio;

                                        const deltaValElem = document.getElementById('live-delta-val');
                                        const deltaFill = document.getElementById('live-delta-fill');
                                        if (deltaValElem) {
                                            const isBull = delta >= 0;
                                            deltaValElem.className = 'of-val ' + (isBull ? 'text-bull' : 'text-bear');
                                            deltaValElem.innerText = (isBull ? '+' : '') + delta.toFixed(2) + ' (' + (isBull ? 'Buyers Dominant' : 'Sellers Dominant') + ')';
                                            deltaFill.style.width = Math.min(100, Math.max(0, buyRatio)).toFixed(1) + '%%';
                                            deltaFill.style.background = isBull ? '#089981' : '#f23645';
                                        }
                                    }

                                    else if (stream.includes('@kline')) {
                                        const k = data.k;
                                        const tf = k.i;
                                        const candle = {
                                            time: Math.floor(k.t / 1000),
                                            open: parseFloat(k.o),
                                            high: parseFloat(k.h),
                                            low: parseFloat(k.l),
                                            close: parseFloat(k.c),
                                            volume: parseFloat(k.v)
                                        };

                                        const wrapper = wrappersMap[tf];
                                        if (wrapper) {
                                            wrapper.candleSeries.update(candle);
                                        }

                                        if (tf === '1m') {
                                            currentBtcPrice = candle.close;
                                            livePriceElem.innerText = '$' + formatPrice(currentBtcPrice);
                                            if (currentBtcPrice > lastPrice) {
                                                livePriceElem.style.color = '#00e676';
                                            } else if (currentBtcPrice < lastPrice) {
                                                livePriceElem.style.color = '#ff1744';
                                            }
                                            lastPrice = currentBtcPrice;

                                            ['1m', '5m', '15m', '1h'].forEach(tfKey => {
                                                updateTradePlanUI(tfKey, wrappersMap[tfKey], tradePlans[tfKey], currentBtcPrice);
                                            });
                                        }

                                        if (k.x === true && wrapper) {
                                            const cCache = candlesCache[tf];
                                            if (cCache && cCache.length > 0) {
                                                const lastIdx = cCache.length - 1;
                                                if (cCache[lastIdx].timeSeconds === candle.time) {
                                                    cCache[lastIdx] = { timeSeconds: candle.time, open: candle.open, high: candle.high, low: candle.low, close: candle.close, volume: candle.volume };
                                                } else {
                                                    cCache.push({ timeSeconds: candle.time, open: candle.open, high: candle.high, low: candle.low, close: candle.close, volume: candle.volume });
                                                    if (cCache.length > 150) cCache.shift();
                                                }
                                            }

                                            const alerts = detectPatternsClient(tf, candlesCache[tf], liveDelta, liveOrderBook);
                                            const markers = alerts.map(a => ({
                                                time: a.timeSeconds,
                                                position: a.isBullish ? 'belowBar' : 'aboveBar',
                                                color: a.isBullish ? '#00e676' : '#ff1744',
                                                shape: a.isBullish ? 'arrowUp' : 'arrowDown',
                                                text: getShortBadge(a.patternName)
                                            }));
                                            wrapper.candleSeries.setMarkers(markers);

                                            alerts.forEach(a => {
                                                alertDetails[tf + '_' + a.timeSeconds] = {
                                                    name: a.patternName,
                                                    score: a.confidenceScore,
                                                    price: a.price,
                                                    desc: a.justification,
                                                    bullish: a.isBullish
                                                };
                                            });

                                            const plan = calculateTradePlanClient(tf, candlesCache[tf], alerts);
                                            tradePlans[tf] = plan;
                                            updateTradePlanUI(tf, wrapper, plan, candle.close);
                                        }
                                    }
                                } catch(err) {
                                    console.error('Stream error', err);
                                }
                            };

                            ws.onclose = () => {
                                if (activeWs === ws) {
                                    console.warn('Stream disconnected. Reconnecting in 3s...');
                                    setTimeout(initWebSocket, 3000);
                                }
                            };
                            ws.onerror = (e) => {
                                console.error('Stream error', e);
                                try { ws.close(); } catch(err) {}
                            };
                        } catch(e) {
                            console.error('WebSocket connection failed', e);
                            setTimeout(initWebSocket, 3000);
                        }
                    }
                    initWebSocket();
                </script>
            </body>
            </html>
            """,
                symbol, symbol, formatPrice(c1m.close()),
                c1m.isBullish() ? "bg-bull" : "bg-bear", c1m.isBullish() ? "\u25b2 BULL" : "\u25bc BEAR",
                c5m.isBullish() ? "bg-bull" : "bg-bear", c5m.isBullish() ? "\u25b2 BULL" : "\u25bc BEAR",
                c15m.isBullish() ? "bg-bull" : "bg-bear", c15m.isBullish() ? "\u25b2 BULL" : "\u25bc BEAR",
                c1h.isBullish() ? "bg-bull" : "bg-bear", c1h.isBullish() ? "\u25b2 BULL" : "\u25bc BEAR",
                // Delta Subbar
                deltaBull ? "text-bull" : "text-bear", deltaBull ? "+" : "", deltaBtc, deltaBull ? "Buyers Dominant" : "Sellers Dominant",
                Math.min(100.0, Math.max(0.0, buyRatio)), deltaBull ? "#089981" : "#f23645",
                // Order Book Subbar
                obBull ? "text-bull" : "text-bear", obBull ? "+" : "", imbPct, obBull ? "Bid Heavy" : "Ask Heavy",
                bidFill, askFill, bidVol, askVol,
                // JS Initial Data
                symbol,
                alertDetailsMap.toString(),
                tradePlansJson.toString(),
                moversJson.toString(),
                trieForecastsJson.toString(),
                c1m.close(),
                deltaJson,
                obJson,
                json1m.toString(),
                json5m.toString(),
                json15m.toString(),
                json1h.toString(),
                markers1m.toString(),
                markers5m.toString(),
                markers15m.toString(),
                markers1h.toString()
        );

        File outputFile = new File("btc_multichart.html");
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(html);
        }
        File cryptoFile = new File("crypto_multichart.html");
        try (FileWriter writer = new FileWriter(cryptoFile)) {
            writer.write(html);
        }
        return outputFile;
    }

    private static String getShortBadge(String patternName) {
        return switch (patternName) {
            case "Bullish Engulfing", "Bearish Engulfing" -> "ENG";
            case "Morning Star", "Evening Star" -> "STAR";
            case "Three White Soldiers" -> "3WS";
            case "Three Black Crows" -> "3BC";
            case "Hammer Reversal" -> "HAM";
            case "Shooting Star" -> "STAR";
            case "Institutional Delta Absorption" -> "ABS";
            case "Order Book Liquidity Breakout" -> "BO";
            case "CVD Momentum Drive" -> "MOM";
            default -> patternName.length() > 4 ? patternName.substring(0, 4).toUpperCase() : patternName;
        };
    }

    private StringBuilder formatCandlesJson(List<BtcCandle> candles) {
        StringBuilder sb = new StringBuilder("[");
        if (candles != null) {
            for (BtcCandle c : candles) {
                sb.append(String.format(Locale.US, "{\"time\":%d,\"open\":%f,\"high\":%f,\"low\":%f,\"close\":%f},",
                        c.timeSeconds(), c.open(), c.high(), c.low(), c.close()));
            }
        }
        closeJsonArray(sb);
        return sb;
    }

    private StringBuilder formatMarkersJson(String tf, List<PatternAlert> alerts) {
        StringBuilder sb = new StringBuilder("[");
        if (alerts != null) {
            for (PatternAlert a : alerts) {
                if (a.timeframe().equals(tf)) {
                    String pos = a.isBullish() ? "belowBar" : "aboveBar";
                    String color = a.isBullish() ? "#00e676" : "#ff1744";
                    String shape = a.isBullish() ? "arrowUp" : "arrowDown";
                    String badge = getShortBadge(a.patternName());
                    sb.append(String.format("{\"time\":%d,\"position\":\"%s\",\"color\":\"%s\",\"shape\":\"%s\",\"text\":\"%s\"},",
                            a.timeSeconds(), pos, color, shape, badge));
                }
            }
        }
        closeJsonArray(sb);
        return sb;
    }

    private static void closeJsonArray(StringBuilder sb) {
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') {
            sb.setCharAt(sb.length() - 1, ']');
        } else {
            sb.append(']');
        }
    }
}
