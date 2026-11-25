# CIA 3 Individual Viva & Code Walkthrough Guide
**Allocation:** 15 Marks (Individual Assessment)  
**Time:** ~2 minutes per student  

---

### Student Role 1: Unit 4 — Double Trie Autocomplete Engine
**Core Method to Walk Through:** `algo.trie.DoubleTriePredictor.java -> autofill()` & `tokenize()`

- **Q1: Why use a Double Trie instead of a Markov Chain or simple lookup table?**  
  *Answer:* A standard lookup table only models 1-step unigrams ($P(X_{t+1}|X_t)$). The Double Trie captures hierarchical $k$-gram memory ($k \le 5$) of both Direction ($G, R, D$) and Magnitude ($S, M, L, X$ normalized by 14-period ATR). This separates price momentum from volatility expansion.

- **Q2: What happens if a live 5-candle prefix has never been seen in history?**  
  *Answer:* We implemented **Hierarchical Suffix Backoff**. If the exact prefix of length 5 does not exist, the algorithm drops the oldest candle and queries length 4, length 3, down to length 1. It never crashes and gracefully degrades to unigram transition probabilities.

- **Q3: What is the Time and Space Complexity?**  
  *Answer:*  
  - Insertion (training): $O(N \cdot L)$ where $N$ is candle count and $L$ is max depth ($L=5$).
  - Query (autofill): $O(L)$ to traverse the branch down to the leaf.
  - Space: Bounded by the observed vocabulary transitions $O(|\Sigma|^L)$ where $|\Sigma| = 12$ compound tokens.

---

### Student Role 2: Unit 5 — Edmonds-Karp Flow Network & Dynamic Sink
**Core Method to Walk Through:** `algo.flow.OrderFlowNetwork.java -> solveNetwork()` & `edmondsKarp()`

- **Q1: How do you map financial market microstructure to a Flow Network?**  
  *Answer:*  
  - **Sources ($S$):** Aggressive taker market buys ($S_{Buy}$) and sells ($S_{Sell}$) from real-time Cumulative Volume Delta (CVD).
  - **Nodes ($V$):** Order book depth tiers ($A_1 \dots A_k$, $B_1 \dots B_k$).
  - **Capacities ($C$):** Resting limit order depth at each price level.
  - **Dynamic Sink ($T$):** Unlike a static sink, this sink physically shifts vertically according to net unabsorbed flow pressure $\Phi_{Sink} = \text{Flow}(S_{Buy}) - \text{Flow}(S_{Sell})$.

- **Q2: How does the Min-Cut theorem identify absorption vs breakout?**  
  *Answer:*  
  By the **Max-Flow Min-Cut Theorem**, maximum flow through the network is strictly bounded by the saturated capacity of the minimum cut.  
  - If incoming buy flow $< C_{mincut}$: Flow is absorbed by passive limit orders $\implies$ **Absorption Reversal Down**.
  - If incoming buy flow $\ge C_{mincut}$: The cut is saturated and breached $\implies$ **Bullish Breakout** into a liquidity vacuum.

- **Q3: What is the complexity of your Edmonds-Karp solver?**  
  *Answer:* $O(V \cdot E^2)$ because it uses Breadth-First Search (BFS) to find the shortest augmenting path in terms of edge count. In our order book graph, $|V| \le 20$ and $|E| \le 40$, executing in under $30\ \mu\text{s}$, well within market tick cycles.

---

### Student Role 3: Unit 6 (CO5) & Unit 1 — Counting Bloom Filter & Randomized Quickselect
**Core Method to Walk Through:** `algo.bloom.OrderFlowBloomFilter.java -> screenPattern()` & `algo.stats.RandomizedQuickselect.java -> select()`

- **Q1: Why is a Bloom Filter necessary in high-frequency trading (HFT)?**  
  *Answer:* Graph traversals and deep database queries introduce latency. The Bloom filter provides an ultra-fast $O(k)$ pre-screening layer to test if an incoming order flow signature matches known institutional spoofing or liquidity trap signatures.

- **Q2: What is the mathematical guarantee of your Bloom Filter?**  
  *Answer:* **Zero False Negatives.** If any of the $k$ hash buckets is 0, the signature is 100% guaranteed not to be a trap. If all $k$ buckets are non-zero, it is "possibly present" subject to a false positive probability $\epsilon = (1 - e^{-kn/m})^k$. We use 4-bit counting buckets to allow dynamic deletion as market regimes expire.

- **Q3: What is the purpose of Randomized Quickselect and its complexity?**  
  *Answer:* Calculating dynamic Value-at-Risk (VaR 95%) and ATR volatility quantiles usually requires sorting an array in $O(N \log N)$. Randomized Quickselect finds the $k$-th smallest element in **expected $O(N)$ time** and $O(1)$ auxiliary space by partitioning around a uniformly random pivot.

