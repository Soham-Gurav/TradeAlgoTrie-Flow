# Algorithmic Market Microstructure Engine
### CIA 3 — Advanced Algorithm Real-World Application Project
**Academic Year:** 2026–2027  
**Course:** Advanced Algorithm (Semester V, B.Tech CS AI & ML)  
**Institution:** ATLAS – uGDX | School of Technology  
**Rubric Evaluation:** 100 Marks  

---

## 🌟 Overview
An institutional-grade algorithmic cryptocurrency trading and order flow microstructure engine. Built from scratch in pure **Java 25 (Backend)** with an ultra-clean **Next.js 14 / React (Frontend)** designed after the minimalist **Taylor Framer template**.

The system addresses the failure of conventional retail indicators (RSI, Moving Averages) during institutional volatility sweeps by deploying four syllabus-compliant advanced algorithms:
1. **Unit 4 (Text Search & Indexing):** `DoubleTriePredictor` — Tokenizes financial price action into compound tokens $(\text{Dir} \times \text{Mag})$ and autofills the next $N$ future bars using hierarchical suffix backoff.
2. **Unit 5 (Network Flow & Approximation):** `OrderFlowNetwork` — Models Cumulative Volume Delta (CVD) and L2 order book depth as a capacitated flow network. Solves Edmonds-Karp Max-Flow and Min-Cut to identify institutional absorption bottlenecks and project dynamic sink vectors.
3. **Unit 6 (CO5 Probabilistic Data Structure):** `OrderFlowBloomFilter` — 4-bit Counting Bloom Filter with Kirsch-Mitzenmacher hashing for $O(k)$ pre-screening of order sequences against institutional spoofing/trap signatures with **Zero False Negatives**.
4. **Unit 1 & 6 (Selection & Randomization):** `RandomizedQuickselect` — Expected $O(N)$ quantile selection for real-time Value-at-Risk (VaR 95%) and dynamic ATR volatility thresholds.

---

## 🚀 Quick Start (Unified 1-Command Launcher)

### Prerequisites
- **Java Development Kit (JDK 17+ or JDK 25)**
- **Node.js (v18+ or v22+) & npm**

### Run Full-Stack System
From the repository root (`d:\TradeAlgo`), simply run:
```powershell
npm run dev
```
*(Automatically compiles Java backend algorithms, launches the Java REST API on `http://localhost:8085`, and starts the Next.js frontend on `http://localhost:3000`)*

Open [http://localhost:3000](http://localhost:3000) in your web browser.

---

## 🧪 Automated Academic Test Suite (Rubric: 20 Marks)
To run the 20-test verification suite with zero dependencies:
```powershell
java -cp backend/bin algo.test.AlgorithmTestSuite
```
**Test Matrix Summary:**
- **Double Trie:** Empty inputs, tokenization validity, deep prefix matching, suffix backoff, probability normalization (5/5 PASS).
- **Max-Flow / Min-Cut:** Zero capacity, Min-Cut theorem bottleneck, parallel saturation, multi-tier absorption, flow conservation (5/5 PASS).
- **Counting Bloom Filter (CO5):** Empty filter, zero false negatives guarantee, false positive calibration ($\epsilon \le 0.05$), dynamic deletion, saturation telemetry (5/5 PASS).
- **Randomized Quickselect:** Single element, median ground truth, min/max boundaries, duplicate handling, scaled benchmark $N = 100 \dots 10000$ (5/5 PASS).
- **Result: 20 / 20 Tests Passed (100% Pass Rate).**

---

## 📡 REST API Contract

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/health` | Backend status, active algorithms, and syllabus metadata |
| `GET` | `/api/trie/predict?symbol=SOLUSDT&interval=15m&steps=5` | Double Trie prefix autofill on live market data |
| `GET` | `/api/flow/solve?symbol=SOLUSDT` | Solves Edmonds-Karp Max-Flow and Min-Cut bottleneck |
| `GET` | `/api/bloom/screen?signature=SPOOF_BID_ICEBERG_LARGE` | Screens order flow signature through Bloom filter |
| `GET` | `/api/stats/benchmark?size=10000` | Executes empirical Quickselect vs Sort benchmark |
| `GET` | `/api/test/run` | Runs full 20-test academic test harness and returns JSON report |

---

## 📁 Repository Structure
```
d:\TradeAlgo\
├── backend/                  # Pure Java REST Backend
│   ├── src/algo/trie/        # Double Trie & Suffix Backoff (Unit 4)
│   ├── src/algo/flow/        # Edmonds-Karp & Min-Cut (Unit 5)
│   ├── src/algo/bloom/       # Counting Bloom Filter (Unit 6 / CO5)
│   ├── src/algo/stats/       # Randomized Quickselect & Backtester (Unit 1/6)
│   ├── src/algo/test/        # 20 Academic Unit Tests (Rubric: 20 Marks)
│   └── src/algo/api/         # High-Speed Embedded REST API Server
├── frontend/                 # Next.js 14 App (Taylor Minimalist Template)
│   ├── app/                  # React App Router pages & interactive canvas visualizer
│   └── tailwind.config.ts    # Taylor Framer styling system
├── docs/                     # Submission Package
│   ├── TECHNICAL_REPORT.md   # Complete 5-8 page report with mathematical proofs
│   ├── PROJECT_PROPOSAL.md   # Formal project proposal deliverable
│   └── VIVA_WALKTHROUGH.md   # Individual viva defense questions & answers
├── run-backend.bat           # 1-Click backend launcher
├── run-frontend.bat          # 1-Click frontend launcher
└── README.md                 # This file
```

