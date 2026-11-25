import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TradingApp {

    public static void main(String[] args) {
        try {
            NseMcpClient bhavcopyClient = NseMcpClient.bhavcopy();

            // 1. Fetch 2 Years of Daily Candles for RELIANCE from NSE
            System.out.println("==========================================================================================");
            System.out.println("            FETCHING 2 YEARS OF OFFICIAL NSE DAILY CANDLES FOR RELIANCE                  ");
            System.out.println("==========================================================================================");
            List<Candle> allCandles = new ArrayList<>();
            String currentEndDate = "today";
            int totalChunks = 8; // 8 chunks * 3 months = 24 months

            for (int chunk = 1; chunk <= totalChunks; chunk++) {
                String historyChunk = bhavcopyClient.getStockHistory("RELIANCE", 3, currentEndDate);
                allCandles.addAll(Candle.parseFromHistoryJson(historyChunk));

                Matcher matcher = Pattern.compile("\"next_end_date\"\\s*:\\s*\"([^\"]+)\"").matcher(historyChunk);
                if (matcher.find()) {
                    currentEndDate = matcher.group(1);
                } else {
                    break;
                }
            }

            allCandles.sort(Comparator.comparing(Candle::date));
            System.out.printf("Loaded %d daily bars from %s to %s%n%n",
                    allCandles.size(), allCandles.get(0).date(), allCandles.get(allCandles.size() - 1).date());

            // -------------------------------------------------------------------------------------
            // 2. Classify Every Candle into 15 Single-Candle Types & Show Frequency Distribution
            // -------------------------------------------------------------------------------------
            System.out.println("==========================================================================================");
            System.out.println("                 PART 1: THE 15 SINGLE CANDLESTICK TYPES IN RELIANCE                      ");
            System.out.println("==========================================================================================");
            Map<Candle.Type, Integer> singleCounts = new EnumMap<>(Candle.Type.class);
            for (Candle.Type t : Candle.Type.values()) {
                singleCounts.put(t, 0);
            }
            for (Candle c : allCandles) {
                Candle.Type t = c.getCandleType();
                singleCounts.put(t, singleCounts.get(t) + 1);
            }

            System.out.printf("%-24s | %-6s | %-50s%n", "Candlestick Type", "Count", "Description / Trading Significance");
            System.out.println("------------------------------------------------------------------------------------------");
            for (Candle.Type t : Candle.Type.values()) {
                int count = singleCounts.get(t);
                System.out.printf("%-24s | %-6d | %-50s%n", t.getDisplayName(), count, t.getDescription());
            }

            // -------------------------------------------------------------------------------------
            // 3. Detect 15 Classic Multi-Candlestick Patterns & Compute Win Rates & Returns
            // -------------------------------------------------------------------------------------
            System.out.println("\n==========================================================================================");
            System.out.println("          PART 2: 15 MULTI-CANDLESTICK PATTERNS (WIN RATES & FORWARD RETURNS)             ");
            System.out.println("==========================================================================================");

            List<CandlePatternDetector.PatternSignal> signals = CandlePatternDetector.detectAll(allCandles);

            // Group signals by pattern name
            Map<String, List<CandlePatternDetector.PatternSignal>> grouped = new LinkedHashMap<>();
            for (CandlePatternDetector.PatternSignal s : signals) {
                grouped.computeIfAbsent(s.patternName(), k -> new ArrayList<>()).add(s);
            }

            System.out.printf("%-22s | %-8s | %-5s | %-10s | %-12s | %-10s | %-12s%n",
                    "Pattern Name", "Bias", "Count", "5D Win %", "Avg 5D Ret", "10D Win %", "Avg 10D Ret");
            System.out.println("------------------------------------------------------------------------------------------");

            List<ChartGenerator.ChartMarker> chartMarkers = new ArrayList<>();

            for (Map.Entry<String, List<CandlePatternDetector.PatternSignal>> entry : grouped.entrySet()) {
                String name = entry.getKey();
                List<CandlePatternDetector.PatternSignal> list = entry.getValue();
                CandlePatternDetector.Bias bias = list.get(0).bias();
                boolean isBullish = (bias == CandlePatternDetector.Bias.BULLISH);

                int win5 = 0, win10 = 0;
                double sum5 = 0.0, sum10 = 0.0;
                int validCount5 = 0, validCount10 = 0;

                for (CandlePatternDetector.PatternSignal s : list) {
                    if (s.index() + 5 < allCandles.size()) {
                        sum5 += s.forwardReturn5d();
                        validCount5++;
                        if ((isBullish && s.forwardReturn5d() > 0) || (!isBullish && s.forwardReturn5d() < 0)) {
                            win5++;
                        }
                    }

                    if (s.index() + 10 < allCandles.size()) {
                        sum10 += s.forwardReturn10d();
                        validCount10++;
                        if ((isBullish && s.forwardReturn10d() > 0) || (!isBullish && s.forwardReturn10d() < 0)) {
                            win10++;
                        }
                    }

                    // Add markers to chart
                    chartMarkers.add(new ChartGenerator.ChartMarker(
                            s.date(),
                            name + String.format(" (%+.1f%%)", s.forwardReturn5d()),
                            isBullish
                    ));
                }

                double winRate5 = validCount5 > 0 ? ((double) win5 / validCount5) * 100.0 : 0.0;
                double avgRet5 = validCount5 > 0 ? (sum5 / validCount5) : 0.0;
                double winRate10 = validCount10 > 0 ? ((double) win10 / validCount10) * 100.0 : 0.0;
                double avgRet10 = validCount10 > 0 ? (sum10 / validCount10) : 0.0;

                System.out.printf("%-22s | %-8s | %-5d | %6.1f%%   | %+6.2f%%      | %6.1f%%   | %+6.2f%%%n",
                        name, bias, list.size(), winRate5, avgRet5, winRate10, avgRet10);
            }

            System.out.println("==========================================================================================");

            // 4. Generate Interactive TradingView Chart
            System.out.println("\nGenerating interactive TradingView chart with all detected patterns...");
            File chartFile = ChartGenerator.createChart("RELIANCE", allCandles, chartMarkers);
            ChartGenerator.openInBrowser(chartFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
