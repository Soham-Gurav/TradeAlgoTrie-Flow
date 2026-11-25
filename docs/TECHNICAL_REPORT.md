# CONTINUOUS INTERNAL ASSESSMENT 03 (CIA 3)
## Advanced Algorithm Real-World Application Project: Technical Report
**Course:** Advanced Algorithm (Semester V, B.Tech CS AI & ML)  
**Academic Year:** 2026–2027  
**Institution:** ATLAS – uGDX | School of Technology  

---

### Project Title
**Algorithmic Market Microstructure Engine: Short-Term Price Trajectory & Liquidity Absorption Forecasting using Double Trie Autocomplete, Edmonds-Karp Max-Flow / Min-Cut Networks, and Probabilistic Bloom Screening**

---

### 1. Executive Summary & Problem Formulation
Financial asset exchanges operate on continuous double-auction limit order books (LOB) coupled with aggressive market taker order flows. Conventional technical indicators (such as RSI, MACD, and Bollinger Bands) suffer from significant lag and high failure rates during institutional volatility sweeps because they neglect order flow microstructure.

This project addresses two core challenges in computational finance:
1. **Predicting Discrete Micro-Price Trajectories:** Autocompleting multi-step future price sequences ($t+1 \dots t+k$) based on sequential geometric memory rather than uncalibrated regression.
2. **Detecting Liquidity Absorption vs Breakout:** Determining whether aggressive market order flows (Cumulative Volume Delta, CVD) will penetrate or be absorbed by passive limit order book walls using network flow optimization.
3. **Low-Latency Trap Pre-Screening (CO5):** Filtering incoming tick signatures against known institutional spoofing and stop-hunt patterns in $O(k)$ time using a Counting Bloom Filter.

---

### 2. Syllabus Mapping & Algorithm Selection

The project implements **four syllabus-compliant algorithms from scratch in pure Java (JDK 25)**:

| Unit | Syllabus Topic | Implemented Class | Real-World Application & Mechanism |
| :--- | :--- | :--- | :--- |
| **Unit 4** | **Text Search & Indexing: Tries** | `DoubleTriePredictor.java` | Double Trie (Direction + Magnitude) with Hierarchical Suffix Backoff for Candlestick autofill. |
| **Unit 5** | **Network Flow: Edmonds-Karp Max-Flow / Min-Cut** | `OrderFlowNetwork.java` | Models CVD order flow into capacitated order book depth tiers. Min-Cut identifies absorption bottleneck; Dynamic Sink calculates target vector. |
| **Unit 6 (CO5)** | **Probabilistic Data Structures: Bloom Filter** | `OrderFlowBloomFilter.java` | 4-bit Counting Bloom Filter with Kirsch-Mitzenmacher hash generation for $O(k)$ HFT spoofing signature pre-screening (Zero False Negatives). |
| **Unit 1 / 6** | **Selection & Randomization: Randomized Quickselect** | `RandomizedQuickselect.java` | Expected $O(N)$ quantile selection for rolling Value-at-Risk (VaR 95%) and dynamic ATR volatility thresholds. |

---

### 3. Mathematical Formulations & Complexity Analysis

#### 3.1. Double Trie Structure (Unit 4)
- **Tokenization:** Discrete compound alphabet $\Sigma = \text{Dir} \times \text{Mag}$ where:
  $$\text{Dir} \in \{\text{Bullish } (G), \text{Bearish } (R), \text{Doji } (D)\}$$
  $$\text{Mag} \in \{\text{Small } (S), \text{Medium } (M), \text{Large } (L), \text{Extreme } (X)\}$$
- **Time Complexity:**
  - Training (Insertion): $O(N \cdot L)$ where $N$ is candle count and $L$ is maximum prefix depth ($L = 5$).
  - Autocomplete Query: $O(L)$ to traverse the prefix path.
  - Suffix Backoff: At most $L$ steps of graceful fallback if prefix is unobserved.
- **Space Complexity:** $O(|\Sigma|^L)$ bounded by observed market state transitions.

#### 3.2. Edmonds-Karp Flow Network (Unit 5)
- **Graph Construction:** $G = (V, E)$ where:
  - Source nodes: $S_{Buy}$ (Taker Buy Flow), $S_{Sell}$ (Taker Sell Flow).
  - Intermediate nodes: Ask resistance tiers $A_1 \dots A_k$, Bid support tiers $B_1 \dots B_k$.
  - Edge capacities: $C(u, v) = \text{resting limit depth (in tokens/USDT)}$.
  - Sink node: $T_{dyn}$ (Dynamic Sink).
- **Max-Flow Algorithm:** Breadth-First Search (BFS) finding the shortest augmenting path from source to sink in terms of edge count.
- **Time Complexity:** $O(V \cdot E^2)$ where $|V| \le 20$ (depth tiers) and $|E| \le 40$. BFS executes in $\approx 20\ \mu\text{s}$, well within the 100ms market depth cycle.
- **Min-Cut Theorem:**
  $$\text{MaxFlow}(S, T) = \min_{S \in S', T \in T'} \sum_{u \in S', v \in T'} C(u, v)$$
  The cut edges crossing the partition identify the exact price tier acting as the primary absorption bottleneck.

#### 3.3. Counting Bloom Filter (Unit 6 / CO5)
- **Optimal Hash Functions:**
  $$k = \frac{m}{n} \ln 2$$
- **Theoretical False Positive Rate:**
  $$\epsilon \approx \left(1 - e^{-kn/m}\right)^k$$
- **Query Time:** $O(k)$ where $k \le 8$ hash evaluations (sub-microsecond execution).
- **Zero False Negative Guarantee:** If $\exists i \in [0, k-1]$ such that $\text{Bucket}[h_i(x)] = 0$, then $x \notin S$ with 100% mathematical certainty.

#### 3.4. Randomized Quickselect (Unit 1 / 6)
- **Recurrence Relation:**
  $$T(N) = T\left(\frac{N}{2}\right) + O(N) \implies E[T(N)] = O(N)$$
- **Speedup vs Dual-Pivot QuickSort:** $O(N)$ vs $O(N \log N)$ sorting baseline.

---

### 4. Empirical Test Suite & Verification Results
The test suite `algo.test.AlgorithmTestSuite` validates all 20 correctness conditions:
- **Double Trie:** 5 tests (Empty dataset boundary, tokenization correctness, deep prefix path, suffix backoff, probability normalization).
- **Max-Flow / Min-Cut:** 5 tests (Zero capacity boundary, bottleneck cut theorem, parallel flow saturation, multi-tier absorption, flow conservation).
- **Bloom Filter:** 5 tests (Empty filter, zero false negatives guarantee, false positive calibration $\epsilon \le 0.05$, dynamic deletion, high-load saturation).
- **Randomized Quickselect:** 5 tests (Single element, median ground truth, min/max boundaries, duplicate elements, scaled benchmark $N = 100 \dots 10000$).
- **Overall Suite Pass Rate: 100% (20/20 passed)**.

