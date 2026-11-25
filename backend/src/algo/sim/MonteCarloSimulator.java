package algo.sim;

import algo.stats.RandomizedQuickselect;
import algo.trie.DoubleTriePredictor;

import java.util.*;

/**
 * Unit 6: Randomized Algorithms - Monte Carlo Price Trajectory & Risk Simulator.
 *
 * Mathematical Formulation:
 * - Estimates empirical log-return mean (mu_log) and volatility (sigma) from historical candles.
 * - Mode GBM: Simulates M independent paths over T horizon steps using Box-Muller normal variates:
 *     S_{t+1} = S_t * exp(mu_log + sigma * Z_t),  Z_t ~ N(0, 1)
 *   whose exact closed-form theoretical expectation is S_0 * exp(T * (mu_log + 0.5 * sigma^2)).
 * - Mode BOOTSTRAP: Empirically resamples historical log-returns with replacement to capture fat tails.
 * - Uses RandomizedQuickselect (O(M)) at each step for quantile cones (P5, P25, P50, P75, P95)
 *   and terminal 95% VaR / CVaR (Expected Shortfall).
 *
 * Complexity:
 * - Time: O(M * T) path simulation + O(M * T) quantile selection via RandomizedQuickselect.
 * - Space: O(M) terminal array + O(K * T) for K <= 25 sample visualization paths.
 */
public class MonteCarloSimulator {

    public enum SimulationMode {
        GBM,
        BOOTSTRAP
    }

    public record HistogramBin(
            double rangeStart,
            double rangeEnd,
            int count
    ) {}

    public record SimulationResult(
            String mode,
            double spotPrice,
            double perBarDriftPct,
            double perBarVolatilityPct,
            double annualizedVolatilityPct,
            int trials,
            int horizonSteps,
            double tpPct,
            double slPct,
            double[] p5Path,
            double[] p25Path,
            double[] p50Path,
            double[] p75Path,
            double[] p95Path,
            List<double[]> samplePaths,
            double terminalMeanPrice,
            double terminalMedianPrice,
            double theoreticalMeanPrice,
            double meanConvergenceErrorPct,
            double var95Pct,
            double cvar95Pct,
            double probProfitPct,
            double probTakeProfitPct,
            double probStopLossPct,
            List<HistogramBin> histogram,
            long executionTimeNs
    ) {}

    private final RandomizedQuickselect quickselect = new RandomizedQuickselect();

    public SimulationResult simulate(
            List<DoubleTriePredictor.SimpleCandle> candles,
            int trials,
            int horizonSteps,
            SimulationMode mode,
            double tpPct,
            double slPct
    ) {
        return simulate(candles, trials, horizonSteps, mode, tpPct, slPct, new Random());
    }

    public SimulationResult simulate(
            List<DoubleTriePredictor.SimpleCandle> candles,
            int trials,
            int horizonSteps,
            SimulationMode mode,
            double tpPct,
            double slPct,
            Random rng
    ) {
        if (candles == null || candles.size() < 5) {
            throw new IllegalArgumentException("At least 5 historical candles are required for Monte Carlo calibration.");
        }
        long t0 = System.nanoTime();

        int m = Math.max(50, Math.min(25000, trials));
        int tSteps = Math.max(5, Math.min(100, horizonSteps));
        double safeTp = Math.max(0.1, Math.min(100.0, tpPct));
        double safeSl = Math.max(0.1, Math.min(99.0, slPct));

        // 1. Extract historical log-returns: r_i = ln(C_i / C_{i-1})
        double[] logReturns = new double[candles.size() - 1];
        double sum = 0.0;
        for (int i = 1; i < candles.size(); i++) {
            double prev = Math.max(candles.get(i - 1).close(), 1e-9);
            double curr = Math.max(candles.get(i).close(), 1e-9);
            double r = Math.log(curr / prev);
            logReturns[i - 1] = r;
            sum += r;
        }

        double muLog = sum / logReturns.length;
        double sqDiff = 0.0;
        for (double r : logReturns) {
            double d = r - muLog;
            sqDiff += d * d;
        }
        double sigma = logReturns.length > 1 ? Math.sqrt(sqDiff / (logReturns.length - 1)) : 0.0;

        double spotPrice = candles.get(candles.size() - 1).close();
        double tpPrice = spotPrice * (1.0 + safeTp / 100.0);
        double slPrice = spotPrice * (1.0 - safeSl / 100.0);

        // 2. Simulate M paths across T horizon steps (column-major storage for O(M) quantile selection per step)
        double[][] stepPrices = new double[tSteps + 1][m];
        Arrays.fill(stepPrices[0], spotPrice);

        int sampleCount = Math.min(25, m);
        List<double[]> samplePaths = new ArrayList<>(sampleCount);
        for (int s = 0; s < sampleCount; s++) {
            double[] sp = new double[tSteps + 1];
            sp[0] = spotPrice;
            samplePaths.add(sp);
        }

        int tpFirstCount = 0;
        int slFirstCount = 0;

        for (int trial = 0; trial < m; trial++) {
            double price = spotPrice;
            boolean barrierResolved = false;

            for (int step = 1; step <= tSteps; step++) {
                double logRet;
                if (mode == SimulationMode.BOOTSTRAP) {
                    logRet = logReturns[rng.nextInt(logReturns.length)];
                } else {
                    double z = nextStandardNormal(rng);
                    logRet = muLog + sigma * z;
                }

                price = price * Math.exp(logRet);
                stepPrices[step][trial] = price;

                if (trial < sampleCount) {
                    samplePaths.get(trial)[step] = price;
                }

                if (!barrierResolved) {
                    if (price >= tpPrice) {
                        tpFirstCount++;
                        barrierResolved = true;
                    } else if (price <= slPrice) {
                        slFirstCount++;
                        barrierResolved = true;
                    }
                }
            }
        }

        // 3. Extract quantile cones using RandomizedQuickselect in O(M) per step
        double[] p5Path = new double[tSteps + 1];
        double[] p25Path = new double[tSteps + 1];
        double[] p50Path = new double[tSteps + 1];
        double[] p75Path = new double[tSteps + 1];
        double[] p95Path = new double[tSteps + 1];

        for (int step = 0; step <= tSteps; step++) {
            if (step == 0) {
                p5Path[0] = p25Path[0] = p50Path[0] = p75Path[0] = p95Path[0] = spotPrice;
                continue;
            }
            double[] slice = stepPrices[step];
            p5Path[step] = quickselect.selectPercentile(slice, 0.05);
            p25Path[step] = quickselect.selectPercentile(slice, 0.25);
            p50Path[step] = quickselect.selectPercentile(slice, 0.50);
            p75Path[step] = quickselect.selectPercentile(slice, 0.75);
            p95Path[step] = quickselect.selectPercentile(slice, 0.95);
        }

        // 4. Terminal Risk Metrics (VaR 95%, CVaR 95%, Profit Prob, Convergence vs Theoretical Mean)
        double[] terminal = stepPrices[tSteps];
        double terminalSum = 0.0;
        int profitCount = 0;
        double minTerm = Double.MAX_VALUE;
        double maxTerm = -Double.MAX_VALUE;

        for (double val : terminal) {
            terminalSum += val;
            if (val > spotPrice) profitCount++;
            if (val < minTerm) minTerm = val;
            if (val > maxTerm) maxTerm = val;
        }

        double terminalMean = terminalSum / m;
        double terminalMedian = p50Path[tSteps];
        double theoreticalMean = spotPrice * Math.exp(tSteps * (muLog + 0.5 * sigma * sigma));
        double convergenceErrPct = theoreticalMean > 0
                ? (Math.abs(terminalMean - theoreticalMean) / theoreticalMean) * 100.0
                : 0.0;

        double p5Terminal = p5Path[tSteps];
        double var95Pct = Math.max(0.0, ((spotPrice - p5Terminal) / spotPrice) * 100.0);

        double tailLossSum = 0.0;
        int tailCount = 0;
        for (double val : terminal) {
            if (val <= p5Terminal) {
                tailLossSum += Math.max(0.0, ((spotPrice - val) / spotPrice) * 100.0);
                tailCount++;
            }
        }
        double cvar95Pct = tailCount > 0 ? tailLossSum / tailCount : var95Pct;

        // 5. Build 16-bin Terminal Distribution Histogram
        int numBins = 16;
        List<HistogramBin> histogram = new ArrayList<>(numBins);
        double span = Math.max(maxTerm - minTerm, spotPrice * 1e-6);
        double binWidth = span / numBins;
        int[] counts = new int[numBins];
        for (double val : terminal) {
            int idx = (int) Math.floor((val - minTerm) / binWidth);
            if (idx >= numBins) idx = numBins - 1;
            if (idx < 0) idx = 0;
            counts[idx]++;
        }
        for (int b = 0; b < numBins; b++) {
            double start = minTerm + b * binWidth;
            double end = start + binWidth;
            histogram.add(new HistogramBin(start, end, counts[b]));
        }

        // Approximate annualized vol assuming 15m/1h style crypto scaling (using 365 * 24 bars/yr as nominal 1h reference,
        // while keeping exact per-bar volatility explicit).
        double perBarVolPct = sigma * 100.0;
        double annualizedVolPct = sigma * Math.sqrt(365.0 * 24.0) * 100.0;
        long elapsedNs = System.nanoTime() - t0;

        return new SimulationResult(
                mode.name(),
                spotPrice,
                muLog * 100.0,
                perBarVolPct,
                annualizedVolPct,
                m,
                tSteps,
                safeTp,
                safeSl,
                p5Path,
                p25Path,
                p50Path,
                p75Path,
                p95Path,
                samplePaths,
                terminalMean,
                terminalMedian,
                theoreticalMean,
                convergenceErrPct,
                var95Pct,
                cvar95Pct,
                (profitCount * 100.0) / m,
                (tpFirstCount * 100.0) / m,
                (slFirstCount * 100.0) / m,
                histogram,
                elapsedNs
        );
    }

    /**
     * Box-Muller transform for standard normal N(0, 1) variates.
     */
    private static double nextStandardNormal(Random rng) {
        double u1 = Math.max(1e-12, rng.nextDouble());
        double u2 = rng.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
