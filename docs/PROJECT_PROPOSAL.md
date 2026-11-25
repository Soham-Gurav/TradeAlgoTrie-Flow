# CONTINUOUS INTERNAL ASSESSMENT 03 (CIA 3)
## Project Proposal: Algorithmic Market Microstructure Engine
**Course:** Advanced Algorithm (Semester V, B.Tech CS AI & ML)  
**Academic Year:** 2026–2027  
**Team Allocation:** 2–3 Students  

---

### 1. Project Title & Problem Domain
**Title:** Real-Time Order Flow Liquidity Network & Double Trie Predictive Autocomplete Engine for Crypto Asset Trajectories  
**Domain:** Computational Finance, High-Frequency Market Microstructure, Algorithmic Network Optimization.

---

### 2. Real-World Problem & Target User
- **Target User:** Quantitative trading firms, liquidity providers, and automated execution algorithms.
- **Problem Statement:** In contemporary crypto markets, retail algorithmic strategies rely heavily on lagging indicators (RSI, Moving Averages) which result in massive drawdowns during institutional liquidity sweeps. Market participants need to know:
  1. What is the most probable sequence of future candlestick developments given recent geometric structure?
  2. Will incoming aggressive buying or selling pressure (CVD) penetrate limit order book walls, or will it be absorbed by passive liquidity clusters?
  3. Can we screen market micro-sequences against known spoofing/manipulation signatures without introducing computational bottlenecks?

---

### 3. Core Techniques & Why Each is Appropriate
1. **Unit 4: Double Trie with Suffix Backoff (Tries)**
   - *Why Appropriate:* Treats price action as compound token n-grams $(\text{Direction}, \text{Magnitude})$. Employs prefix search to project future candlesticks in $O(L)$ time with probabilistic confidence scoring and graceful suffix degradation.
2. **Unit 5: Edmonds-Karp Max-Flow / Min-Cut (Network Flow)**
   - *Why Appropriate:* Formulates order flow as a fluid dynamics network. Sources inject aggressive taker flow; order book tiers provide capacitated resistance; the Min-Cut identifies the exact bottleneck absorption wall; and the Dynamic Sink calculates target price vectors.
3. **Unit 6 (CO5): Counting Bloom Filter (Probabilistic Data Structure)**
   - *Why Appropriate:* High-frequency streams require sub-microsecond pre-screening of order signatures against spoofing blacklists. Guarantees **Zero False Negatives** in $O(k)$ time before triggering heavier graph solvers.
4. **Unit 1 / 6: Randomized Quickselect (Randomized Selection)**
   - *Why Appropriate:* Computes Value-at-Risk (VaR 95%) and dynamic ATR quantile thresholds in expected $O(N)$ time without sorting the whole array.

---

### 4. Data Source & Constraints
- **Primary Data:** Live Binance WebSocket streams (`@depth20@100ms` for Level-2 limit order book and `@aggTrade` for real-time Cumulative Volume Delta).
- **Constraints & Edge Cases:**
  - Network disconnection / WebSocket reconnect handling.
  - Zero capacity / empty order book tiers.
  - Unobserved prefix sequences handled via hierarchical suffix backoff.
  - Bounded memory footprint via 4-bit counting buckets in Bloom filter.

---

### 5. Measurable Success Criteria
1. **Out-of-sample Directional Accuracy:** Outperform a 50% random walk on high-momentum assets (e.g. SOL, DOGE, NEAR achieving $> 52\%$ accuracy and Profit Factor $> 1.15$).
2. **Solver Latency:** Solve Edmonds-Karp Max-Flow and Min-Cut in $< 1\text{ ms}$ on 20-node depth networks.
3. **Bloom Screening:** $O(k)$ lookup with zero false negatives and calibrated false positive rate $\epsilon \le 0.05$.
4. **Test Suite:** 100% pass rate across 20 documented test cases covering boundary, edge, and stress scenarios.

