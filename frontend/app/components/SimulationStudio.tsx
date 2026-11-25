"use client";

import React, { useState, useEffect, useRef } from "react";
import { Play, Activity, GitBranch, RefreshCw } from "lucide-react";
import MagicBento from "./MagicBento";
import CoinLogo from "./CoinLogo";

const BACKEND_URL = "http://localhost:8085";

const TSP_CANDIDATE_ASSETS = [
  "BTCUSDT",
  "ETHUSDT",
  "SOLUSDT",
  "BNBUSDT",
  "XRPUSDT",
  "DOGEUSDT",
  "AVAXUSDT",
  "LINKUSDT",
  "ADAUSDT",
  "SUIUSDT",
];

interface SimulationStudioProps {
  selectedPair: string;
  onSelectPair: (pair: string) => void;
  availablePairs: string[];
}

export default function SimulationStudio({
  selectedPair,
  onSelectPair,
  availablePairs,
}: SimulationStudioProps) {
  // Unit 6 Monte Carlo Controls & Result
  const [simInterval, setSimInterval] = useState<string>("15m");
  const [simMode, setSimMode] = useState<"GBM" | "BOOTSTRAP">("GBM");
  const [trials, setTrials] = useState<number>(2000);
  const [horizonSteps, setHorizonSteps] = useState<number>(30);
  const [tpPct, setTpPct] = useState<number>(2.0);
  const [slPct, setSlPct] = useState<number>(1.5);
  const [mcData, setMcData] = useState<any>(null);
  const [mcLoading, setMcLoading] = useState<boolean>(false);
  const [mcError, setMcError] = useState<string | null>(null);

  // Unit 5 Metric TSP Controls & Result
  const [tspSymbols, setTspSymbols] = useState<string[]>([
    "BTCUSDT",
    "ETHUSDT",
    "SOLUSDT",
    "BNBUSDT",
    "XRPUSDT",
    "DOGEUSDT",
    "AVAXUSDT",
    "LINKUSDT",
  ]);
  const [tspInterval, setTspInterval] = useState<string>("1h");
  const [showExactOverlay, setShowExactOverlay] = useState<boolean>(true);
  const [tspData, setTspData] = useState<any>(null);
  const [tspLoading, setTspLoading] = useState<boolean>(false);
  const [tspError, setTspError] = useState<string | null>(null);

  const mcCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const tspCanvasRef = useRef<HTMLCanvasElement | null>(null);

  const runMonteCarlo = async () => {
    setMcLoading(true);
    setMcError(null);
    try {
      const url = `${BACKEND_URL}/api/sim/montecarlo?symbol=${encodeURIComponent(
        selectedPair
      )}&interval=${encodeURIComponent(simInterval)}&trials=${trials}&horizon=${horizonSteps}&mode=${simMode}&tpPct=${tpPct}&slPct=${slPct}`;
      const res = await fetch(url);
      const data = await res.json();
      if (!res.ok || data.error) {
        setMcError(data.error || "Monte Carlo simulation failed");
      } else {
        setMcData(data);
      }
    } catch (err: any) {
      setMcError(err?.message || "Could not reach Java backend :8085");
    } finally {
      setMcLoading(false);
    }
  };

  const runMetricTsp = async (symbolsOverride?: string[]) => {
    const activeSyms = symbolsOverride ?? tspSymbols;
    if (activeSyms.length < 2) return;
    setTspLoading(true);
    setTspError(null);
    try {
      const url = `${BACKEND_URL}/api/sim/tsp?symbols=${encodeURIComponent(
        activeSyms.join(",")
      )}&interval=${encodeURIComponent(tspInterval)}`;
      const res = await fetch(url);
      const data = await res.json();
      if (!res.ok || data.error) {
        setTspError(data.error || "Metric TSP calculation failed");
      } else {
        setTspData(data);
      }
    } catch (err: any) {
      setTspError(err?.message || "Could not reach Java backend :8085");
    } finally {
      setTspLoading(false);
    }
  };

  useEffect(() => {
    runMonteCarlo();
  }, [selectedPair, simInterval, simMode, trials, horizonSteps]);

  useEffect(() => {
    runMetricTsp();
  }, [tspInterval]);

  const toggleTspAsset = (sym: string) => {
    setTspSymbols((prev) => {
      if (prev.includes(sym)) {
        if (prev.length <= 3) return prev; // Keep at least 3 nodes for a cycle
        const next = prev.filter((s) => s !== sym);
        runMetricTsp(next);
        return next;
      } else {
        if (prev.length >= 10) return prev;
        const next = [...prev, sym];
        runMetricTsp(next);
        return next;
      }
    });
  };

  // Draw High-DPI Monte Carlo Fan Chart
  useEffect(() => {
    const canvas = mcCanvasRef.current;
    if (!canvas || !mcData) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const renderFan = () => {
      const parent = canvas.parentElement;
      if (!parent) return;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = Math.max(320, parent.clientWidth);
      const h = Math.max(260, parent.clientHeight);
      if (canvas.width !== Math.floor(w * dpr) || canvas.height !== Math.floor(h * dpr)) {
        canvas.width = Math.floor(w * dpr);
        canvas.height = Math.floor(h * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);

      const padL = 56;
      const padR = 16;
      const padT = 20;
      const padB = 28;
      const plotW = w - padL - padR;
      const plotH = h - padT - padB;

      const p5: number[] = mcData.p5Path || [];
      const p25: number[] = mcData.p25Path || [];
      const p50: number[] = mcData.p50Path || [];
      const p75: number[] = mcData.p75Path || [];
      const p95: number[] = mcData.p95Path || [];
      const samples: number[][] = mcData.samplePaths || [];
      const spot: number = mcData.spotPrice || 100;
      const tpPrice = spot * (1 + (mcData.tpPct || 2) / 100);
      const slPrice = spot * (1 - (mcData.slPct || 1.5) / 100);

      let minP = Math.min(...p5, slPrice);
      let maxP = Math.max(...p95, tpPrice);
      for (const path of samples) {
        for (const v of path) {
          if (v < minP) minP = v;
          if (v > maxP) maxP = v;
        }
      }
      const padVal = Math.max((maxP - minP) * 0.08, spot * 0.002);
      minP -= padVal;
      maxP += padVal;
      const spanP = Math.max(maxP - minP, 1e-9);
      const steps = Math.max(1, p50.length - 1);

      const xFor = (step: number) => padL + (step / steps) * plotW;
      const yFor = (price: number) => padT + (1 - (price - minP) / spanP) * plotH;

      // Subtle gridlines & Y-axis labels
      ctx.save();
      ctx.font = '600 10px "JetBrains Mono", monospace';
      ctx.textAlign = "right";
      ctx.textBaseline = "middle";
      for (let g = 0; g <= 4; g++) {
        const frac = g / 4;
        const val = maxP - frac * spanP;
        const gy = padT + frac * plotH;
        ctx.strokeStyle = "rgba(255,255,255,0.06)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(padL, gy);
        ctx.lineTo(w - padR, gy);
        ctx.stroke();

        ctx.fillStyle = "#64748b";
        const label = val >= 100 ? `$${val.toFixed(1)}` : `$${val.toFixed(4)}`;
        ctx.fillText(label, padL - 6, gy);
      }
      ctx.restore();

      // Shaded P5 - P95 cone
      if (p95.length > 1 && p5.length === p95.length) {
        ctx.save();
        ctx.fillStyle = "rgba(56, 189, 248, 0.10)";
        ctx.beginPath();
        p95.forEach((v, idx) => {
          const x = xFor(idx);
          const y = yFor(v);
          if (idx === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        for (let idx = p5.length - 1; idx >= 0; idx--) {
          ctx.lineTo(xFor(idx), yFor(p5[idx]));
        }
        ctx.closePath();
        ctx.fill();
        ctx.restore();
      }

      // Shaded P25 - P75 interquartile cone
      if (p75.length > 1 && p25.length === p75.length) {
        ctx.save();
        ctx.fillStyle = "rgba(16, 185, 129, 0.16)";
        ctx.beginPath();
        p75.forEach((v, idx) => {
          const x = xFor(idx);
          const y = yFor(v);
          if (idx === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        for (let idx = p25.length - 1; idx >= 0; idx--) {
          ctx.lineTo(xFor(idx), yFor(p25[idx]));
        }
        ctx.closePath();
        ctx.fill();
        ctx.restore();
      }

      // Sample individual Monte Carlo paths
      samples.forEach((path) => {
        const term = path[path.length - 1] ?? spot;
        ctx.save();
        ctx.strokeStyle =
          term >= spot ? "rgba(16, 185, 129, 0.24)" : "rgba(244, 63, 94, 0.24)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        path.forEach((v, idx) => {
          const x = xFor(idx);
          const y = yFor(v);
          if (idx === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        ctx.stroke();
        ctx.restore();
      });

      // TP & SL barrier lines
      const drawBarrier = (price: number, color: string, label: string) => {
        const by = yFor(price);
        if (by < padT || by > padT + plotH) return;
        ctx.save();
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.25;
        ctx.setLineDash([5, 4]);
        ctx.beginPath();
        ctx.moveTo(padL, by);
        ctx.lineTo(w - padR, by);
        ctx.stroke();
        ctx.fillStyle = color;
        ctx.font = '700 9px "JetBrains Mono", monospace';
        ctx.textAlign = "right";
        ctx.fillText(label, w - padR - 4, by - 6);
        ctx.restore();
      };
      drawBarrier(tpPrice, "#10b981", `TP +${mcData.tpPct}%`);
      drawBarrier(slPrice, "#f43f5e", `SL -${mcData.slPct}%`);

      // P5 & P95 boundary strokes + Bold P50 Median
      const drawCurve = (arr: number[], color: string, width: number, dashed = false) => {
        if (arr.length < 2) return;
        ctx.save();
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        if (dashed) ctx.setLineDash([3, 3]);
        ctx.beginPath();
        arr.forEach((v, idx) => {
          const x = xFor(idx);
          const y = yFor(v);
          if (idx === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        ctx.stroke();
        ctx.restore();
      };
      drawCurve(p95, "rgba(56, 189, 248, 0.85)", 1.3, true);
      drawCurve(p5, "rgba(56, 189, 248, 0.85)", 1.3, true);
      drawCurve(p50, "#f8fafc", 2.4, false);

      // X-axis step markers
      ctx.save();
      ctx.fillStyle = "#64748b";
      ctx.font = '600 10px "JetBrains Mono", monospace';
      ctx.textAlign = "center";
      [0, Math.floor(steps / 2), steps].forEach((st) => {
        ctx.fillText(`t+${st}`, xFor(st), h - 10);
      });
      ctx.restore();
    };

    renderFan();
    const ro = new ResizeObserver(renderFan);
    if (canvas.parentElement) ro.observe(canvas.parentElement);
    return () => ro.disconnect();
  }, [mcData]);

  // Draw High-DPI Metric TSP Graph (MST + 2-Approx Tour + Optional Exact Overlay)
  useEffect(() => {
    const canvas = tspCanvasRef.current;
    if (!canvas || !tspData) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const renderTsp = () => {
      const parent = canvas.parentElement;
      if (!parent) return;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = Math.max(320, parent.clientWidth);
      const h = Math.max(260, parent.clientHeight);
      if (canvas.width !== Math.floor(w * dpr) || canvas.height !== Math.floor(h * dpr)) {
        canvas.width = Math.floor(w * dpr);
        canvas.height = Math.floor(h * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);

      // Subtle architectural dot grid
      ctx.fillStyle = "rgba(255, 255, 255, 0.04)";
      for (let gx = 20; gx < w; gx += 24) {
        for (let gy = 20; gy < h; gy += 24) {
          ctx.fillRect(gx, gy, 1, 1);
        }
      }

      const nodes: { index: number; symbol: string; x: number; y: number }[] = tspData.nodes || [];
      const mstEdges: { from: number; to: number; weight: number }[] = tspData.mstEdges || [];
      const approxTour: number[] = tspData.approxTour || [];
      const exactTour: number[] = tspData.exactOptimalTour || [];

      if (nodes.length === 0) return;
      const px = (nx: number) => nx * w;
      const py = (ny: number) => ny * h;

      // 1. Draw Prim's MST backbone (amber dashed)
      mstEdges.forEach((e) => {
        const a = nodes[e.from];
        const b = nodes[e.to];
        if (!a || !b) return;
        ctx.save();
        ctx.strokeStyle = "rgba(245, 158, 11, 0.55)";
        ctx.lineWidth = 2;
        ctx.setLineDash([5, 5]);
        ctx.beginPath();
        ctx.moveTo(px(a.x), py(a.y));
        ctx.lineTo(px(b.x), py(b.y));
        ctx.stroke();
        ctx.restore();
      });

      // 2. Optional Exact Optimal Held-Karp Tour (sky subtle dashed)
      if (showExactOverlay && exactTour.length > 1) {
        ctx.save();
        ctx.strokeStyle = "rgba(56, 189, 248, 0.5)";
        ctx.lineWidth = 2;
        ctx.setLineDash([2, 4]);
        ctx.beginPath();
        exactTour.forEach((idx, i) => {
          const nd = nodes[idx];
          if (!nd) return;
          if (i === 0) ctx.moveTo(px(nd.x), py(nd.y));
          else ctx.lineTo(px(nd.x), py(nd.y));
        });
        ctx.stroke();
        ctx.restore();
      }

      // 3. Draw 2-Approximation Hamiltonian Tour (emerald solid with directional midpoint arrows)
      if (approxTour.length > 1) {
        for (let i = 0; i < approxTour.length - 1; i++) {
          const a = nodes[approxTour[i]];
          const b = nodes[approxTour[i + 1]];
          if (!a || !b) continue;
          const x1 = px(a.x);
          const y1 = py(a.y);
          const x2 = px(b.x);
          const y2 = py(b.y);

          ctx.save();
          ctx.strokeStyle = "#10b981";
          ctx.lineWidth = 2.25;
          ctx.shadowColor = "rgba(16, 185, 129, 0.45)";
          ctx.shadowBlur = 8;
          ctx.beginPath();
          ctx.moveTo(x1, y1);
          ctx.lineTo(x2, y2);
          ctx.stroke();

          // Arrowhead at 65% of segment
          const mx = x1 + (x2 - x1) * 0.65;
          const my = y1 + (y2 - y1) * 0.65;
          const angle = Math.atan2(y2 - y1, x2 - x1);
          ctx.fillStyle = "#10b981";
          ctx.beginPath();
          ctx.moveTo(mx + 6 * Math.cos(angle), my + 6 * Math.sin(angle));
          ctx.lineTo(mx - 5 * Math.cos(angle - 0.5), my - 5 * Math.sin(angle - 0.5));
          ctx.lineTo(mx - 5 * Math.cos(angle + 0.5), my - 5 * Math.sin(angle + 0.5));
          ctx.closePath();
          ctx.fill();
          ctx.restore();
        }
      }

      // 4. Draw Asset Nodes
      nodes.forEach((nd, i) => {
        const cx = px(nd.x);
        const cy = py(nd.y);
        const shortSym = nd.symbol.replace(/USDT$/i, "");
        const isRoot = i === 0;

        ctx.save();
        ctx.fillStyle = "#0b0e14";
        ctx.strokeStyle = isRoot ? "#f59e0b" : "#10b981";
        ctx.lineWidth = isRoot ? 2.5 : 2;
        ctx.beginPath();
        ctx.arc(cx, cy, 20, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = "#ffffff";
        ctx.font = '700 10px "JetBrains Mono", monospace';
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText(shortSym, cx, cy);
        ctx.restore();
      });
    };

    renderTsp();
    const ro = new ResizeObserver(renderTsp);
    if (canvas.parentElement) ro.observe(canvas.parentElement);
    return () => ro.disconnect();
  }, [tspData, showExactOverlay]);

  const fmtPrice = (p?: number) => {
    if (p === undefined || Number.isNaN(p)) return "—";
    if (p >= 100) return `$${p.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
    if (p >= 1) return `$${p.toFixed(3)}`;
    return `$${p.toFixed(5)}`;
  };

  const quickCoins = Array.from(new Set([selectedPair, "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT"])).slice(0, 7);
  const selectablePairs = availablePairs.length > 0 ? availablePairs.slice(0, 60) : TSP_CANDIDATE_ASSETS;
  const maxHistCount = mcData?.histogram
    ? Math.max(...mcData.histogram.map((b: any) => b.count), 1)
    : 1;

  return (
    <div className="flex-1 flex flex-col w-full">
      <div className="w-full px-4 sm:px-8 lg:px-10 pt-24 pb-12 flex flex-col gap-6">
        {/* SIMULATION STUDIO HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <div>
            <span className="text-[11px] font-mono uppercase tracking-widest text-sky-400 font-semibold block mb-1">
              Stochastic &amp; Approximation Simulation Lab
            </span>
            <h2 className="text-2xl md:text-3xl font-extrabold text-white tracking-tight flex items-center gap-3">
              <CoinLogo symbol={selectedPair} size={32} />
              {selectedPair} Simulation
              <span className="text-neutral-400 font-mono font-medium text-xs sm:text-sm px-2.5 py-1 rounded-lg bg-white/5 border border-white/10">
                Unit 6 Monte Carlo + Unit 5 Metric TSP
              </span>
            </h2>
          </div>

          {/* Quick Asset Pills */}
          <div className="flex flex-wrap items-center gap-1.5 bg-[#0a0c10] p-1.5 rounded-xl border border-white/10 font-mono text-xs">
            {quickCoins.map((pair) => {
              const active = selectedPair === pair;
              return (
                <button
                  key={pair}
                  type="button"
                  onClick={() => onSelectPair(pair)}
                  className={`px-2.5 py-1.5 rounded-lg font-bold transition flex items-center gap-1.5 ${
                    active ? "bg-sky-400 text-black" : "text-neutral-300 hover:bg-white/10 hover:text-white"
                  }`}
                >
                  <CoinLogo symbol={pair} size={16} active={active} />
                  {pair.replace("USDT", "")}
                </button>
              );
            })}
            <select
              value={selectedPair}
              onChange={(e) => onSelectPair(e.target.value)}
              className="bg-[#11141e] border border-white/15 rounded-lg px-2 py-1.5 text-xs font-mono text-white focus:outline-none focus:border-sky-400"
            >
              {selectablePairs.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* MONTE CARLO PARAMETER CONTROL BAR */}
        <div className="bg-[#07080a] border border-white/10 rounded-2xl p-4 flex flex-wrap items-center justify-between gap-4 font-mono text-xs">
          <div className="flex flex-wrap items-center gap-3">
            {/* Model Mode */}
            <div className="flex items-center gap-1 bg-[#11141e] p-1 rounded-lg border border-white/10">
              {(["GBM", "BOOTSTRAP"] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => setSimMode(m)}
                  className={`px-2.5 py-1 rounded font-bold transition ${
                    simMode === m ? "bg-sky-400 text-black" : "text-neutral-400 hover:text-white"
                  }`}
                >
                  {m}
                </button>
              ))}
            </div>

            {/* Interval */}
            <div className="flex items-center gap-1 bg-[#11141e] p-1 rounded-lg border border-white/10">
              {(["15m", "1h", "4h", "1d"] as const).map((tf) => (
                <button
                  key={tf}
                  type="button"
                  onClick={() => setSimInterval(tf)}
                  className={`px-2 py-1 rounded uppercase font-bold transition ${
                    simInterval === tf ? "bg-emerald-500 text-black" : "text-neutral-400 hover:text-white"
                  }`}
                >
                  {tf}
                </button>
              ))}
            </div>

            {/* Trials */}
            <div className="flex items-center gap-1 bg-[#11141e] p-1 rounded-lg border border-white/10">
              <span className="text-neutral-500 px-1.5">Paths:</span>
              {[500, 2000, 5000, 10000].map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => setTrials(m)}
                  className={`px-2 py-1 rounded font-bold transition ${
                    trials === m ? "bg-white text-black" : "text-neutral-400 hover:text-white"
                  }`}
                >
                  {m >= 1000 ? `${m / 1000}k` : m}
                </button>
              ))}
            </div>

            {/* Horizon */}
            <div className="flex items-center gap-1 bg-[#11141e] p-1 rounded-lg border border-white/10">
              <span className="text-neutral-500 px-1.5">Horizon:</span>
              {[15, 30, 50].map((h) => (
                <button
                  key={h}
                  type="button"
                  onClick={() => setHorizonSteps(h)}
                  className={`px-2 py-1 rounded font-bold transition ${
                    horizonSteps === h ? "bg-white text-black" : "text-neutral-400 hover:text-white"
                  }`}
                >
                  +{h}
                </button>
              ))}
            </div>

            {/* TP / SL Barriers */}
            <div className="flex items-center gap-2">
              <label className="flex items-center gap-1 text-emerald-400">
                <span>TP%</span>
                <input
                  type="number"
                  step="0.5"
                  min="0.2"
                  max="50"
                  value={tpPct}
                  onChange={(e) => setTpPct(parseFloat(e.target.value) || 2.0)}
                  className="w-14 bg-[#11141e] border border-white/15 rounded-lg px-2 py-1 text-white text-center"
                />
              </label>
              <label className="flex items-center gap-1 text-rose-400">
                <span>SL%</span>
                <input
                  type="number"
                  step="0.5"
                  min="0.2"
                  max="50"
                  value={slPct}
                  onChange={(e) => setSlPct(parseFloat(e.target.value) || 1.5)}
                  className="w-14 bg-[#11141e] border border-white/15 rounded-lg px-2 py-1 text-white text-center"
                />
              </label>
            </div>
          </div>

          <button
            type="button"
            onClick={runMonteCarlo}
            disabled={mcLoading}
            className="px-4 py-2 rounded-xl bg-sky-400 hover:bg-sky-300 text-black font-bold flex items-center gap-2 transition disabled:opacity-50"
          >
            <Play className="w-3.5 h-3.5" /> {mcLoading ? "Simulating..." : "Run Monte Carlo"}
          </button>
        </div>

        <MagicBento
          columns={4}
          rowHeight={240}
          gap={16}
          radius={16}
          theme="dark"
          glowColor="#38bdf8"
          enableBorderGlow={true}
          enableSpotlight={true}
          spotlightRadius={280}
          enableStars={false}
          enableTilt={false}
          enableMagnetism={false}
          clickEffect={false}
          intro={false}
          style={{
            ["--mb-surface" as any]: "#07080a",
            ["--mb-edge" as any]: "rgba(255, 255, 255, 0.12)",
            ["--mb-highlight" as any]: "rgba(255, 255, 255, 0.05)",
            ["--mb-shadow" as any]: "0 12px 32px -12px rgba(0, 0, 0, 0.85)",
          }}
          items={[
            {
              id: "sim-mc-fan",
              colSpan: 4,
              rowSpan: 2,
              content: (
                <div className="flex flex-col h-full justify-between">
                  <div className="flex justify-between items-start mb-2 pb-2 border-b border-white/10">
                    <div>
                      <span className="text-[10px] font-mono text-sky-400 uppercase tracking-widest block font-bold">
                        UNIT 6 FORMULATION • MONTE CARLO ALGORITHM
                      </span>
                      <h4 className="text-base font-bold text-white flex items-center gap-2">
                        <Activity className="w-4 h-4 text-sky-400" />
                        Stochastic Price Fan ({trials.toLocaleString()} {simMode} Paths)
                      </h4>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-white/5 text-neutral-300 border border-white/10">
                        Java :8085
                      </span>
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-sky-500/10 text-sky-400 border border-sky-500/20 font-bold">
                        O(M·T)
                      </span>
                    </div>
                  </div>

                  <div className="relative w-full flex-1 min-h-[290px] border border-white/10 rounded-xl bg-[#06080d] overflow-hidden">
                    {mcError ? (
                      <div className="h-full flex items-center justify-center text-xs font-mono text-rose-400 px-4 text-center">
                        {mcError}
                      </div>
                    ) : (
                      <canvas ref={mcCanvasRef} className="w-full h-full block" />
                    )}
                  </div>

                  <div className="mt-3 pt-2 border-t border-white/10 flex flex-wrap justify-between items-center gap-2 text-[11px] font-mono">
                    <div className="flex items-center gap-3 text-neutral-400">
                      <span className="flex items-center gap-1">
                        <span className="w-2.5 h-0.5 bg-white inline-block" /> Median P50
                      </span>
                      <span className="flex items-center gap-1">
                        <span className="w-2.5 h-2 bg-emerald-500/30 border border-emerald-400/50 inline-block" /> P25–P75
                      </span>
                      <span className="flex items-center gap-1">
                        <span className="w-2.5 h-2 bg-sky-400/20 border border-sky-400/60 inline-block" /> P5–P95
                      </span>
                    </div>
                    <span className="text-sky-400 font-bold">
                      Spot: {fmtPrice(mcData?.spotPrice)} → P50: {fmtPrice(mcData?.terminalMedianPrice)}
                    </span>
                  </div>
                </div>
              ),
            },
            {
              id: "sim-mc-risk",
              colSpan: 2,
              rowSpan: 2,
              content: (
                <div className="flex flex-col h-full justify-between gap-2.5">
                  <div className="flex justify-between items-start pb-2 border-b border-white/10">
                    <div>
                      <span className="text-[10px] font-mono text-emerald-400 uppercase tracking-widest block font-bold">
                        RISK CONVERGENCE &amp; TAIL QUANTILES
                      </span>
                      <h4 className="text-base font-bold text-white">Terminal Distribution, VaR 95% &amp; CVaR</h4>
                    </div>
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-bold">
                      {mcData ? `${(mcData.executionTimeNs / 1e6).toFixed(2)} ms` : "—"}
                    </span>
                  </div>

                  {/* 4 Risk & Probability Cards */}
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 font-mono text-xs">
                    <div className="p-2.5 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 uppercase block">95% VaR / CVaR</span>
                      <span className="text-sm font-extrabold text-rose-400 block">
                        {mcData ? `-${mcData.var95Pct.toFixed(2)}%` : "—"}
                      </span>
                      <span className="text-[10px] text-neutral-400">
                        CVaR: {mcData ? `-${mcData.cvar95Pct.toFixed(2)}%` : "—"}
                      </span>
                    </div>
                    <div className="p-2.5 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 uppercase block">P(Profit)</span>
                      <span className="text-sm font-extrabold text-emerald-400 block">
                        {mcData ? `${mcData.probProfitPct.toFixed(1)}%` : "—"}
                      </span>
                      <span className="text-[10px] text-neutral-400">Final &gt; Spot</span>
                    </div>
                    <div className="p-2.5 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 uppercase block">First-Passage</span>
                      <span className="text-sm font-extrabold text-sky-400 block">
                        {mcData ? `${mcData.probTakeProfitPct.toFixed(1)}%` : "—"}
                      </span>
                      <span className="text-[10px] text-rose-400">
                        SL: {mcData ? `${mcData.probStopLossPct.toFixed(1)}%` : "—"}
                      </span>
                    </div>
                    <div className="p-2.5 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 uppercase block">MC Error vs E[S]</span>
                      <span className="text-sm font-extrabold text-amber-400 block">
                        {mcData ? `${mcData.meanConvergenceErrorPct.toFixed(3)}%` : "—"}
                      </span>
                      <span className="text-[10px] text-neutral-400">
                        E[S]: {fmtPrice(mcData?.theoreticalMeanPrice)}
                      </span>
                    </div>
                  </div>

                  {/* 16-Bin Terminal Price Histogram */}
                  <div className="flex-1 flex flex-col justify-between bg-[#0a0d14] border border-white/10 rounded-xl p-3 font-mono">
                    <div className="flex justify-between text-[10px] text-neutral-400 mb-2">
                      <span>Terminal Price Density ({trials.toLocaleString()} trials)</span>
                      <span>
                        Mean: {fmtPrice(mcData?.terminalMeanPrice)} • Vol/Bar:{" "}
                        {mcData ? `${mcData.perBarVolatilityPct.toFixed(3)}%` : "—"}
                      </span>
                    </div>

                    <div className="flex-1 flex items-end gap-1 min-h-[120px] pt-2">
                      {mcData?.histogram ? (
                        mcData.histogram.map((b: any, idx: number) => {
                          const mid = (b.rangeStart + b.rangeEnd) / 2;
                          const aboveSpot = mid >= (mcData.spotPrice || 0);
                          const heightPct = Math.max(4, (b.count / maxHistCount) * 100);
                          return (
                            <div
                              key={idx}
                              title={`${fmtPrice(b.rangeStart)} – ${fmtPrice(b.rangeEnd)}: ${b.count} paths`}
                              className="flex-1 h-full flex flex-col justify-end group"
                            >
                              <div
                                style={{ height: `${heightPct}%` }}
                                className={`w-full rounded-t transition-all ${
                                  aboveSpot
                                    ? "bg-emerald-500/75 group-hover:bg-emerald-400"
                                    : "bg-rose-500/75 group-hover:bg-rose-400"
                                }`}
                              />
                            </div>
                          );
                        })
                      ) : (
                        <div className="w-full h-full flex items-center justify-center text-xs text-neutral-500">
                          Running Monte Carlo simulation on Java :8085...
                        </div>
                      )}
                    </div>

                    <div className="flex justify-between text-[10px] text-neutral-500 mt-2 pt-1.5 border-t border-white/10">
                      <span>{mcData?.histogram?.[0] ? fmtPrice(mcData.histogram[0].rangeStart) : "—"}</span>
                      <span className="text-white">Spot: {fmtPrice(mcData?.spotPrice)}</span>
                      <span>
                        {mcData?.histogram?.length
                          ? fmtPrice(mcData.histogram[mcData.histogram.length - 1].rangeEnd)
                          : "—"}
                      </span>
                    </div>
                  </div>
                </div>
              ),
            },
            {
              id: "sim-tsp-graph",
              colSpan: 2,
              rowSpan: 2,
              content: (
                <div className="flex flex-col h-full justify-between gap-2">
                  <div className="flex justify-between items-start pb-2 border-b border-white/10">
                    <div>
                      <span className="text-[10px] font-mono text-amber-400 uppercase tracking-widest block font-bold">
                        UNIT 5 FORMULATION • APPROXIMATION ALGORITHM
                      </span>
                      <h4 className="text-base font-bold text-white flex items-center gap-2">
                        <GitBranch className="w-4 h-4 text-amber-400" />
                        Metric TSP 2-Approximation (Correlation Distance)
                      </h4>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-white/5 text-neutral-300 border border-white/10">
                        Java :8085
                      </span>
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20 font-bold">
                        ≤ 2·OPT
                      </span>
                    </div>
                  </div>

                  {/* Asset Subset Selector & Refresh */}
                  <div className="flex flex-wrap items-center justify-between gap-2 font-mono text-[10px]">
                    <div className="flex flex-wrap gap-1">
                      {TSP_CANDIDATE_ASSETS.map((sym) => {
                        const active = tspSymbols.includes(sym);
                        return (
                          <button
                            key={sym}
                            type="button"
                            onClick={() => toggleTspAsset(sym)}
                            className={`px-2 py-1 rounded-lg border flex items-center gap-1 transition font-bold ${
                              active
                                ? "bg-emerald-500/15 border-emerald-500/40 text-emerald-300"
                                : "bg-[#0e1118] border-white/10 text-neutral-500 hover:text-white"
                            }`}
                          >
                            <CoinLogo symbol={sym} size={13} />
                            {sym.replace("USDT", "")}
                          </button>
                        );
                      })}
                    </div>

                    <div className="flex items-center gap-1.5">
                      <div className="flex items-center gap-0.5 bg-[#11141e] p-0.5 rounded-lg border border-white/10">
                        {(["15m", "1h", "4h", "1d"] as const).map((tf) => (
                          <button
                            key={tf}
                            type="button"
                            onClick={() => setTspInterval(tf)}
                            className={`px-1.5 py-0.5 rounded uppercase font-bold transition ${
                              tspInterval === tf ? "bg-amber-400 text-black" : "text-neutral-400 hover:text-white"
                            }`}
                          >
                            {tf}
                          </button>
                        ))}
                      </div>
                      <button
                        type="button"
                        onClick={() => setShowExactOverlay(!showExactOverlay)}
                        className={`px-2 py-1 rounded-lg border font-bold transition ${
                          showExactOverlay
                            ? "bg-sky-400/15 border-sky-400/50 text-sky-300"
                            : "bg-white/5 border-white/10 text-neutral-400"
                        }`}
                      >
                        OPT Overlay
                      </button>
                      <button
                        type="button"
                        onClick={() => runMetricTsp()}
                        disabled={tspLoading}
                        className="px-2.5 py-1 rounded-lg bg-white text-black font-bold flex items-center gap-1 hover:bg-neutral-200 transition disabled:opacity-50"
                      >
                        <RefreshCw className={`w-3 h-3 ${tspLoading ? "animate-spin" : ""}`} />
                        Solve
                      </button>
                    </div>
                  </div>

                  <div className="relative w-full flex-1 min-h-[245px] border border-white/10 rounded-xl bg-[#06080d] overflow-hidden">
                    {tspError ? (
                      <div className="h-full flex items-center justify-center text-xs font-mono text-rose-400 px-4 text-center">
                        {tspError}
                      </div>
                    ) : (
                      <canvas ref={tspCanvasRef} className="w-full h-full block" />
                    )}
                  </div>

                  <div className="pt-1 border-t border-white/10 flex flex-wrap justify-between items-center gap-2 text-[10px] font-mono">
                    <div className="flex items-center gap-3 text-neutral-400">
                      <span className="flex items-center gap-1">
                        <span className="w-3 h-0.5 bg-amber-400 inline-block" /> Prim MST
                      </span>
                      <span className="flex items-center gap-1">
                        <span className="w-3 h-0.5 bg-emerald-400 inline-block" /> 2-Approx Tour
                      </span>
                      <span className="flex items-center gap-1">
                        <span className="w-3 h-0.5 bg-sky-400 inline-block" /> Exact Held-Karp
                      </span>
                    </div>
                    <span className="text-emerald-400 font-bold">
                      {tspData
                        ? `Cost: ${tspData.approxCost.toFixed(3)} • OPT: ${
                            tspData.exactOptimalCost > 0 ? tspData.exactOptimalCost.toFixed(3) : "—"
                          } • Ratio: ${tspData.approxRatioVsOpt.toFixed(2)}× (≤2×)`
                        : "Metric Axiom: CHECKING"}
                    </span>
                  </div>
                </div>
              ),
            },
          ]}
        />
      </div>

      {/* FULL SCREEN WIDTH FOOTER */}
      <footer className="w-full bg-black py-20 md:py-28 px-6 border-t border-white/10 flex flex-col items-center justify-center relative overflow-hidden select-none">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/[0.03] via-transparent to-transparent pointer-events-none" />
        <div className="w-full flex items-center justify-center overflow-hidden py-4">
          <span className="font-black uppercase tracking-tight text-center leading-none text-transparent bg-clip-text bg-gradient-to-b from-transparent via-neutral-500/20 to-neutral-200/60 text-[11vw] max-w-full select-none inline-block">
            MarketCents
          </span>
        </div>
      </footer>
    </div>
  );
}
