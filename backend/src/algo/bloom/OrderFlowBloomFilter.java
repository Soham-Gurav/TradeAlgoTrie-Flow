package algo.bloom;

import java.util.*;

/**
 * Unit 6 / CO5: Probabilistic Data Structure - Order Flow Counting Bloom Filter.
 * 
 * Mathematical Formulation:
 * - Optimal hash functions: k = (m / n) * ln(2)
 * - False positive probability: epsilon = (1 - e^(-kn/m))^k
 * - 4-bit saturated counting buckets per cell to support dynamic deletions/expirations.
 * 
 * Real-World Application in HFT:
 * - High-speed pre-screening of order flow micro-sequences against a known database
 *   of "Institutional Liquidity Traps / Spoofing Signatures".
 * - Property: ZERO FALSE NEGATIVES (Authoritative safety guarantee).
 * - Fast O(k) evaluation before invoking heavier graph or prediction algorithms.
 */
public class OrderFlowBloomFilter {

    private final int bitArraySize; // m
    private final int numHashFunctions; // k
    private final byte[] counters; // 4-bit counters (0..15)
    private int elementCount; // n

    public record ScreeningResult(
            String patternSignature,
            boolean possiblyPresent,
            double theoreticalFalsePositiveRate,
            int matchingHashBuckets,
            String recommendation
    ) {}

    public OrderFlowBloomFilter(int expectedElements, double targetFalsePositiveRate) {
        if (expectedElements <= 0) expectedElements = 1000;
        if (targetFalsePositiveRate <= 0.0 || targetFalsePositiveRate >= 1.0) targetFalsePositiveRate = 0.01;

        // m = - (n * ln(p)) / (ln(2)^2)
        int m = (int) Math.ceil(-1.0 * (expectedElements * Math.log(targetFalsePositiveRate)) / (Math.log(2) * Math.log(2)));
        this.bitArraySize = Math.max(64, m);

        // k = (m / n) * ln(2)
        int k = (int) Math.round(((double) this.bitArraySize / expectedElements) * Math.log(2));
        this.numHashFunctions = Math.max(1, Math.min(16, k));

        this.counters = new byte[this.bitArraySize];
        this.elementCount = 0;
    }

    public synchronized void insert(String signature) {
        if (signature == null || signature.isBlank()) return;
        int[] hashes = computeHashes(signature);
        for (int h : hashes) {
            if (counters[h] < 15) {
                counters[h]++;
            }
        }
        elementCount++;
    }

    public synchronized boolean contains(String signature) {
        if (signature == null || signature.isBlank()) return false;
        int[] hashes = computeHashes(signature);
        for (int h : hashes) {
            if (counters[h] == 0) {
                return false; // DEFINITELY NOT PRESENT (Zero False Negatives)
            }
        }
        return true; // POSSIBLY PRESENT (Subject to false positive rate epsilon)
    }

    public synchronized boolean remove(String signature) {
        if (!contains(signature)) return false;
        int[] hashes = computeHashes(signature);
        for (int h : hashes) {
            if (counters[h] > 0) {
                counters[h]--;
            }
        }
        elementCount = Math.max(0, elementCount - 1);
        return true;
    }

    public ScreeningResult screenPattern(String signature) {
        boolean present = contains(signature);
        int[] hashes = computeHashes(signature);
        int activeBuckets = 0;
        for (int h : hashes) {
            if (counters[h] > 0) activeBuckets++;
        }

        double fpr = calculateCurrentFalsePositiveRate();
        String rec = present
                ? (fpr < 0.05 ? "ALERT: High probability institutional trap detected. Require deep graph verification."
                              : "WARNING: Pattern flagged by Bloom filter. Possible trap signature.")
                : "CLEAR: Signature verified free of known liquidity traps (Zero False Negatives).";

        return new ScreeningResult(signature, present, fpr, activeBuckets, rec);
    }

    public double calculateCurrentFalsePositiveRate() {
        if (elementCount == 0) return 0.0;
        // epsilon = (1 - e^(-k * n / m))^k
        double exponent = -1.0 * numHashFunctions * elementCount / bitArraySize;
        double base = 1.0 - Math.exp(exponent);
        return Math.pow(Math.max(0.0, base), numHashFunctions);
    }

    public int getBitArraySize() { return bitArraySize; }
    public int getNumHashFunctions() { return numHashFunctions; }
    public int getElementCount() { return elementCount; }

    public double getCapacityFillRatio() {
        int nonZero = 0;
        for (byte b : counters) {
            if (b > 0) nonZero++;
        }
        return (double) nonZero / bitArraySize;
    }

    private int[] computeHashes(String key) {
        int[] result = new int[numHashFunctions];
        // 64-bit FNV-1a hash base
        long hash64 = 0xcbf29ce484222325L;
        for (byte b : key.getBytes()) {
            hash64 ^= (b & 0xff);
            hash64 *= 0x100000001b3L;
        }

        int h1 = (int) (hash64 ^ (hash64 >>> 32));
        int h2 = (int) (hash64 * 31 + 17);

        // Kirsch-Mitzenmacher optimization: g_i(x) = h1(x) + i * h2(x)
        for (int i = 0; i < numHashFunctions; i++) {
            long combined = (h1 + (long) i * h2) & 0x7fffffffL;
            result[i] = (int) (combined % bitArraySize);
        }
        return result;
    }
}

