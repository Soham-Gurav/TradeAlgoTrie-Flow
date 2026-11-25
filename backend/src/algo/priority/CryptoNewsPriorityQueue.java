package algo.priority;

import algo.priority.NewsModels.*;

import java.util.*;

/**
 * Unit 2: Priority Queues - Self-Implemented Binary Max-Heap for Crypto Event Prioritization.
 *
 * Complexity:
 * - buildHeap (Floyd's bottom-up heapify): O(N)
 * - insert (siftUp): O(log N)
 * - extractMax (siftDown): O(log N)
 * - extractTopK: O(N + K log N) vs Full Sort Baseline O(N log N)
 * - increasePriority (indexed lookup + siftUp): O(log N)
 * - peekMax: O(1)
 *
 * Tie-breaker: When two articles have nearly identical priority scores (|a - b| < 1e-9),
 * the more recently published article has higher priority.
 */
public class CryptoNewsPriorityQueue {

    private ScoredNewsItem[] heap;
    private int size;
    private final Map<String, Integer> indexMap = new HashMap<>();

    public CryptoNewsPriorityQueue() {
        this(32);
    }

    public CryptoNewsPriorityQueue(int initialCapacity) {
        int cap = Math.max(8, initialCapacity);
        this.heap = new ScoredNewsItem[cap];
        this.size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int getHeapHeight() {
        if (size == 0) return 0;
        return (int) Math.floor(Math.log(size) / Math.log(2)) + 1;
    }

    public ScoredNewsItem peekMax() {
        if (size == 0) return null;
        return heap[0];
    }

    /**
     * Inserts a scored news item into the Binary Max-Heap in O(log N) time.
     */
    public void insert(ScoredNewsItem item) {
        if (item == null || item.item() == null) return;
        ensureCapacity(size + 1);
        heap[size] = item;
        indexMap.put(item.item().id(), size);
        siftUp(size);
        size++;
    }

    /**
     * Builds the Binary Max-Heap in O(N) time using Floyd's bottom-up heapify algorithm.
     */
    public void buildHeap(List<ScoredNewsItem> items) {
        clear();
        if (items == null || items.isEmpty()) return;
        ensureCapacity(items.size());

        int idx = 0;
        for (ScoredNewsItem item : items) {
            if (item == null || item.item() == null) continue;
            heap[idx] = item;
            indexMap.put(item.item().id(), idx);
            idx++;
        }
        this.size = idx;

        for (int i = (size / 2) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    /**
     * Removes and returns the highest-priority news item in O(log N) time.
     */
    public ScoredNewsItem extractMax() {
        if (size == 0) return null;
        ScoredNewsItem max = heap[0];
        indexMap.remove(max.item().id());

        int lastIdx = size - 1;
        if (lastIdx > 0) {
            heap[0] = heap[lastIdx];
            indexMap.put(heap[0].item().id(), 0);
        }
        heap[lastIdx] = null;
        size--;

        if (size > 0) {
            siftDown(0);
        }
        return max;
    }

    /**
     * Dynamically boosts an existing item's priority score and restores max-heap order in O(log N).
     */
    public boolean increasePriority(String newsId, double additionalBoost) {
        if (newsId == null || additionalBoost <= 0.0) return false;
        Integer idx = indexMap.get(newsId);
        if (idx == null || idx < 0 || idx >= size) return false;

        ScoredNewsItem old = heap[idx];
        ScoredNewsItem updated = new ScoredNewsItem(
                old.item(),
                old.matchedCoins(),
                old.keywordMatches(),
                old.sentimentHint(),
                old.coinScore(),
                old.keywordScore(),
                old.recencyScore(),
                old.sourceScore(),
                old.customBoostScore() + additionalBoost,
                Math.round((old.priorityScore() + additionalBoost) * 100.0) / 100.0
        );
        heap[idx] = updated;
        siftUp(idx);
        return true;
    }

    /**
     * Non-destructively extracts the Top-K highest-priority items in O(K log N) from a heap copy.
     */
    public List<ScoredNewsItem> peekTopK(int k) {
        if (size == 0 || k <= 0) return Collections.emptyList();
        int count = Math.min(k, size);

        CryptoNewsPriorityQueue copy = new CryptoNewsPriorityQueue(size);
        System.arraycopy(this.heap, 0, copy.heap, 0, this.size);
        copy.size = this.size;
        for (int i = 0; i < copy.size; i++) {
            copy.indexMap.put(copy.heap[i].item().id(), i);
        }

        List<ScoredNewsItem> top = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            top.add(copy.extractMax());
        }
        return top;
    }

    /**
     * Extracts up to K items destructively from this heap in O(K log N) time.
     */
    public List<ScoredNewsItem> extractTopK(int k) {
        if (size == 0 || k <= 0) return Collections.emptyList();
        int count = Math.min(k, size);
        List<ScoredNewsItem> top = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            top.add(extractMax());
        }
        return top;
    }

    /**
     * Exports the leading nodes of the underlying Binary Max-Heap array for tree visualization.
     */
    public List<HeapNodeView> snapshotHeapNodes(int maxNodes) {
        int limit = Math.min(size, Math.max(0, maxNodes));
        List<HeapNodeView> views = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ScoredNewsItem s = heap[i];
            int left = (2 * i + 1 < limit) ? (2 * i + 1) : -1;
            int right = (2 * i + 2 < limit) ? (2 * i + 2) : -1;
            int parent = (i == 0) ? -1 : (i - 1) / 2;
            String primaryCoin = s.matchedCoins().isEmpty() ? "CRYPTO" : s.matchedCoins().get(0);
            views.add(new HeapNodeView(
                    i,
                    s.item().id(),
                    s.item().title(),
                    primaryCoin,
                    s.priorityScore(),
                    parent,
                    left,
                    right
            ));
        }
        return views;
    }

    /**
     * Verifies that every parent in the array strictly dominates its children.
     */
    public boolean verifyMaxHeapInvariant() {
        for (int i = 0; i < size; i++) {
            int left = 2 * i + 1;
            int right = 2 * i + 2;
            if (left < size && comparePriority(heap[left], heap[i]) > 0) return false;
            if (right < size && comparePriority(heap[right], heap[i]) > 0) return false;
        }
        return true;
    }

    public void clear() {
        Arrays.fill(heap, 0, size, null);
        size = 0;
        indexMap.clear();
    }

    /**
     * Empirical Top-K benchmark comparing Floyd O(N) buildHeap + K extractMax (O(N + K log N))
     * against a Full Sort baseline (O(N log N)).
     */
    public PriorityBenchmark benchmarkTopK(int n, int k) {
        int safeN = Math.max(20, Math.min(200_000, n));
        int safeK = Math.max(1, Math.min(safeN, k));

        Random rng = new Random(2026);
        List<ScoredNewsItem> synthetic = new ArrayList<>(safeN);
        long baseTs = 1_760_000_000L;
        for (int i = 0; i < safeN; i++) {
            double score = Math.round(rng.nextDouble() * 300.0 * 100.0) / 100.0;
            RawNewsItem raw = new RawNewsItem("bm-" + i, "Item " + i, "", "", "Bench", 20.0, "", baseTs + i, List.of());
            synthetic.add(new ScoredNewsItem(raw, List.of("BTC"), List.of(), "NEUTRAL", 0, 0, 0, 0, 0, score));
        }

        // 1. Custom Binary Max-Heap Top-K: O(N + K log N)
        long t0 = System.nanoTime();
        CryptoNewsPriorityQueue pq = new CryptoNewsPriorityQueue(safeN);
        pq.buildHeap(synthetic);
        List<ScoredNewsItem> heapTop = pq.extractTopK(safeK);
        long heapNs = System.nanoTime() - t0;

        // 2. Full Sort Baseline: O(N log N)
        long t1 = System.nanoTime();
        ScoredNewsItem[] arr = synthetic.toArray(new ScoredNewsItem[0]);
        Arrays.sort(arr, (a, b) -> comparePriority(b, a));
        List<ScoredNewsItem> sortTop = new ArrayList<>(safeK);
        for (int i = 0; i < safeK; i++) sortTop.add(arr[i]);
        long sortNs = System.nanoTime() - t1;

        // Keep results used to prevent dead-code elimination
        if (heapTop.size() != sortTop.size()) {
            throw new IllegalStateException("Top-K cardinality mismatch");
        }

        double speedup = heapNs > 0 ? (double) sortNs / heapNs : 1.0;
        return new PriorityBenchmark(safeN, safeK, heapNs, sortNs, Math.round(speedup * 100.0) / 100.0);
    }

    public static int comparePriority(ScoredNewsItem a, ScoredNewsItem b) {
        double diff = a.priorityScore() - b.priorityScore();
        if (Math.abs(diff) > 1e-9) {
            return diff > 0 ? 1 : -1;
        }
        int tsCmp = Long.compare(a.item().publishedEpochSec(), b.item().publishedEpochSec());
        if (tsCmp != 0) return tsCmp;
        return b.item().id().compareTo(a.item().id());
    }

    private void siftUp(int idx) {
        int curr = idx;
        while (curr > 0) {
            int parent = (curr - 1) / 2;
            if (comparePriority(heap[curr], heap[parent]) > 0) {
                swap(curr, parent);
                curr = parent;
            } else {
                break;
            }
        }
    }

    private void siftDown(int idx) {
        int curr = idx;
        while (true) {
            int left = 2 * curr + 1;
            int right = 2 * curr + 2;
            int largest = curr;

            if (left < size && comparePriority(heap[left], heap[largest]) > 0) {
                largest = left;
            }
            if (right < size && comparePriority(heap[right], heap[largest]) > 0) {
                largest = right;
            }
            if (largest == curr) break;

            swap(curr, largest);
            curr = largest;
        }
    }

    private void swap(int i, int j) {
        ScoredNewsItem tmp = heap[i];
        heap[i] = heap[j];
        heap[j] = tmp;
        indexMap.put(heap[i].item().id(), i);
        indexMap.put(heap[j].item().id(), j);
    }

    private void ensureCapacity(int minCap) {
        if (minCap <= heap.length) return;
        int nextCap = Math.max(minCap, heap.length * 2);
        heap = Arrays.copyOf(heap, nextCap);
    }
}
