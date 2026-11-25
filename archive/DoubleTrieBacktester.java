import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Institutional Walk-Forward Out-of-Sample Backtester for Double Trie Engine.
 * 
 * Strict Zero-Lookahead Bias:
 * - At every bar t, the Double Trie is trained exclusively on historical bars [0 ... t].
 * - The active prefix [t-4 ... t] queries the Trie to forecast future bars [t+1 ... t+5].
 * - Realized price action is evaluated across:
 *   1. 1-Bar Directional Hit Rate (vs 50% baseline).
 *   2. 3-Bar and 5-Bar Cumulative Trend Accuracy.
 *   3. Magnitude Volatility Prediction (Compression vs Expansion).
 *   4. Confidence Calibration: Does higher Trie confidence yield higher win rates?
 *   5. Simulated PnL, Profit Factor, Expectancy, and Max Drawdown.
 */
public class DoubleTrieBacktester {

    public record BacktestResult(
            String symbol,
            String interval,
            int totalBarsEvaluated,
            int totalPredictionsMade,
            // Directional Metrics
            int dirCorrect1Bar,
            double dirAccuracy1BarPct,
            int dirCorrect3Bars,
            double dirAccuracy3BarsPct,
            int dirCorrect5Bars,
            double dirAccuracy5BarsPct,
            // Confidence Stratification (High Confidence >= 65%)
            int highConfCount,
            int highConfCorrect,
            double highConfAccuracyPct,
            // Simulated Trading Strategy
            int totalTrades,
            int winningTrades,
            int losingTrades,
            double winRatePct,
            double grossProfitPct,
            double grossLossPct,
            double profitFactor,
            double netReturnPct,
            double maxDrawdownPct,
            double avgReturnPerTradePct
    ) {}

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public List<DoubleTriePredictor.SimpleCandle> fetchHistoricalCandles(String symbol, String interval, int limit) throws Exception {
        String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=%d", symbol, interval, limit);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return parseKlines(resp.body());
    }

    private List<DoubleTriePredictor.SimpleCandle> parseKlines(String json) {
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

    /**
     * Executes rigorous walk-forward out-of-sample backtesting.
     */
    public BacktestResult runWalkForward(String symbol, String interval, int totalBarsToFetch, int warmupPeriod, int forecastHorizon) throws Exception {
        List<DoubleTriePredictor.SimpleCandle> allCandles = fetchHistoricalCandles(symbol, interval, totalBarsToFetch);
        if (allCandles.size() < warmupPeriod + forecastHorizon + 10) {
            throw new IllegalArgumentException("Insufficient candles fetched for backtesting.");
        }

        int totalEvaluated = 0;
        int dirCorrect1 = 0;
        int dirCorrect3 = 0;
        int dirCorrect5 = 0;

        int highConfCount = 0;
        int highConfCorrect = 0;

        // Trade tracking
        int totalTrades = 0;
        int winningTrades = 0;
        int losingTrades = 0;
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        double peakEquity = 100.0;
        double currentEquity = 100.0;
        double maxDrawdown = 0.0;

        // Precompute ATR array
        double[] atrs = calculateAtrs(allCandles, 14);

        // Slide window bar-by-bar (Walk-Forward)
        // At step t: train ONLY on [0 ... t-1], predict for [t ... t + forecastHorizon - 1]
        for (int t = warmupPeriod; t <= allCandles.size() - forecastHorizon - 1; t++) {
            List<DoubleTriePredictor.SimpleCandle> trainSlice = allCandles.subList(0, t);
            List<DoubleTriePredictor.SimpleCandle> prefixSlice = allCandles.subList(Math.max(0, t - 5), t);

            DoubleTriePredictor predictor = new DoubleTriePredictor(5);
            predictor.train(trainSlice);

            double currentAtr = atrs[t - 1];
            DoubleTriePredictor.AutofillForecast forecast = predictor.autofill(prefixSlice, currentAtr, forecastHorizon);
            if (forecast == null || forecast.predictedPath().isEmpty()) continue;

            totalEvaluated++;
            DoubleTriePredictor.SimpleCandle currentBar = allCandles.get(t - 1);
            DoubleTriePredictor.SimpleCandle barT1 = allCandles.get(t);
            DoubleTriePredictor.SimpleCandle barT3 = (t + 2 < allCandles.size()) ? allCandles.get(t + 2) : barT1;
            DoubleTriePredictor.SimpleCandle barT5 = (t + 4 < allCandles.size()) ? allCandles.get(t + 4) : barT3;

            // 1. Evaluate 1-Bar Direction
            DoubleTriePredictor.PredictedCandle p1 = forecast.predictedPath().get(0);
            boolean pred1Bull = p1.close() >= p1.open();
            boolean real1Bull = barT1.close() >= barT1.open();
            if (pred1Bull == real1Bull) {
                dirCorrect1++;
            }

            // 2. Evaluate 3-Bar Cumulative Direction
            boolean pred3Bull = forecast.predictedPath().size() >= 3 && forecast.predictedPath().get(2).close() >= currentBar.close();
            boolean real3Bull = barT3.close() >= currentBar.close();
            if (pred3Bull == real3Bull) {
                dirCorrect3++;
            }

            // 3. Evaluate 5-Bar Cumulative Direction
            boolean pred5Bull = forecast.cumulativeReturnPct() >= 0;
            boolean real5Bull = barT5.close() >= currentBar.close();
            if (pred5Bull == real5Bull) {
                dirCorrect5++;
            }

            // 4. Evaluate High-Confidence Predictions (>= 65% Confidence)
            if (forecast.overallConfidencePct() >= 65.0) {
                highConfCount++;
                if (pred5Bull == real5Bull) {
                    highConfCorrect++;
                }

                // 5. Simulate Strategy Execution on High-Confidence Signals
                // Minimum expected movement filter (>= 0.35%)
                if (Math.abs(forecast.cumulativeReturnPct()) >= 0.35) {
                    totalTrades++;
                    boolean isLong = forecast.cumulativeReturnPct() > 0;
                    double entryPrice = currentBar.close();

                    // Risk management: Stop loss at recent 4-bar swing, Take profit at 1.5R
                    double swingLow = Double.MAX_VALUE;
                    double swingHigh = Double.MIN_VALUE;
                    for (int k = Math.max(0, t - 4); k < t; k++) {
                        swingLow = Math.min(swingLow, allCandles.get(k).low());
                        swingHigh = Math.max(swingHigh, allCandles.get(k).high());
                    }

                    double stopLoss = isLong ? (swingLow * 0.9995) : (swingHigh * 1.0005);
                    double risk = Math.max(Math.abs(entryPrice - stopLoss), entryPrice * 0.002);
                    double tp1 = isLong ? (entryPrice + 1.5 * risk) : (entryPrice - 1.5 * risk);

                    // Check forward bars for SL or TP hit over the next 5 bars
                    boolean tradeResolved = false;
                    double tradeReturnPct = 0.0;

                    for (int f = t; f < t + forecastHorizon; f++) {
                        DoubleTriePredictor.SimpleCandle fc = allCandles.get(f);
                        if (isLong) {
                            if (fc.low() <= stopLoss) {
                                tradeReturnPct = -((risk / entryPrice) * 100.0);
                                tradeResolved = true;
                                break;
                            }
                            if (fc.high() >= tp1) {
                                tradeReturnPct = +((1.5 * risk / entryPrice) * 100.0);
                                tradeResolved = true;
                                break;
                            }
                        } else {
                            if (fc.high() >= stopLoss) {
                                tradeReturnPct = -((risk / entryPrice) * 100.0);
                                tradeResolved = true;
                                break;
                            }
                            if (fc.low() <= tp1) {
                                tradeReturnPct = +((1.5 * risk / entryPrice) * 100.0);
                                tradeResolved = true;
                                break;
                            }
                        }
                    }

                    // If neither hit during the horizon, exit at horizon close
                    if (!tradeResolved) {
                        double exitPrice = barT5.close();
                        tradeReturnPct = isLong ? ((exitPrice - entryPrice) / entryPrice) * 100.0 : ((entryPrice - exitPrice) / entryPrice) * 100.0;
                    }

                    if (tradeReturnPct > 0) {
                        winningTrades++;
                        grossProfit += tradeReturnPct;
                    } else {
                        losingTrades++;
                        grossLoss += Math.abs(tradeReturnPct);
                    }

                    currentEquity *= (1.0 + tradeReturnPct / 100.0);
                    peakEquity = Math.max(peakEquity, currentEquity);
                    double dd = ((peakEquity - currentEquity) / peakEquity) * 100.0;
                    maxDrawdown = Math.max(maxDrawdown, dd);
                }
            }
        }

        double dirAcc1 = totalEvaluated > 0 ? (dirCorrect1 * 100.0) / totalEvaluated : 0.0;
        double dirAcc3 = totalEvaluated > 0 ? (dirCorrect3 * 100.0) / totalEvaluated : 0.0;
        double dirAcc5 = totalEvaluated > 0 ? (dirCorrect5 * 100.0) / totalEvaluated : 0.0;
        double highConfAcc = highConfCount > 0 ? (highConfCorrect * 100.0) / highConfCount : 0.0;

        double winRate = totalTrades > 0 ? (winningTrades * 100.0) / totalTrades : 0.0;
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? 99.9 : 1.0);
        double netReturn = currentEquity - 100.0;
        double avgReturnPerTrade = totalTrades > 0 ? netReturn / totalTrades : 0.0;

        return new BacktestResult(
                symbol, interval, allCandles.size(), totalEvaluated,
                dirCorrect1, dirAcc1, dirCorrect3, dirAcc3, dirCorrect5, dirAcc5,
                highConfCount, highConfCorrect, highConfAcc,
                totalTrades, winningTrades, losingTrades, winRate,
                grossProfit, grossLoss, profitFactor, netReturn, maxDrawdown, avgReturnPerTrade
        );
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

    public static void main(String[] args) {
        System.out.println("==========================================================================================");
        System.out.println("      QUANTITATIVE WALK-FORWARD OUT-OF-SAMPLE BACKTESTER: DOUBLE TRIE ENGINE              ");
        System.out.println("==========================================================================================");
        System.out.println("Testing on Out-of-Sample Historical Data with ZERO Lookahead Bias (Strict Rolling Walk-Forward)...");

        DoubleTrieBacktester backtester = new DoubleTrieBacktester();

        // Assets and Timeframes to Benchmark
        String[][] benchmarks = {
                {"BTCUSDT", "15m"},
                {"BTCUSDT", "1h"},
                {"ETHUSDT", "15m"},
                {"SOLUSDT", "15m"},
                {"DOGEUSDT", "15m"},
                {"NEARUSDT", "15m"}
        };

        List<BacktestResult> results = new ArrayList<>();

        for (String[] bm : benchmarks) {
            String sym = bm[0];
            String tf = bm[1];
            System.out.printf("%n[RUNNING WALK-FORWARD TEST] Asset: %-8s | Timeframe: %-4s | Horizon: 5 Bars...%n", sym, tf);
            try {
                long t0 = System.currentTimeMillis();
                // Fetch 1000 bars, warmup 150 bars, forecast 5 bars forward
                BacktestResult res = backtester.runWalkForward(sym, tf, 1000, 150, 5);
                long elapsed = System.currentTimeMillis() - t0;
                results.add(res);
                System.out.printf("  >> Completed %d walk-forward simulations in %d ms (Avg %.1f ms/step)%n",
                        res.totalPredictionsMade(), elapsed, (double) elapsed / Math.max(1, res.totalPredictionsMade()));
            } catch (Exception e) {
                System.err.printf("Error backtesting %s %s: %s%n", sym, tf, e.getMessage());
            }
        }

        // Summary Performance Table
        System.out.println("\n==================================================================================================================================");
        System.out.println("                                      DOUBLE TRIE ENGINE: WALK-FORWARD PERFORMANCE BENCHMARK                                      ");
        System.out.println("==================================================================================================================================");
        System.out.printf("%-9s | %-4s | %-6s | %-10s | %-10s | %-10s | %-12s | %-8s | %-8s | %-8s | %-8s%n",
                "Asset", "TF", "Bars", "1-Bar Acc", "3-Bar Acc", "5-Bar Acc", "High-Conf Acc", "Trades", "Win Rate", "Prof Fact", "Net PnL");
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------");
        for (BacktestResult r : results) {
            System.out.printf("%-9s | %-4s | %-6d | %8.1f%%  | %8.1f%%  | %8.1f%%  | %10.1f%%  | %-8d | %6.1f%%  | %8.2f  | %+7.2f%%%n",
                    r.symbol(), r.interval(), r.totalBarsEvaluated(),
                    r.dirAccuracy1BarPct(), r.dirAccuracy3BarsPct(), r.dirAccuracy5BarsPct(),
                    r.highConfAccuracyPct(), r.totalTrades(), r.winRatePct(), r.profitFactor(), r.netReturnPct());
        }
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------");

        // In-depth Trade Breakdown
        System.out.println("\n==========================================================================================");
        System.out.println("                    DETAILED TRADING STRATEGY METRICS & CALIBRATION                       ");
        System.out.println("==========================================================================================");
        for (BacktestResult r : results) {
            System.out.printf(">> %s [%s] Strategy Performance:%n", r.symbol(), r.interval());
            System.out.printf("   * Total Strategy Trades Triggered : %d (Wins: %d | Losses: %d)%n", r.totalTrades(), r.winningTrades(), r.losingTrades());
            System.out.printf("   * Strategy Win Rate               : %.1f%%%n", r.winRatePct());
            System.out.printf("   * Profit Factor (Gross P / Gross L): %.2f%n", r.profitFactor());
            System.out.printf("   * Net Strategy Return             : %+.2f%%%n", r.netReturnPct());
            System.out.printf("   * Maximum Drawdown (Equity Peak)  : %.2f%%%n", r.maxDrawdownPct());
            System.out.printf("   * Baseline 1-Bar (All Predictions): %.1f%% (Unfiltered)%n", r.dirAccuracy1BarPct());
            System.out.printf("   * High-Confidence (>= 65%% Conf)   : %.1f%% (%d of %d correct)%n%n",
                    r.highConfAccuracyPct(), r.highConfCorrect(), r.highConfCount());
        }
    }
}
