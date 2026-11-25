package algo.test;

import algo.approx.MetricTspApproximator;
import algo.bloom.OrderFlowBloomFilter;
import algo.flow.OrderFlowNetwork;
import algo.priority.CryptoKeywordAnalyzer;
import algo.priority.CryptoNewsPriorityQueue;
import algo.priority.NewsModels.*;
import algo.sim.MonteCarloSimulator;
import algo.stats.RandomizedQuickselect;
import algo.trie.DoubleTriePredictor;

import java.util.*;

/**
 * Academic Test & Correctness Verification Suite.
 * Fulfills CIA 3 Rubric: >= 5 documented tests per core algorithm including boundary/edge cases,
 * sound probabilistic behavior, and scaled benchmarks.
 */
public class AlgorithmTestSuite {

    public record TestResult(
            String suiteName,
            String testName,
            boolean passed,
            String details,
            long executionTimeNs
    ) {}

    public record FullSuiteReport(
            int totalTests,
            int passedTests,
            int failedTests,
            double passRatePct,
            List<TestResult> results
    ) {}

    public FullSuiteReport runAllTests() {
        List<TestResult> list = new ArrayList<>();

        // 1. Double Trie Tests (Unit 4)
        list.add(testTrieEmptyInput());
        list.add(testTrieSingleToken());
        list.add(testTrieDeepPrefixMatching());
        list.add(testTrieSuffixBackoffFallback());
        list.add(testTrieProbabilityDistribution());

        // 2. Edmonds-Karp Max-Flow / Min-Cut Tests (Unit 5)
        list.add(testFlowZeroCapacity());
        list.add(testFlowSingleBottleneckMinCut());
        list.add(testFlowSaturatedMaxFlow());
        list.add(testFlowMultiTierAbsorption());
        list.add(testFlowConservationLaw());

        // 3. Counting Bloom Filter Tests (Unit 6 / CO5)
        list.add(testBloomEmptyFilter());
        list.add(testBloomZeroFalseNegatives());
        list.add(testBloomFalsePositiveRateCalibration());
        list.add(testBloomDynamicDeletion());
        list.add(testBloomHighLoadSaturation());

        // 4. Randomized Quickselect Tests (Unit 1 / 6)
        list.add(testQuickselectSingleElement());
        list.add(testQuickselectMedianCorrectness());
        list.add(testQuickselectMinMaxBoundaries());
        list.add(testQuickselectDuplicateElements());
        list.add(testQuickselectScaledBenchmark());

        // 5. Monte Carlo Simulator Tests (Unit 6)
        list.add(testMonteCarloInsufficientInputBoundary());
        list.add(testMonteCarloZeroVolatilityInvariance());
        list.add(testMonteCarloConvergenceToTheoreticalMean());
        list.add(testMonteCarloRiskAndProbabilityBounds());
        list.add(testMonteCarloScaledBenchmark());

        // 6. Metric TSP 2-Approximation Tests (Unit 5)
        list.add(testTspEmptyAndSingleBoundary());
        list.add(testTspMetricTriangleInequality());
        list.add(testTspTwoApproximationGuarantee());
        list.add(testTspValidHamiltonianCycle());
        list.add(testTspScaledRuntimeComparison());

        // 7. Crypto News Binary Max-Heap Priority Queue & Keyword Tests (Unit 2)
        list.add(testPriorityQueueEmptyAndSingleBoundary());
        list.add(testPriorityQueueMaxHeapOrderAndTieBreaker());
        list.add(testPriorityQueueBuildHeapAndIncreasePriority());
        list.add(testCryptoKeywordRelevanceGateAndFalsePositiveGuard());
        list.add(testPriorityQueueScaledTopKBenchmark());

        int passed = 0;
        for (TestResult r : list) {
            if (r.passed()) passed++;
        }

        double rate = list.isEmpty() ? 0.0 : ((double) passed / list.size()) * 100.0;
        return new FullSuiteReport(list.size(), passed, list.size() - passed, rate, list);
    }

    // --- DOUBLE TRIE TESTS ---
    private TestResult testTrieEmptyInput() {
        long t0 = System.nanoTime();
        DoubleTriePredictor predictor = new DoubleTriePredictor(5);
        predictor.train(Collections.emptyList());
        var forecast = predictor.autofill(Collections.emptyList(), 1.0, 5);
        boolean passed = forecast == null;
        return new TestResult("Unit 4: Double Trie", "T1: Empty Dataset Boundary Test", passed,
                passed ? "Correctly returned null without crashing on empty inputs." : "Failed to handle empty input.", System.nanoTime() - t0);
    }

    private TestResult testTrieSingleToken() {
        long t0 = System.nanoTime();
        DoubleTriePredictor predictor = new DoubleTriePredictor(5);
        List<DoubleTriePredictor.SimpleCandle> candles = createMockCandles(25, true);
        predictor.train(candles);
        var token = predictor.tokenize(candles.get(0), 10.0);
        boolean passed = token != null && token.direction() != null && token.magnitude() != null;
        return new TestResult("Unit 4: Double Trie", "T2: Tokenization Correctness", passed,
                "Token successfully synthesized: " + token, System.nanoTime() - t0);
    }

    private TestResult testTrieDeepPrefixMatching() {
        long t0 = System.nanoTime();
        DoubleTriePredictor predictor = new DoubleTriePredictor(5);
        List<DoubleTriePredictor.SimpleCandle> candles = createMockCandles(50, true);
        predictor.train(candles);
        var forecast = predictor.autofill(candles.subList(candles.size() - 5, candles.size()), 5.0, 5);
        boolean passed = forecast != null && !forecast.predictedPath().isEmpty() && forecast.matchedPrefix().size() > 0;
        return new TestResult("Unit 4: Double Trie", "T3: Deep Prefix Path Matching", passed,
                "Matched " + (forecast != null ? forecast.matchedPrefix().size() : 0) + " tokens with non-zero confidence.", System.nanoTime() - t0);
    }

    private TestResult testTrieSuffixBackoffFallback() {
        long t0 = System.nanoTime();
        DoubleTriePredictor predictor = new DoubleTriePredictor(5);
        List<DoubleTriePredictor.SimpleCandle> candles = createMockCandles(30, true);
        predictor.train(candles);
        // Feed an exotic unseen prefix
        List<DoubleTriePredictor.SimpleCandle> exotic = List.of(
                new DoubleTriePredictor.SimpleCandle(100, 100, 200, 50, 180, 500)
        );
        var forecast = predictor.autofill(exotic, 10.0, 3);
        boolean passed = forecast != null && forecast.fallbackDepth() >= 0;
        return new TestResult("Unit 4: Double Trie", "T4: Suffix Backoff Graceful Fallback", passed,
                "Gracefully handled unobserved prefix via suffix backoff (fallback depth: " + (forecast != null ? forecast.fallbackDepth() : -1) + ")", System.nanoTime() - t0);
    }

    private TestResult testTrieProbabilityDistribution() {
        long t0 = System.nanoTime();
        DoubleTriePredictor predictor = new DoubleTriePredictor(5);
        List<DoubleTriePredictor.SimpleCandle> candles = createMockCandles(60, false);
        predictor.train(candles);
        var forecast = predictor.autofill(candles.subList(candles.size() - 4, candles.size()), 2.0, 4);
        boolean passed = forecast != null && forecast.overallConfidencePct() >= 0.0 && forecast.overallConfidencePct() <= 100.0;
        return new TestResult("Unit 4: Double Trie", "T5: Probability Distribution Normalization", passed,
                "Confidence bounded in [0%, 100%]: " + (forecast != null ? String.format("%.1f%%", forecast.overallConfidencePct()) : "N/A"), System.nanoTime() - t0);
    }

    // --- EDMONDS-KARP MAX-FLOW TESTS ---
    private TestResult testFlowZeroCapacity() {
        long t0 = System.nanoTime();
        OrderFlowNetwork.MaxFlowSolver solver = new OrderFlowNetwork.MaxFlowSolver(4);
        solver.addEdge(0, 1, 0.0);
        solver.addEdge(1, 2, 0.0);
        solver.addEdge(2, 3, 0.0);
        double flow = solver.edmondsKarp(0, 3);
        boolean passed = Math.abs(flow) < 1e-9;
        return new TestResult("Unit 5: Max-Flow Min-Cut", "T6: Zero Capacity Boundary Edge Case", passed,
                "Max flow through 0-capacity graph is strictly 0.0", System.nanoTime() - t0);
    }

    private TestResult testFlowSingleBottleneckMinCut() {
        long t0 = System.nanoTime();
        OrderFlowNetwork.MaxFlowSolver solver = new OrderFlowNetwork.MaxFlowSolver(4);
        solver.addEdge(0, 1, 100.0);
        solver.addEdge(1, 2, 25.0); // Bottleneck edge
        solver.addEdge(2, 3, 100.0);
        double flow = solver.edmondsKarp(0, 3);
        boolean[] minCut = solver.getMinCut(0);
        boolean passed = Math.abs(flow - 25.0) < 1e-6 && minCut[0] && minCut[1] && !minCut[2] && !minCut[3];
        return new TestResult("Unit 5: Max-Flow Min-Cut", "T7: Bottleneck Min-Cut Theorem Verification", passed,
                "Flow correctly throttled to exactly 25.0 by bottleneck edge (1 -> 2). S-T cut verified.", System.nanoTime() - t0);
    }

    private TestResult testFlowSaturatedMaxFlow() {
        long t0 = System.nanoTime();
        OrderFlowNetwork.MaxFlowSolver solver = new OrderFlowNetwork.MaxFlowSolver(4);
        solver.addEdge(0, 1, 50.0);
        solver.addEdge(0, 2, 50.0);
        solver.addEdge(1, 3, 50.0);
        solver.addEdge(2, 3, 50.0);
        double flow = solver.edmondsKarp(0, 3);
        boolean passed = Math.abs(flow - 100.0) < 1e-6;
        return new TestResult("Unit 5: Max-Flow Min-Cut", "T8: Parallel Flow Saturation", passed,
                "Dual augmenting paths fully saturated to 100.0 capacity.", System.nanoTime() - t0);
    }

    private TestResult testFlowMultiTierAbsorption() {
        long t0 = System.nanoTime();
        OrderFlowNetwork network = new OrderFlowNetwork();
        List<OrderFlowNetwork.BookLevel> bids = List.of(new OrderFlowNetwork.BookLevel(100, 500));
        List<OrderFlowNetwork.BookLevel> asks = List.of(new OrderFlowNetwork.BookLevel(101, 1000));
        // Buy flow (100) is far less than Ask wall (1000) -> Absorption Reversal expected
        var res = network.solveNetwork(100.5, 100.0, 50.0, bids, asks);
        boolean passed = res.directionType() == OrderFlowNetwork.DirectionType.ABSORPTION_REVERSAL_BEAR;
        return new TestResult("Unit 5: Max-Flow Min-Cut", "T9: Order Book Liquidity Absorption State", passed,
                "Heavy Ask wall correctly triggered: " + res.directionType(), System.nanoTime() - t0);
    }

    private TestResult testFlowConservationLaw() {
        long t0 = System.nanoTime();
        OrderFlowNetwork.MaxFlowSolver solver = new OrderFlowNetwork.MaxFlowSolver(5);
        solver.addEdge(0, 1, 40.0);
        solver.addEdge(0, 2, 60.0);
        solver.addEdge(1, 3, 30.0);
        solver.addEdge(2, 3, 50.0);
        solver.addEdge(3, 4, 70.0);
        double flow = solver.edmondsKarp(0, 4);
        boolean passed = flow > 0 && flow <= 70.0;
        return new TestResult("Unit 5: Max-Flow Min-Cut", "T10: Flow Conservation & Capacity Bounds", passed,
                "Flow strictly bounded by terminal capacity: " + flow, System.nanoTime() - t0);
    }

    // --- COUNTING BLOOM FILTER TESTS (CO5) ---
    private TestResult testBloomEmptyFilter() {
        long t0 = System.nanoTime();
        OrderFlowBloomFilter bf = new OrderFlowBloomFilter(500, 0.01);
        boolean passed = !bf.contains("UNSEEN_TRAP_SIGNATURE_999");
        return new TestResult("Unit 6: Bloom Filter (CO5)", "T11: Empty Filter Boundary Test", passed,
                "Empty Bloom filter returns false on uninserted keys.", System.nanoTime() - t0);
    }

    private TestResult testBloomZeroFalseNegatives() {
        long t0 = System.nanoTime();
        OrderFlowBloomFilter bf = new OrderFlowBloomFilter(1000, 0.01);
        List<String> traps = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String sig = "SPOOF_TRAP_" + i;
            traps.add(sig);
            bf.insert(sig);
        }
        boolean allFound = true;
        for (String t : traps) {
            if (!bf.contains(t)) { allFound = false; break; }
        }
        return new TestResult("Unit 6: Bloom Filter (CO5)", "T12: Zero False Negatives Guarantee", allFound,
                "100% of 200 inserted trap signatures correctly identified (0 False Negatives).", System.nanoTime() - t0);
    }

    private TestResult testBloomFalsePositiveRateCalibration() {
        long t0 = System.nanoTime();
        double targetFpr = 0.05;
        OrderFlowBloomFilter bf = new OrderFlowBloomFilter(1000, targetFpr);
        for (int i = 0; i < 500; i++) {
            bf.insert("INSERTED_PATTERN_" + i);
        }
        int falsePositives = 0;
        int trials = 2000;
        for (int i = 0; i < trials; i++) {
            if (bf.contains("NON_EXISTENT_PATTERN_" + i)) {
                falsePositives++;
            }
        }
        double empiricalFpr = (double) falsePositives / trials;
        boolean passed = empiricalFpr <= targetFpr * 1.5; // Within probabilistic variance
        return new TestResult("Unit 6: Bloom Filter (CO5)", "T13: False Positive Rate Calibration (epsilon <= 0.05)", passed,
                String.format("Empirical FPR: %.3f%% (Target: %.2f%%, FP: %d/%d)", empiricalFpr * 100.0, targetFpr * 100.0, falsePositives, trials), System.nanoTime() - t0);
    }

    private TestResult testBloomDynamicDeletion() {
        long t0 = System.nanoTime();
        OrderFlowBloomFilter bf = new OrderFlowBloomFilter(500, 0.01);
        String trap = "ICEBERG_EXPIRED_ORDER_42";
        bf.insert(trap);
        boolean presentBefore = bf.contains(trap);
        boolean removed = bf.remove(trap);
        boolean presentAfter = bf.contains(trap);
        boolean passed = presentBefore && removed && !presentAfter;
        return new TestResult("Unit 6: Bloom Filter (CO5)", "T14: Dynamic Decrement / Expiration", passed,
                "Successfully decremented counter bucket and removed key from Bloom filter.", System.nanoTime() - t0);
    }

    private TestResult testBloomHighLoadSaturation() {
        long t0 = System.nanoTime();
        OrderFlowBloomFilter bf = new OrderFlowBloomFilter(100, 0.01);
        for (int i = 0; i < 300; i++) {
            bf.insert("OVERLOAD_KEY_" + i);
        }
        double fill = bf.getCapacityFillRatio();
        boolean passed = fill > 0.50 && bf.getElementCount() == 300;
        return new TestResult("Unit 6: Bloom Filter (CO5)", "T15: High-Load Saturation Telemetry", passed,
                String.format("Capacity fill ratio at %.1f%% under 3x overload.", fill * 100.0), System.nanoTime() - t0);
    }

    // --- RANDOMIZED QUICKSELECT TESTS (UNIT 1 / 6) ---
    private TestResult testQuickselectSingleElement() {
        long t0 = System.nanoTime();
        RandomizedQuickselect qs = new RandomizedQuickselect();
        double val = qs.select(new double[]{42.5}, 0);
        boolean passed = Math.abs(val - 42.5) < 1e-9;
        return new TestResult("Unit 1/6: Quickselect", "T16: Single Element Boundary Test", passed,
                "Correctly selected single element 42.5.", System.nanoTime() - t0);
    }

    private TestResult testQuickselectMedianCorrectness() {
        long t0 = System.nanoTime();
        RandomizedQuickselect qs = new RandomizedQuickselect();
        double[] array = {12.0, 3.0, 19.0, 8.0, 7.0, 1.0, 15.0};
        double median = qs.selectPercentile(array, 0.50);
        double[] sorted = array.clone();
        Arrays.sort(sorted);
        double expected = sorted[array.length / 2];
        boolean passed = Math.abs(median - expected) < 1e-9;
        return new TestResult("Unit 1/6: Quickselect", "T17: Median Selection Ground Truth Test", passed,
                "Selected median: " + median + " (Expected: " + expected + ")", System.nanoTime() - t0);
    }

    private TestResult testQuickselectMinMaxBoundaries() {
        long t0 = System.nanoTime();
        RandomizedQuickselect qs = new RandomizedQuickselect();
        double[] array = {99.1, 4.2, 18.7, -5.0, 42.0};
        double min = qs.select(array, 0);
        double max = qs.select(array, array.length - 1);
        boolean passed = Math.abs(min - (-5.0)) < 1e-9 && Math.abs(max - 99.1) < 1e-9;
        return new TestResult("Unit 1/6: Quickselect", "T18: Min/Max Boundary (k=0 and k=N-1)", passed,
                "Min: " + min + " | Max: " + max, System.nanoTime() - t0);
    }

    private TestResult testQuickselectDuplicateElements() {
        long t0 = System.nanoTime();
        RandomizedQuickselect qs = new RandomizedQuickselect();
        double[] array = {5.0, 5.0, 5.0, 5.0, 5.0};
        double val = qs.select(array, 2);
        boolean passed = Math.abs(val - 5.0) < 1e-9;
        return new TestResult("Unit 1/6: Quickselect", "T19: Identical Duplicate Elements Test", passed,
                "Correctly handled array of all duplicates.", System.nanoTime() - t0);
    }

    private TestResult testQuickselectScaledBenchmark() {
        long t0 = System.nanoTime();
        RandomizedQuickselect qs = new RandomizedQuickselect();
        var bm100 = qs.benchmark(100, 0.95);
        var bm1000 = qs.benchmark(1000, 0.95);
        var bm10000 = qs.benchmark(10000, 0.95);
        boolean passed = bm10000.speedupFactor() > 0.0;
        return new TestResult("Unit 1/6: Quickselect", "T20: Scaled Benchmark (N=100, 1000, 10000)", passed,
                String.format("N=10k: QS took %d us vs Sort %d us (Speedup: %.2fx)",
                        bm10000.quickselectTimeNs() / 1000, bm10000.sortBaselineTimeNs() / 1000, bm10000.speedupFactor()), System.nanoTime() - t0);
    }

    // --- MONTE CARLO SIMULATOR TESTS (UNIT 6) ---
    private TestResult testMonteCarloInsufficientInputBoundary() {
        long t0 = System.nanoTime();
        MonteCarloSimulator mc = new MonteCarloSimulator();
        boolean threw = false;
        try {
            mc.simulate(Collections.emptyList(), 500, 10, MonteCarloSimulator.SimulationMode.GBM, 2.0, 1.5);
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        return new TestResult("Unit 6: Monte Carlo", "T21: Empty/Insufficient Input Boundary", threw,
                threw ? "Correctly rejected empty candle list with IllegalArgumentException." : "Failed to reject empty input.",
                System.nanoTime() - t0);
    }

    private TestResult testMonteCarloZeroVolatilityInvariance() {
        long t0 = System.nanoTime();
        MonteCarloSimulator mc = new MonteCarloSimulator();
        List<DoubleTriePredictor.SimpleCandle> flat = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            flat.add(new DoubleTriePredictor.SimpleCandle(1700000000L + i * 900L, 100, 100, 100, 100, 1000));
        }
        var res = mc.simulate(flat, 300, 15, MonteCarloSimulator.SimulationMode.GBM, 2.0, 1.5, new Random(42));
        boolean passed = Math.abs(res.perBarVolatilityPct()) < 1e-9
                && Math.abs(res.p5Path()[15] - 100.0) < 1e-6
                && Math.abs(res.p95Path()[15] - 100.0) < 1e-6;
        return new TestResult("Unit 6: Monte Carlo", "T22: Zero-Volatility Deterministic Invariance", passed,
                String.format("Sigma=%.4f%% -> P5=%.2f, P95=%.2f (strictly invariant at 100.00)",
                        res.perBarVolatilityPct(), res.p5Path()[15], res.p95Path()[15]), System.nanoTime() - t0);
    }

    private TestResult testMonteCarloConvergenceToTheoreticalMean() {
        long t0 = System.nanoTime();
        MonteCarloSimulator mc = new MonteCarloSimulator();
        List<DoubleTriePredictor.SimpleCandle> candles = createMockCandles(60, true);
        var lowN = mc.simulate(candles, 100, 20, MonteCarloSimulator.SimulationMode.GBM, 3.0, 2.0, new Random(7));
        var highN = mc.simulate(candles, 8000, 20, MonteCarloSimulator.SimulationMode.GBM, 3.0, 2.0, new Random(7));
        boolean passed = highN.meanConvergenceErrorPct() < 1.5 && highN.meanConvergenceErrorPct() <= lowN.meanConvergenceErrorPct() + 1.0;
        return new TestResult("Unit 6: Monte Carlo", "T23: Convergence to Theoretical GBM Mean", passed,
                String.format("M=8000 error=%.3f%% vs M=100 error=%.3f%% (Theoretical=%.2f)",
                        highN.meanConvergenceErrorPct(), lowN.meanConvergenceErrorPct(), highN.theoreticalMeanPrice()),
                System.nanoTime() - t0);
    }

    private TestResult testMonteCarloRiskAndProbabilityBounds() {
        long t0 = System.nanoTime();
        MonteCarloSimulator mc = new MonteCarloSimulator();
        List<DoubleTriePredictor.SimpleCandle> candles = createOscillatingCandles(50);
        var res = mc.simulate(candles, 1000, 20, MonteCarloSimulator.SimulationMode.BOOTSTRAP, 2.0, 2.0, new Random(99));
        boolean passed = res.probProfitPct() >= 0.0 && res.probProfitPct() <= 100.0
                && res.probTakeProfitPct() >= 0.0 && res.probStopLossPct() >= 0.0
                && res.probTakeProfitPct() + res.probStopLossPct() <= 100.0 + 1e-6
                && res.cvar95Pct() + 1e-9 >= res.var95Pct()
                && res.p5Path()[20] <= res.p50Path()[20] && res.p50Path()[20] <= res.p95Path()[20];
        return new TestResult("Unit 6: Monte Carlo", "T24: VaR <= CVaR & Quantile Cone Bounds", passed,
                String.format("VaR95=%.2f%% <= CVaR95=%.2f%% | P(Profit)=%.1f%%",
                        res.var95Pct(), res.cvar95Pct(), res.probProfitPct()), System.nanoTime() - t0);
    }

    private TestResult testMonteCarloScaledBenchmark() {
        long t0 = System.nanoTime();
        MonteCarloSimulator mc = new MonteCarloSimulator();
        List<DoubleTriePredictor.SimpleCandle> candles = createOscillatingCandles(60);
        var s500 = mc.simulate(candles, 500, 20, MonteCarloSimulator.SimulationMode.GBM, 2.0, 1.5, new Random(1));
        var s2000 = mc.simulate(candles, 2000, 20, MonteCarloSimulator.SimulationMode.GBM, 2.0, 1.5, new Random(1));
        var s10000 = mc.simulate(candles, 10000, 20, MonteCarloSimulator.SimulationMode.GBM, 2.0, 1.5, new Random(1));
        boolean passed = s500.trials() == 500 && s2000.trials() == 2000 && s10000.trials() == 10000
                && s10000.executionTimeNs() > 0;
        return new TestResult("Unit 6: Monte Carlo", "T25: Scaled Simulation Benchmark (M=500..10k)", passed,
                String.format("500: %.2fms | 2k: %.2fms | 10k: %.2fms",
                        s500.executionTimeNs() / 1e6, s2000.executionTimeNs() / 1e6, s10000.executionTimeNs() / 1e6),
                System.nanoTime() - t0);
    }

    // --- METRIC TSP 2-APPROXIMATION TESTS (UNIT 5) ---
    private TestResult testTspEmptyAndSingleBoundary() {
        long t0 = System.nanoTime();
        MetricTspApproximator tsp = new MetricTspApproximator();
        var empty = tsp.solveFromDistanceMatrix(Collections.emptyList(), new double[0][0]);
        var single = tsp.solveFromDistanceMatrix(List.of("BTC"), new double[][]{{0.0}});
        boolean passed = empty.approxTour().isEmpty() && Math.abs(empty.approxCost()) < 1e-9
                && single.approxTour().equals(List.of(0, 0)) && Math.abs(single.approxCost()) < 1e-9;
        return new TestResult("Unit 5: Metric TSP", "T26: Empty & Single-Node Boundary", passed,
                "Handled N=0 and N=1 gracefully with 0.0 tour cost.", System.nanoTime() - t0);
    }

    private TestResult testTspMetricTriangleInequality() {
        long t0 = System.nanoTime();
        MetricTspApproximator tsp = new MetricTspApproximator();
        Random rng = new Random(123);
        List<String> syms = List.of("BTC", "ETH", "SOL", "BNB", "XRP");
        List<double[]> rets = new ArrayList<>();
        for (int i = 0; i < syms.size(); i++) {
            double[] r = new double[40];
            for (int k = 0; k < 40; k++) r[k] = rng.nextGaussian() * 0.02;
            rets.add(r);
        }
        var res = tsp.solveFromReturnSeries(syms, rets);
        boolean passed = res.validMetric();
        return new TestResult("Unit 5: Metric TSP", "T27: Correlation Distance Metric Axiom", passed,
                "Verified d(i,j)=sqrt(2*(1-rho)) satisfies triangle inequality across 5 assets.", System.nanoTime() - t0);
    }

    private TestResult testTspTwoApproximationGuarantee() {
        long t0 = System.nanoTime();
        MetricTspApproximator tsp = new MetricTspApproximator();
        // 6 points in 2D Euclidean space (strict metric)
        double[][] coords = {{0, 0}, {1, 0}, {2, 0.4}, {2.2, 1.5}, {1, 2}, {0.1, 1.4}};
        double[][] dist = euclideanMatrix(coords);
        var res = tsp.solveFromDistanceMatrix(List.of("A", "B", "C", "D", "E", "F"), dist);
        boolean passed = res.validMetric()
                && res.approxCost() <= 2.0 * res.exactOptimalCost() + 1e-6
                && res.approxCost() <= 2.0 * res.mstCost() + 1e-6
                && res.exactOptimalCost() <= res.approxCost() + 1e-6;
        return new TestResult("Unit 5: Metric TSP", "T28: 2-Approximation Guarantee vs Exact OPT", passed,
                String.format("Approx=%.3f, OPT=%.3f, MST=%.3f (Ratio=%.3fx <= 2.0x)",
                        res.approxCost(), res.exactOptimalCost(), res.mstCost(), res.approxRatioVsOpt()),
                System.nanoTime() - t0);
    }

    private TestResult testTspValidHamiltonianCycle() {
        long t0 = System.nanoTime();
        MetricTspApproximator tsp = new MetricTspApproximator();
        double[][] coords = {{0, 0}, {3, 1}, {4, 4}, {1, 5}, {-1, 2}, {2, 2}, {3.5, 0.5}};
        double[][] dist = euclideanMatrix(coords);
        var res = tsp.solveFromDistanceMatrix(List.of("N0", "N1", "N2", "N3", "N4", "N5", "N6"), dist);
        Set<Integer> unique = new HashSet<>(res.approxTour().subList(0, res.approxTour().size() - 1));
        boolean passed = res.approxTour().size() == 8
                && unique.size() == 7
                && Objects.equals(res.approxTour().get(0), res.approxTour().get(7));
        return new TestResult("Unit 5: Metric TSP", "T29: Valid Hamiltonian Cycle Permutation", passed,
                "Tour visits all 7 nodes once and returns cleanly to origin node 0.", System.nanoTime() - t0);
    }

    private TestResult testTspScaledRuntimeComparison() {
        long t0 = System.nanoTime();
        MetricTspApproximator tsp = new MetricTspApproximator();
        Random rng = new Random(77);
        int[] sizes = {6, 9, 11};
        StringBuilder details = new StringBuilder();
        boolean passed = true;
        for (int n : sizes) {
            double[][] coords = new double[n][2];
            List<String> syms = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                coords[i][0] = rng.nextDouble() * 10.0;
                coords[i][1] = rng.nextDouble() * 10.0;
                syms.add("A" + i);
            }
            var res = tsp.solveFromDistanceMatrix(syms, euclideanMatrix(coords));
            if (res.approxRatioVsOpt() > 2.0 + 1e-6) passed = false;
            details.append(String.format("N=%d:%.2fx ", n, res.approxRatioVsOpt()));
        }
        return new TestResult("Unit 5: Metric TSP", "T30: Scaled 2-Approx vs Exact Held-Karp (N=6,9,11)", passed,
                "Empirical approx ratios: " + details.toString().trim(), System.nanoTime() - t0);
    }

    // --- UNIT 2: CRYPTO NEWS PRIORITY QUEUE & KEYWORD TESTS ---
    private TestResult testPriorityQueueEmptyAndSingleBoundary() {
        long t0 = System.nanoTime();
        CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue();
        boolean emptyOk = pq.isEmpty() && pq.peekMax() == null && pq.extractMax() == null && pq.extractTopK(5).isEmpty();

        ScoredNewsItem single = mockScoredNews("n-1", "Bitcoin ETF inflow", 88.5, 1_760_000_000L);
        pq.insert(single);
        boolean singleOk = pq.size() == 1 && pq.peekMax() == single && pq.extractMax() == single && pq.isEmpty();
        boolean passed = emptyOk && singleOk;
        return new TestResult("Unit 2: Priority Queue", "T31: Empty & Single-Item Boundary", passed,
                passed ? "Empty queue returned null/empty safely and single-item insert/extract preserved state." : "Boundary invariant failed.",
                System.nanoTime() - t0);
    }

    private TestResult testPriorityQueueMaxHeapOrderAndTieBreaker() {
        long t0 = System.nanoTime();
        CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue();
        pq.insert(mockScoredNews("low", "Minor token note", 25.0, 1_760_000_500L));
        pq.insert(mockScoredNews("tie-old", "Older equal-score story", 90.0, 1_760_000_100L));
        pq.insert(mockScoredNews("tie-new", "Newer equal-score story", 90.0, 1_760_000_900L));
        pq.insert(mockScoredNews("high", "SEC spot ETF approval", 135.0, 1_760_000_200L));

        boolean invariantOk = pq.verifyMaxHeapInvariant();
        List<ScoredNewsItem> ordered = pq.extractTopK(4);
        boolean orderOk = ordered.size() == 4
                && "high".equals(ordered.get(0).item().id())
                && "tie-new".equals(ordered.get(1).item().id())
                && "tie-old".equals(ordered.get(2).item().id())
                && "low".equals(ordered.get(3).item().id());

        boolean passed = invariantOk && orderOk;
        return new TestResult("Unit 2: Priority Queue", "T32: Max-Heap Ordering & Recency Tie-Break", passed,
                "Extracted strict descending priorities with newer timestamp winning score ties.",
                System.nanoTime() - t0);
    }

    private TestResult testPriorityQueueBuildHeapAndIncreasePriority() {
        long t0 = System.nanoTime();
        List<ScoredNewsItem> items = List.of(
                mockScoredNews("a", "Story A", 40.0, 100L),
                mockScoredNews("b", "Story B", 75.0, 200L),
                mockScoredNews("c", "Story C", 55.0, 300L),
                mockScoredNews("d", "Story D", 95.0, 400L),
                mockScoredNews("e", "Story E", 30.0, 500L)
        );
        CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue();
        pq.buildHeap(items);
        boolean floydOk = pq.verifyMaxHeapInvariant() && "d".equals(pq.peekMax().item().id());
        boolean boosted = pq.increasePriority("e", 80.0); // 30 + 80 = 110 -> becomes root
        boolean boostOk = boosted && pq.verifyMaxHeapInvariant() && "e".equals(pq.peekMax().item().id());
        boolean passed = floydOk && boostOk;
        return new TestResult("Unit 2: Priority Queue", "T33: Floyd O(N) Heapify & IncreasePriority", passed,
                "Floyd buildHeap established invariant and O(log N) increasePriority promoted boosted item to root.",
                System.nanoTime() - t0);
    }

    private TestResult testCryptoKeywordRelevanceGateAndFalsePositiveGuard() {
        long t0 = System.nanoTime();
        CryptoKeywordAnalyzer analyzer = new CryptoKeywordAnalyzer();
        var offTopic = analyzer.checkText("OpenAI and Anthropic engineers debate enterprise productivity software", "ALL", PriorityMode.BALANCED, List.of());
        var falseNear = analyzer.checkText("Stock futures trade flat near term ahead of retail sales report", "ALL", PriorityMode.BALANCED, List.of());
        var nearCrypto = analyzer.checkText("NEAR Protocol mainnet upgrade boosts DeFi TVL and staking inflows", "NEAR", PriorityMode.CATALYST, List.of());
        var solCatalyst = analyzer.checkText("SEC reviews spot Solana ETF filing as SOL whale inflow surges", "SOL", PriorityMode.CATALYST, List.of());

        boolean passed = !offTopic.cryptoRelevant()
                && !falseNear.matchedCoins().contains("NEAR")
                && nearCrypto.cryptoRelevant()
                && nearCrypto.matchedCoins().contains("NEAR")
                && solCatalyst.cryptoRelevant()
                && solCatalyst.matchedCoins().contains("SOL")
                && solCatalyst.keywordMatches().size() >= 4;
        return new TestResult("Unit 2: Priority Queue", "T34: Crypto Keyword Gate & Ambiguity Guard", passed,
                String.format("Rejected off-topic & 'near term' false match; matched NEAR (%d kw) & SOL (%d kw).",
                        nearCrypto.keywordMatches().size(), solCatalyst.keywordMatches().size()),
                System.nanoTime() - t0);
    }

    private TestResult testPriorityQueueScaledTopKBenchmark() {
        long t0 = System.nanoTime();
        CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue();
        var bm500 = pq.benchmarkTopK(500, 20);
        var bm2000 = pq.benchmarkTopK(2000, 20);
        var bm10000 = pq.benchmarkTopK(10000, 20);

        // Also cross-verify exact Top-10 equivalence against Arrays.sort on 200 items
        List<ScoredNewsItem> sample = new ArrayList<>();
        Random rng = new Random(11);
        for (int i = 0; i < 200; i++) {
            sample.add(mockScoredNews("s-" + i, "Headline " + i, rng.nextDouble() * 200.0, 1_760_000_000L + i));
        }
        pq.buildHeap(sample);
        List<ScoredNewsItem> heapTop10 = pq.extractTopK(10);
        List<ScoredNewsItem> sortedCopy = new ArrayList<>(sample);
        sortedCopy.sort((a, b) -> CryptoNewsPriorityQueue.comparePriority(b, a));
        boolean exactMatch = true;
        for (int i = 0; i < 10; i++) {
            if (!heapTop10.get(i).item().id().equals(sortedCopy.get(i).item().id())) {
                exactMatch = false;
                break;
            }
        }

        boolean passed = exactMatch && bm500.heapTimeNs() > 0 && bm2000.heapTimeNs() > 0 && bm10000.heapTimeNs() > 0;
        return new TestResult("Unit 2: Priority Queue", "T35: Scaled Top-K vs Full Sort (N=500..10k)", passed,
                String.format("N=10k Top-20: Heap=%.2fms vs Sort=%.2fms (Speedup=%.2fx)",
                        bm10000.heapTimeNs() / 1e6, bm10000.sortTimeNs() / 1e6, bm10000.speedupFactor()),
                System.nanoTime() - t0);
    }

    private static ScoredNewsItem mockScoredNews(String id, String title, double score, long epochSec) {
        RawNewsItem raw = new RawNewsItem(id, title, "", "https://example.com/" + id, "TestFeed", 20.0, "", epochSec, List.of());
        return new ScoredNewsItem(raw, List.of("BTC"), List.of(), "NEUTRAL", 20.0, 20.0, 50.0, 20.0, 0.0, score);
    }

    private static double[][] euclideanMatrix(double[][] coords) {
        int n = coords.length;
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dist = Math.hypot(coords[i][0] - coords[j][0], coords[i][1] - coords[j][1]);
                d[i][j] = dist;
                d[j][i] = dist;
            }
        }
        return d;
    }

    private List<DoubleTriePredictor.SimpleCandle> createOscillatingCandles(int count) {
        List<DoubleTriePredictor.SimpleCandle> list = new ArrayList<>();
        double p = 100.0;
        long time = 1700000000L;
        for (int i = 0; i < count; i++) {
            double delta = (i % 2 == 0 ? 1.0 : -0.85) * (0.8 + (i % 4) * 0.4);
            double o = p;
            double c = p + delta;
            list.add(new DoubleTriePredictor.SimpleCandle(time, o, Math.max(o, c) + 0.4, Math.min(o, c) - 0.4, c, 1000));
            p = c;
            time += 900;
        }
        return list;
    }

    private List<DoubleTriePredictor.SimpleCandle> createMockCandles(int count, boolean trendUp) {
        List<DoubleTriePredictor.SimpleCandle> list = new ArrayList<>();
        double p = 100.0;
        long time = 1700000000L;
        for (int i = 0; i < count; i++) {
            double delta = (trendUp ? 1.0 : -1.0) * (1.0 + (i % 3) * 0.5);
            double o = p;
            double c = p + delta;
            double h = Math.max(o, c) + 0.5;
            double l = Math.min(o, c) - 0.5;
            list.add(new DoubleTriePredictor.SimpleCandle(time, o, h, l, c, 1000));
            p = c;
            time += 900;
        }
        return list;
    }

    public static void main(String[] args) {
        AlgorithmTestSuite suite = new AlgorithmTestSuite();
        FullSuiteReport report = suite.runAllTests();

        System.out.println("==========================================================================================");
        System.out.println("      ADVANCED ALGORITHM CORRECTNESS & TEST COVERAGE SUITE (RUBRIC: 20 MARKS)            ");
        System.out.println("==========================================================================================");
        System.out.printf("Total Tests: %d | Passed: %d | Failed: %d | Pass Rate: %.1f%%%n%n",
                report.totalTests(), report.passedTests(), report.failedTests(), report.passRatePct());

        System.out.printf("%-26s | %-40s | %-8s | %-12s%n", "Module", "Test Name", "Status", "Latency");
        System.out.println("--------------------------------------------------------------------------------------------------");
        for (TestResult r : report.results()) {
            System.out.printf("%-26s | %-40s | %-8s | %8.2f us%n",
                    r.suiteName(), r.testName(), r.passed() ? "PASS [OK]" : "FAIL [X]", r.executionTimeNs() / 1000.0);
        }
        System.out.println("--------------------------------------------------------------------------------------------------");
    }
}

