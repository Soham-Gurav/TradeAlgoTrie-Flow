package algo.stats;

import java.util.Random;

/**
 * Unit 1 & Unit 6: Randomized Selection (Quickselect) for Rolling Value-at-Risk (VaR)
 * and Volatility Quantile Thresholds.
 * 
 * Mathematical Complexity:
 * - Expected Time Complexity: O(N) using uniform random pivot partitioning.
 * - Worst-Case Time Complexity: O(N^2) (mitigated by random pivot selection).
 * - Auxiliary Space Complexity: O(1) in-place.
 * 
 * Baseline Comparison:
 * - Full Sort (QuickSort / Dual-Pivot): O(N log N).
 * - Demonstrates clear computational speedup on large tick streams (e.g. N = 10,000+).
 */
public class RandomizedQuickselect {

    private final Random random = new Random();

    public record SelectionBenchmark(
            int arraySize,
            double quantilePercentile,
            double selectedValue,
            long quickselectTimeNs,
            long sortBaselineTimeNs,
            double speedupFactor
    ) {}

    /**
     * Finds the k-th smallest element in array in expected O(N) time.
     * k is 0-indexed (0 to array.length - 1).
     */
    public double select(double[] array, int k) {
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }
        if (k < 0 || k >= array.length) {
            throw new IndexOutOfBoundsException("k must be within [0, " + (array.length - 1) + "]");
        }

        double[] copy = array.clone();
        return quickselectInternal(copy, 0, copy.length - 1, k);
    }

    /**
     * Calculates empirical Value-at-Risk (VaR) or volatility percentile in O(N).
     * e.g., percentile = 0.95 for 95th percentile risk.
     */
    public double selectPercentile(double[] array, double percentile) {
        if (percentile < 0.0) percentile = 0.0;
        if (percentile > 1.0) percentile = 1.0;
        int k = (int) Math.round(percentile * (array.length - 1));
        return select(array, k);
    }

    private double quickselectInternal(double[] a, int left, int right, int k) {
        while (left < right) {
            int pivotIndex = left + random.nextInt(right - left + 1);
            int partitionIndex = partition(a, left, right, pivotIndex);

            if (partitionIndex == k) {
                return a[k];
            } else if (k < partitionIndex) {
                right = partitionIndex - 1;
            } else {
                left = partitionIndex + 1;
            }
        }
        return a[left];
    }

    private int partition(double[] a, int left, int right, int pivotIndex) {
        double pivotValue = a[pivotIndex];
        swap(a, pivotIndex, right);
        int storeIndex = left;

        for (int i = left; i < right; i++) {
            if (a[i] < pivotValue) {
                swap(a, storeIndex, i);
                storeIndex++;
            }
        }
        swap(a, storeIndex, right);
        return storeIndex;
    }

    private void swap(double[] a, int i, int j) {
        double temp = a[i];
        a[i] = a[j];
        a[j] = temp;
    }

    /**
     * Runs an empirical benchmark comparing Randomized Quickselect O(N) vs Full Sort O(N log N).
     */
    public SelectionBenchmark benchmark(int size, double percentile) {
        double[] data = new double[size];
        for (int i = 0; i < size; i++) {
            data[i] = random.nextGaussian() * 10.0 + 50.0;
        }

        int k = (int) Math.round(percentile * (size - 1));

        // 1. Measure Quickselect
        long t0 = System.nanoTime();
        double qsVal = select(data, k);
        long qsTime = System.nanoTime() - t0;

        // 2. Measure Full Sort Baseline
        long t1 = System.nanoTime();
        double[] sortCopy = data.clone();
        java.util.Arrays.sort(sortCopy);
        double sortVal = sortCopy[k];
        long sortTime = System.nanoTime() - t1;

        double speedup = qsTime > 0 ? (double) sortTime / qsTime : 1.0;
        return new SelectionBenchmark(size, percentile, qsVal, qsTime, sortTime, speedup);
    }
}

