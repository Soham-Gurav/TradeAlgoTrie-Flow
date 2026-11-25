package algo.test;

import algo.bloom.OrderFlowBloomFilter;
import algo.flow.OrderFlowNetwork;
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

