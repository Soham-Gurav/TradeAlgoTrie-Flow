"use client";

import React, { useState, useEffect, useCallback } from "react";
import {
  Newspaper,
  RefreshCw,
  Search,
  Sparkles,
  ExternalLink,
  ShieldAlert,
  TrendingUp,
  TrendingDown,
  Zap,
  CheckCircle2,
  XCircle,
  GitFork,
  ArrowUpRight,
} from "lucide-react";
import MagicBento from "./MagicBento";
import CoinLogo from "./CoinLogo";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8085";

export const WATCHLIST_NEWS_COINS = [
  "BTC",
  "ETH",
  "SOL",
  "BNB",
  "XRP",
  "DOGE",
  "SUI",
  "NEAR",
  "PEPE",
  "AVAX",
  "TRX",
  "LINK",
  "ADA",
  "SHIB",
];

type PriorityMode = "BALANCED" | "BREAKING" | "CATALYST" | "RISK";

interface KeywordMatch {
  keyword: string;
  tier: number;
  category: string;
  weight: number;
  matchedInTitle: boolean;
}

interface NewsArticle {
  rank: number;
  id: string;
  title: string;
  summary: string;
  link: string;
  sourceName: string;
  imageUrl: string;
  publishedEpochSec: number;
  sentimentHint: string;
  coinScore: number;
  keywordScore: number;
  recencyScore: number;
  sourceScore: number;
  customBoostScore: number;
  priorityScore: number;
  matchedCoins: string[];
  keywordMatches: KeywordMatch[];
}

interface HeapNodeView {
  heapIndex: number;
  newsId: string;
  title: string;
  primaryCoin: string;
  priorityScore: number;
  parentIndex: number;
  leftIndex: number;
  rightIndex: number;
}

interface KeywordFrequency {
  keyword: string;
  tier: number;
  category: string;
  count: number;
  weight: number;
}

interface FeedFetchStatus {
  sourceName: string;
  url: string;
  status: string;
  itemCount: number;
  latencyMs: number;
}

interface NewsFeedResponse {
  focusCoin: string;
  mode: PriorityMode;
  modeLabel: string;
  topK: number;
  rawFetchedCount: number;
  filteredOutCount: number;
  cryptoRelevantCount: number;
  heapSize: number;
  heapHeight: number;
  liveHeapExtractTimeNs: number;
  totalPipelineMs: number;
  generatedEpochSec: number;
  coinCounts: Record<string, number>;
  keywordFrequencies: KeywordFrequency[];
  heapNodes: HeapNodeView[];
  benchmark: {
    itemCount: number;
    topK: number;
    heapTimeNs: number;
    sortTimeNs: number;
    speedupFactor: number;
  };
  feedStatuses: FeedFetchStatus[];
  items: NewsArticle[];
}

interface KeywordCheckResponse {
  text: string;
  cryptoRelevant: boolean;
  sentimentHint: string;
  coinScore: number;
  keywordScore: number;
  customBoostScore: number;
  estimatedPriorityScore: number;
  matchedCoins: string[];
  keywordMatches: KeywordMatch[];
}

interface NewsTerminalProps {
  selectedPair: string;
  onSelectPair: (pair: string) => void;
  onNavigateToMarket: (pair: string) => void;
  availablePairs: string[];
}

const MODE_OPTIONS: { id: PriorityMode; label: string; hint: string }[] = [
  { id: "BALANCED", label: "Balanced", hint: "Coin + Keywords + Recency" },
  { id: "BREAKING", label: "Breaking", hint: "Recency Velocity First" },
  { id: "CATALYST", label: "Catalysts", hint: "ETF / SEC / Mainnet" },
  { id: "RISK", label: "Risk Alerts", hint: "Exploits / Liquidations" },
];

export default function NewsTerminal({
  selectedPair,
  onSelectPair,
  onNavigateToMarket,
  availablePairs,
}: NewsTerminalProps) {
  const [focusCoin, setFocusCoin] = useState<string>("ALL");
  const [mode, setMode] = useState<PriorityMode>("BALANCED");
  const [topK, setTopK] = useState<number>(20);
  const [customKeywordsInput, setCustomKeywordsInput] = useState<string>("");
  const [appliedCustomKeywords, setAppliedCustomKeywords] = useState<string>("");
  const [includeGoogleNews, setIncludeGoogleNews] = useState<boolean>(true);

  const [feedData, setFeedData] = useState<NewsFeedResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string>("");
  const [brokenImages, setBrokenImages] = useState<Record<string, boolean>>({});

  // Interactive Keyword Checker State
  const [probeText, setProbeText] = useState<string>(
    "SEC reviews spot Solana ETF filing as SOL whale inflow surges"
  );
  const [probeResult, setProbeResult] = useState<KeywordCheckResponse | null>(null);
  const [probeLoading, setProbeLoading] = useState<boolean>(false);

  // Scaled Benchmark State
  const [benchmarkOverride, setBenchmarkOverride] = useState<NewsFeedResponse["benchmark"] | null>(
    null
  );
  const [benchmarkLoading, setBenchmarkLoading] = useState<boolean>(false);

  const fetchNewsFeed = useCallback(
    async (forceRefresh = false) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams({
          coin: focusCoin,
          coins: WATCHLIST_NEWS_COINS.join(","),
          mode,
          topK: String(topK),
          includeGoogleNews: String(includeGoogleNews),
          refresh: String(forceRefresh),
          customKeywords: appliedCustomKeywords,
        });
        const res = await fetch(`${BACKEND_URL}/api/news/feed?${params.toString()}`);
        const data = await res.json();
        if (!res.ok || data.error) {
          setError(data.error || "Failed to fetch prioritized crypto RSS feed");
        } else {
          setFeedData(data);
          setBenchmarkOverride(data.benchmark || null);
          setStatusMessage(
            `Prioritized ${data.items?.length ?? 0} crypto news items for ${
              focusCoin === "ALL" ? "all watchlist coins" : focusCoin
            } using ${data.modeLabel} mode`
          );
        }
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : "Could not reach Java backend :8085";
        setError(msg);
      } finally {
        setLoading(false);
      }
    },
    [focusCoin, mode, topK, includeGoogleNews, appliedCustomKeywords]
  );

  useEffect(() => {
    fetchNewsFeed(false);
  }, [fetchNewsFeed]);

  const runKeywordProbe = useCallback(
    async (textToCheck: string) => {
      if (!textToCheck.trim()) return;
      setProbeLoading(true);
      try {
        const params = new URLSearchParams({
          text: textToCheck,
          coin: focusCoin,
          mode,
          customKeywords: appliedCustomKeywords,
        });
        const res = await fetch(`${BACKEND_URL}/api/news/keywords/check?${params.toString()}`);
        if (res.ok) {
          const data: KeywordCheckResponse = await res.json();
          setProbeResult(data);
        }
      } catch {
        // Ignore transient probe errors when backend is starting
      } finally {
        setProbeLoading(false);
      }
    },
    [focusCoin, mode, appliedCustomKeywords]
  );

  useEffect(() => {
    runKeywordProbe(probeText);
  }, [focusCoin, mode, appliedCustomKeywords]);

  const runScaledBenchmark = async () => {
    setBenchmarkLoading(true);
    try {
      const res = await fetch(
        `${BACKEND_URL}/api/news/benchmark?size=10000&topK=${Math.min(topK, 20)}`
      );
      if (res.ok) {
        const bm = await res.json();
        setBenchmarkOverride(bm);
      }
    } finally {
      setBenchmarkLoading(false);
    }
  };

  const toggleCustomBoostKeyword = (kw: string) => {
    const existing = appliedCustomKeywords
      .split(",")
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean);
    const target = kw.toLowerCase();
    const next = existing.includes(target)
      ? existing.filter((x) => x !== target)
      : [...existing, target];
    const joined = next.join(", ");
    setCustomKeywordsInput(joined);
    setAppliedCustomKeywords(joined);
  };

  const handleSelectWatchlistCoin = (sym: string) => {
    setFocusCoin(sym);
    if (sym !== "ALL") {
      onSelectPair(`${sym}USDT`);
    }
  };

  const formatRelativeTime = (epochSec: number, nowSec?: number) => {
    if (!epochSec) return "Recent";
    const ref = nowSec || Math.floor(Date.now() / 1000);
    const diff = Math.max(0, ref - epochSec);
    if (diff < 60) return `${diff}s ago`;
    if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
    if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
    return `${Math.floor(diff / 86400)}d ago`;
  };

  const sentimentBadgeStyle = (sentiment: string) => {
    switch (sentiment) {
      case "BULLISH":
        return "bg-emerald-500/15 text-emerald-400 border-emerald-500/30";
      case "BEARISH":
        return "bg-rose-500/15 text-rose-400 border-rose-500/30";
      case "RISK_ALERT":
        return "bg-amber-500/15 text-amber-300 border-amber-500/40";
      case "MACRO_REGULATORY":
        return "bg-sky-500/15 text-sky-400 border-sky-500/30";
      default:
        return "bg-white/5 text-neutral-300 border-white/15";
    }
  };

  const tierPillStyle = (tier: number, category: string) => {
    if (category === "CUSTOM_BOOST") return "bg-fuchsia-500/20 text-fuchsia-300 border-fuchsia-400/40";
    if (tier === 1) return "bg-amber-500/15 text-amber-300 border-amber-500/30";
    if (tier === 2) return "bg-sky-500/15 text-sky-300 border-sky-500/30";
    return "bg-white/5 text-neutral-300 border-white/10";
  };

  const activeBenchmark = benchmarkOverride || feedData?.benchmark;
  const heapNodes = feedData?.heapNodes || [];
  const activeBoostSet = new Set(
    appliedCustomKeywords
      .split(",")
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean)
  );
  const selectableAllCoins = Array.from(
    new Set([
      ...WATCHLIST_NEWS_COINS,
      selectedPair.replace(/USDT$/i, "").toUpperCase(),
      ...availablePairs.slice(0, 40).map((p) => p.replace(/USDT$/i, "").toUpperCase()),
    ])
  ).filter(Boolean);

  return (
    <div className="flex-1 flex flex-col w-full">
      <div className="w-full px-4 sm:px-8 lg:px-10 pt-24 pb-12 flex flex-col gap-6">
        {/* Visually hidden status region for screen reader announcements on reorder/filter */}
        <p role="status" className="sr-only">
          {statusMessage}
        </p>

        {/* NEWS HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <div>
            <span className="text-[11px] font-mono uppercase tracking-widest text-amber-400 font-semibold block mb-1">
              Unit 2 Priority Queue • Live RSS Intelligence
            </span>
            <h2 className="text-2xl md:text-3xl font-extrabold text-white tracking-tight flex items-center gap-3 flex-wrap">
              {focusCoin !== "ALL" ? (
                <CoinLogo symbol={focusCoin} size={32} active />
              ) : (
                <Newspaper className="w-7 h-7 text-amber-400 shrink-0" />
              )}
              <span>
                {focusCoin === "ALL" ? "Watchlist Crypto News" : `${focusCoin} Market Intelligence`}
              </span>
              <span className="text-neutral-400 font-mono font-medium text-xs sm:text-sm px-2.5 py-1 rounded-lg bg-white/5 border border-white/10">
                Binary Max-Heap Top-{topK}
              </span>
            </h2>
          </div>

          {/* Quick Jump to Active Pair in Market */}
          <div className="flex items-center gap-2 self-start lg:self-auto font-mono text-xs">
            <select
              aria-label="Filter news by coin ticker"
              value={focusCoin}
              onChange={(e) => handleSelectWatchlistCoin(e.target.value)}
              className="bg-[#0e1118] border border-white/15 rounded-xl px-3 py-2 text-xs font-mono text-white focus:outline-none focus:border-amber-400"
            >
              <option value="ALL">ALL 14 WATCHLIST COINS</option>
              {selectableAllCoins.map((sym) => (
                <option key={sym} value={sym}>
                  {sym} ({feedData?.coinCounts?.[sym] ?? 0} hits)
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() =>
                onNavigateToMarket(focusCoin === "ALL" ? selectedPair : `${focusCoin}USDT`)
              }
              className="px-3.5 py-2 rounded-xl bg-white/10 hover:bg-white/15 border border-white/15 text-white font-bold transition flex items-center gap-1.5 whitespace-nowrap"
            >
              <span>Open {focusCoin === "ALL" ? selectedPair.replace("USDT", "") : focusCoin} Chart</span>
              <ArrowUpRight className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* WATCHLIST COIN PILL STRIP (14 Core Coins) */}
        <div className="flex flex-wrap items-center gap-1.5 bg-[#0a0c10] p-2 rounded-2xl border border-white/10 font-mono text-xs">
          <button
            type="button"
            aria-pressed={focusCoin === "ALL"}
            onClick={() => handleSelectWatchlistCoin("ALL")}
            className={`px-3 py-1.5 rounded-xl font-bold transition flex items-center gap-1.5 ${
              focusCoin === "ALL"
                ? "bg-amber-400 text-black"
                : "text-neutral-300 hover:bg-white/10 hover:text-white"
            }`}
          >
            <span>ALL</span>
            <span className="text-[10px] opacity-80">
              ({feedData?.cryptoRelevantCount ?? "—"})
            </span>
          </button>

          {WATCHLIST_NEWS_COINS.map((sym) => {
            const active = focusCoin === sym;
            const count = feedData?.coinCounts?.[sym] ?? 0;
            return (
              <button
                key={sym}
                type="button"
                aria-pressed={active}
                onClick={() => handleSelectWatchlistCoin(sym)}
                className={`px-2.5 py-1.5 rounded-xl font-bold transition flex items-center gap-1.5 border ${
                  active
                    ? "bg-emerald-500 text-black border-emerald-400"
                    : "bg-[#0e1118] text-neutral-300 border-white/10 hover:border-white/25 hover:text-white"
                }`}
              >
                <CoinLogo symbol={sym} size={15} active={active} />
                <span>{sym}</span>
                <span
                  className={`text-[10px] ${
                    active ? "text-black/80" : count > 0 ? "text-emerald-400" : "text-neutral-500"
                  }`}
                >
                  {count}
                </span>
              </button>
            );
          })}
        </div>

        {/* PRIORITY QUEUE PARAMETER CONTROL BAR */}
        <div className="bg-[#07080a] border border-white/10 rounded-2xl p-4 flex flex-wrap items-center justify-between gap-3 font-mono text-xs">
          <div className="flex flex-wrap items-center gap-2.5">
            {/* Priority Mode */}
            <div className="flex items-center gap-1 bg-[#11141e] p-1 rounded-xl border border-white/10">
              {MODE_OPTIONS.map((m) => (
                <button
                  key={m.id}
                  type="button"
                  title={m.hint}
                  aria-pressed={mode === m.id}
                  onClick={() => setMode(m.id)}
                  className={`px-2.5 py-1 rounded-lg font-bold transition ${
                    mode === m.id
                      ? "bg-amber-400 text-black"
                      : "text-neutral-400 hover:text-white"
                  }`}
                >
                  {m.label}
                </button>
              ))}
            </div>

            {/* Top-K Selector */}
            <div className="flex items-center gap-1 bg-[#11141e] p-1 rounded-xl border border-white/10">
              <span className="text-neutral-500 px-1.5">Top-K:</span>
              {[10, 20, 30, 50].map((k) => (
                <button
                  key={k}
                  type="button"
                  aria-pressed={topK === k}
                  onClick={() => setTopK(k)}
                  className={`px-2 py-1 rounded-lg font-bold transition ${
                    topK === k ? "bg-white text-black" : "text-neutral-400 hover:text-white"
                  }`}
                >
                  {k}
                </button>
              ))}
            </div>

            {/* Google News RSS Toggle */}
            <button
              type="button"
              aria-pressed={includeGoogleNews}
              onClick={() => setIncludeGoogleNews(!includeGoogleNews)}
              className={`px-3 py-1.5 rounded-xl border font-bold transition ${
                includeGoogleNews
                  ? "bg-sky-400/15 border-sky-400/40 text-sky-300"
                  : "bg-[#11141e] border-white/10 text-neutral-400 hover:text-white"
              }`}
            >
              + Altcoin Google RSS: {includeGoogleNews ? "ON" : "OFF"}
            </button>
          </div>

          {/* Custom Priority Boost Keywords + Refresh */}
          <div className="flex flex-wrap items-center gap-2 w-full lg:w-auto">
            <div className="relative flex-1 lg:w-64">
              <Sparkles className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-fuchsia-400 pointer-events-none" />
              <input
                type="text"
                aria-label="Custom boost keywords"
                value={customKeywordsInput}
                onChange={(e) => setCustomKeywordsInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") setAppliedCustomKeywords(customKeywordsInput);
                }}
                placeholder="Boost keywords (etf, hack, mainnet)..."
                className="w-full bg-[#11141e] border border-white/15 rounded-xl pl-8 pr-3 py-1.5 text-xs text-white placeholder-neutral-500 focus:outline-none focus:border-fuchsia-400"
              />
            </div>
            <button
              type="button"
              onClick={() => setAppliedCustomKeywords(customKeywordsInput)}
              className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 border border-white/15 text-white font-bold transition"
            >
              Boost
            </button>
            <button
              type="button"
              onClick={() => fetchNewsFeed(true)}
              disabled={loading}
              className="px-3.5 py-1.5 rounded-xl bg-amber-400 hover:bg-amber-300 text-black font-bold flex items-center gap-1.5 transition disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin" : ""}`} />
              <span>{loading ? "Ranking..." : "Refresh RSS"}</span>
            </button>
          </div>
        </div>

        {/* UNIT 2 ALGORITHM TELEMETRY BENTO GRID */}
        <MagicBento
          columns={4}
          rowHeight={240}
          gap={16}
          radius={16}
          theme="dark"
          glowColor="#f59e0b"
          enableBorderGlow={true}
          enableSpotlight={true}
          spotlightRadius={280}
          enableStars={false}
          enableTilt={false}
          enableMagnetism={false}
          clickEffect={false}
          intro={false}
          style={{
            ["--mb-surface" as string]: "#07080a",
            ["--mb-edge" as string]: "rgba(255, 255, 255, 0.12)",
            ["--mb-highlight" as string]: "rgba(255, 255, 255, 0.05)",
            ["--mb-shadow" as string]: "0 12px 32px -12px rgba(0, 0, 0, 0.85)",
          }}
          items={[
            {
              id: "news-heap-tree",
              colSpan: 2,
              rowSpan: 2,
              content: (
                <div className="flex flex-col h-full justify-between gap-2.5">
                  <div className="flex justify-between items-start pb-2 border-b border-white/10">
                    <div>
                      <span className="text-[10px] font-mono text-amber-400 uppercase tracking-widest block font-bold">
                        UNIT 2 FORMULATION • BINARY MAX-HEAP
                      </span>
                      <h3 className="text-base font-bold text-white flex items-center gap-2">
                        <GitFork className="w-4 h-4 text-amber-400" />
                        Heap Structure &amp; O(N + K log N) Benchmark
                      </h3>
                    </div>
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/10 text-amber-300 border border-amber-500/20 font-bold">
                      Floyd Heapify O(N)
                    </span>
                  </div>

                  {/* Live Heap Metrics */}
                  <div className="grid grid-cols-4 gap-2 font-mono text-xs">
                    <div className="p-2 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 block uppercase">Heap N</span>
                      <span className="text-sm font-extrabold text-white">
                        {feedData?.heapSize ?? "—"}
                      </span>
                    </div>
                    <div className="p-2 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 block uppercase">Height</span>
                      <span className="text-sm font-extrabold text-sky-400">
                        {feedData?.heapHeight ?? "—"}
                      </span>
                    </div>
                    <div className="p-2 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 block uppercase">Root Max</span>
                      <span className="text-sm font-extrabold text-emerald-400">
                        {heapNodes[0] ? heapNodes[0].priorityScore.toFixed(1) : "—"}
                      </span>
                    </div>
                    <div className="p-2 rounded-xl bg-[#0a0d14] border border-white/10">
                      <span className="text-[10px] text-neutral-500 block uppercase">Extract</span>
                      <span className="text-sm font-extrabold text-amber-300">
                        {feedData ? `${(feedData.liveHeapExtractTimeNs / 1000).toFixed(0)} µs` : "—"}
                      </span>
                    </div>
                  </div>

                  {/* Binary Max-Heap Level 0..2 Visual Tree (Top 7 Array Slots) */}
                  <div className="flex-1 bg-[#06080d] border border-white/10 rounded-xl p-2.5 flex flex-col justify-between overflow-hidden">
                    <div className="flex justify-between items-center text-[10px] font-mono text-neutral-400 mb-1">
                      <span>Max-Heap Array Snapshot (Parent ≥ Children)</span>
                      <span className="text-neutral-500">H[0]..H[6]</span>
                    </div>

                    {heapNodes.length > 0 ? (
                      <div className="flex flex-col gap-2 my-auto font-mono">
                        {/* Level 0: Root H[0] */}
                        <div className="flex justify-center">
                          <div className="px-3 py-1 rounded-lg bg-amber-500/15 border border-amber-400/50 text-center max-w-[210px]">
                            <div className="text-[10px] font-bold text-amber-300">
                              H[0] {heapNodes[0].primaryCoin} • {heapNodes[0].priorityScore.toFixed(1)}
                            </div>
                            <div className="text-[9px] text-neutral-300 truncate">
                              {heapNodes[0].title}
                            </div>
                          </div>
                        </div>

                        {/* Level 1: H[1], H[2] */}
                        {heapNodes.length > 1 && (
                          <div className="grid grid-cols-2 gap-2">
                            {[1, 2].map((idx) => {
                              const nd = heapNodes[idx];
                              if (!nd) return <div key={idx} className="rounded-lg border border-dashed border-white/5 p-1" />;
                              return (
                                <div
                                  key={idx}
                                  className="px-2 py-1 rounded-lg bg-emerald-500/10 border border-emerald-500/30 text-center min-w-0"
                                >
                                  <div className="text-[10px] font-bold text-emerald-300">
                                    H[{idx}] {nd.primaryCoin} • {nd.priorityScore.toFixed(1)}
                                  </div>
                                  <div className="text-[9px] text-neutral-400 truncate">
                                    {nd.title}
                                  </div>
                                </div>
                              );
                            })}
                          </div>
                        )}

                        {/* Level 2: H[3]..H[6] */}
                        {heapNodes.length > 3 && (
                          <div className="grid grid-cols-4 gap-1.5">
                            {[3, 4, 5, 6].map((idx) => {
                              const nd = heapNodes[idx];
                              if (!nd) return <div key={idx} className="rounded-md border border-dashed border-white/5 p-1" />;
                              return (
                                <div
                                  key={idx}
                                  title={nd.title}
                                  className="px-1.5 py-1 rounded-md bg-[#0e131e] border border-white/10 text-center min-w-0"
                                >
                                  <div className="text-[9px] font-bold text-sky-300 truncate">
                                    H[{idx}] {nd.primaryCoin}
                                  </div>
                                  <div className="text-[9px] text-neutral-400">
                                    {nd.priorityScore.toFixed(1)}
                                  </div>
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    ) : (
                      <div className="flex-1 flex items-center justify-center text-xs font-mono text-neutral-500">
                        {loading ? "Building Binary Max-Heap on Java :8085..." : "No items in heap"}
                      </div>
                    )}
                  </div>

                  {/* Empirical Top-K Heap vs Full Sort Baseline Comparison */}
                  <div className="pt-2 border-t border-white/10 font-mono text-[10px] space-y-1.5">
                    <div className="flex justify-between items-center">
                      <span className="text-neutral-400">
                        Scaled Benchmark (N={activeBenchmark?.itemCount?.toLocaleString() || "5,000"}, Top-{activeBenchmark?.topK || 20})
                      </span>
                      <div className="flex items-center gap-2">
                        <span className="text-emerald-400 font-bold">
                          {activeBenchmark ? `${activeBenchmark.speedupFactor.toFixed(2)}× faster` : "—"}
                        </span>
                        <button
                          type="button"
                          onClick={runScaledBenchmark}
                          disabled={benchmarkLoading}
                          className="px-2 py-0.5 rounded bg-white text-black font-bold hover:bg-neutral-200 transition disabled:opacity-50"
                        >
                          {benchmarkLoading ? "..." : "Bench 10k"}
                        </button>
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <div className="flex justify-between mb-0.5">
                          <span className="text-emerald-400">Max-Heap Top-K</span>
                          <span className="text-white">
                            {activeBenchmark ? `${(activeBenchmark.heapTimeNs / 1e6).toFixed(2)} ms` : "—"}
                          </span>
                        </div>
                        <div className="h-1.5 rounded-full bg-white/10 overflow-hidden">
                          <div
                            className="h-full bg-emerald-400 rounded-full"
                            style={{
                              width:
                                activeBenchmark && activeBenchmark.sortTimeNs > 0
                                  ? `${Math.max(10, Math.min(100, (activeBenchmark.heapTimeNs / activeBenchmark.sortTimeNs) * 100))}%`
                                  : "25%",
                            }}
                          />
                        </div>
                      </div>
                      <div>
                        <div className="flex justify-between mb-0.5">
                          <span className="text-rose-400">Arrays.sort O(N log N)</span>
                          <span className="text-white">
                            {activeBenchmark ? `${(activeBenchmark.sortTimeNs / 1e6).toFixed(2)} ms` : "—"}
                          </span>
                        </div>
                        <div className="h-1.5 rounded-full bg-white/10 overflow-hidden">
                          <div className="h-full bg-rose-500/80 rounded-full w-full" />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              ),
            },
            {
              id: "news-keyword-radar",
              colSpan: 2,
              rowSpan: 2,
              content: (
                <div className="flex flex-col h-full justify-between gap-2.5">
                  <div className="flex justify-between items-start pb-2 border-b border-white/10">
                    <div>
                      <span className="text-[10px] font-mono text-sky-400 uppercase tracking-widest block font-bold">
                        CRYPTO KEYWORD CLASSIFIER &amp; RELEVANCE GATE
                      </span>
                      <h3 className="text-base font-bold text-white flex items-center gap-2">
                        <Search className="w-4 h-4 text-sky-400" />
                        Detected Keywords &amp; Interactive Probe
                      </h3>
                    </div>
                    <div className="flex items-center gap-1.5 text-[10px] font-mono">
                      <span className="px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-bold">
                        {feedData?.cryptoRelevantCount ?? 0} Kept
                      </span>
                      <span className="px-2 py-0.5 rounded bg-rose-500/10 text-rose-400 border border-rose-500/20 font-bold">
                        {feedData?.filteredOutCount ?? 0} Noise Dropped
                      </span>
                    </div>
                  </div>

                  {/* Interactive Keyword Tester */}
                  <div className="p-2.5 rounded-xl bg-[#0a0d14] border border-white/10 space-y-2 font-mono">
                    <div className="flex gap-2">
                      <input
                        type="text"
                        aria-label="Test headline or text against crypto keyword classifier"
                        value={probeText}
                        onChange={(e) => setProbeText(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") runKeywordProbe(probeText);
                        }}
                        placeholder="Paste any headline to test crypto relevance & priority score..."
                        className="flex-1 bg-[#11141e] border border-white/15 rounded-lg px-2.5 py-1.5 text-[11px] text-white placeholder-neutral-500 focus:outline-none focus:border-sky-400"
                      />
                      <button
                        type="button"
                        onClick={() => runKeywordProbe(probeText)}
                        disabled={probeLoading}
                        className="px-3 py-1.5 rounded-lg bg-sky-400 hover:bg-sky-300 text-black font-bold text-[11px] transition disabled:opacity-50"
                      >
                        Check
                      </button>
                    </div>

                    {probeResult && (
                      <div className="flex flex-wrap items-center justify-between gap-2 text-[10px]">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span
                            className={`px-2 py-0.5 rounded font-bold flex items-center gap-1 border ${
                              probeResult.cryptoRelevant
                                ? "bg-emerald-500/15 text-emerald-400 border-emerald-500/30"
                                : "bg-rose-500/15 text-rose-400 border-rose-500/30"
                            }`}
                          >
                            {probeResult.cryptoRelevant ? (
                              <CheckCircle2 className="w-3 h-3" />
                            ) : (
                              <XCircle className="w-3 h-3" />
                            )}
                            {probeResult.cryptoRelevant ? "CRYPTO PASS" : "REJECTED"}
                          </span>
                          {probeResult.matchedCoins.map((c) => (
                            <span
                              key={c}
                              className="px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-300 border border-emerald-500/25 font-bold"
                            >
                              {c}
                            </span>
                          ))}
                          {probeResult.keywordMatches.slice(0, 4).map((km) => (
                            <span
                              key={km.keyword}
                              className={`px-1.5 py-0.5 rounded border ${tierPillStyle(
                                km.tier,
                                km.category
                              )}`}
                            >
                              {km.keyword} (+{km.weight.toFixed(0)})
                            </span>
                          ))}
                        </div>
                        <span className="text-amber-300 font-bold">
                          Est. Score: {probeResult.estimatedPriorityScore.toFixed(1)}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Corpus Top Crypto Keywords (Click to Boost in Priority Queue) */}
                  <div className="flex-1 flex flex-col justify-between min-h-0">
                    <div className="flex justify-between items-center text-[10px] font-mono text-neutral-400 mb-1.5">
                      <span>Top Crypto Keywords in Live RSS Corpus (Click to Boost)</span>
                      <span>T1 Catalyst • T2 On-Chain • T3 Domain</span>
                    </div>
                    <div className="flex flex-wrap gap-1.5 overflow-y-auto pr-1 content-start flex-1">
                      {(feedData?.keywordFrequencies || []).slice(0, 18).map((kf) => {
                        const isBoosted = activeBoostSet.has(kf.keyword.toLowerCase());
                        return (
                          <button
                            key={kf.keyword}
                            type="button"
                            onClick={() => toggleCustomBoostKeyword(kf.keyword)}
                            className={`px-2 py-1 rounded-lg border text-[10px] font-mono font-bold transition flex items-center gap-1 ${
                              isBoosted
                                ? "bg-fuchsia-500/25 border-fuchsia-400 text-fuchsia-200"
                                : `${tierPillStyle(kf.tier, kf.category)} hover:border-white/40`
                            }`}
                          >
                            <span>T{kf.tier}</span>
                            <span>{kf.keyword}</span>
                            <span className="opacity-75">×{kf.count}</span>
                          </button>
                        );
                      })}
                      {!loading && (feedData?.keywordFrequencies?.length ?? 0) === 0 && (
                        <span className="text-xs font-mono text-neutral-500">
                          No keyword matches found for current filter.
                        </span>
                      )}
                    </div>
                  </div>

                  {/* RSS Feed Health Status Footer */}
                  <div className="pt-2 border-t border-white/10 flex flex-wrap items-center gap-1.5 text-[10px] font-mono">
                    {(feedData?.feedStatuses || []).map((fs) => {
                      const ok = fs.status === "OK" || fs.status === "CACHED";
                      return (
                        <span
                          key={fs.sourceName}
                          title={`${fs.url} (${fs.latencyMs}ms)`}
                          className={`px-2 py-0.5 rounded border ${
                            ok
                              ? "bg-white/5 border-white/10 text-neutral-300"
                              : "bg-rose-500/10 border-rose-500/30 text-rose-300"
                          }`}
                        >
                          {fs.sourceName}: {fs.itemCount}
                        </span>
                      );
                    })}
                  </div>
                </div>
              ),
            },
          ]}
        />

        {/* PRIORITIZED TOP-K CRYPTO NEWS STREAM */}
        <section aria-label="Prioritized crypto news articles" className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-white/10 pb-3">
            <div>
              <h3 className="text-lg font-bold text-white tracking-tight">
                Priority-Ordered News Feed
              </h3>
              <p className="text-xs font-mono text-neutral-400">
                Extracted from Binary Max-Heap in descending priority ({feedData?.modeLabel || mode} •{" "}
                {feedData?.totalPipelineMs ?? "—"} ms end-to-end)
              </p>
            </div>
            <span className="text-xs font-mono text-neutral-400">
              Showing {feedData?.items?.length ?? 0} of {feedData?.heapSize ?? 0} queued crypto stories
            </span>
          </div>

          {error && (
            <div className="p-6 rounded-2xl bg-rose-500/10 border border-rose-500/30 text-rose-300 font-mono text-xs flex items-center justify-between gap-4">
              <span>{error}</span>
              <button
                type="button"
                onClick={() => fetchNewsFeed(true)}
                className="px-3 py-1.5 rounded-lg bg-rose-500 text-black font-bold"
              >
                Retry
              </button>
            </div>
          )}

          {loading && !feedData ? (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
              {Array.from({ length: 6 }).map((_, idx) => (
                <div
                  key={idx}
                  className="h-64 rounded-2xl bg-[#07080a] border border-white/10 animate-pulse p-5"
                />
              ))}
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
              {(feedData?.items || []).map((article) => {
                const hasImage = Boolean(article.imageUrl && !brokenImages[article.id]);
                return (
                  <article
                    key={article.id}
                    className="rounded-2xl bg-[#07080a] border border-white/12 hover:border-white/25 transition flex flex-col justify-between overflow-hidden min-w-0"
                  >
                    {hasImage && (
                      <img
                        src={article.imageUrl}
                        alt=""
                        loading="lazy"
                        onError={() =>
                          setBrokenImages((prev) => ({ ...prev, [article.id]: true }))
                        }
                        className="w-full aspect-video object-cover border-b border-white/10 bg-[#0b0e16]"
                      />
                    )}

                    <div className="p-5 flex-1 flex flex-col justify-between gap-3.5">
                      <div className="space-y-2.5">
                        {/* Rank + Priority Score + Sentiment Header */}
                        <header className="space-y-2">
                          <div className="flex items-center justify-between gap-2 font-mono text-[10px]">
                            <div className="flex items-center gap-1.5">
                              <span className="px-2 py-0.5 rounded-md bg-amber-400 text-black font-extrabold">
                                #{article.rank}
                              </span>
                              <span className="px-2 py-0.5 rounded-md bg-white/5 border border-white/10 text-amber-300 font-bold">
                                Score {article.priorityScore.toFixed(1)}
                              </span>
                            </div>

                            <span
                              className={`px-2 py-0.5 rounded-md border font-bold flex items-center gap-1 ${sentimentBadgeStyle(
                                article.sentimentHint
                              )}`}
                            >
                              {article.sentimentHint === "BULLISH" && (
                                <TrendingUp className="w-3 h-3" />
                              )}
                              {article.sentimentHint === "BEARISH" && (
                                <TrendingDown className="w-3 h-3" />
                              )}
                              {article.sentimentHint === "RISK_ALERT" && (
                                <ShieldAlert className="w-3 h-3" />
                              )}
                              {article.sentimentHint === "MACRO_REGULATORY" && (
                                <Zap className="w-3 h-3" />
                              )}
                              {article.sentimentHint.replace("_", " ")}
                            </span>
                          </div>

                          <div className="flex items-center justify-between text-[11px] font-mono text-neutral-400">
                            <span className="truncate font-semibold text-neutral-300">
                              {article.sourceName}
                            </span>
                            <span className="shrink-0">
                              {formatRelativeTime(
                                article.publishedEpochSec,
                                feedData?.generatedEpochSec
                              )}
                            </span>
                          </div>

                          <h4 className="text-base font-bold text-white leading-snug">
                            <a
                              href={article.link}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="hover:text-amber-300 transition inline-flex items-start gap-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 rounded"
                            >
                              <span>{article.title}</span>
                              <ExternalLink className="w-3.5 h-3.5 shrink-0 mt-1 opacity-60" />
                            </a>
                          </h4>
                        </header>

                        {/* Summary */}
                        {article.summary && (
                          <p className="text-xs text-neutral-400 leading-relaxed line-clamp-3">
                            {article.summary}
                          </p>
                        )}
                      </div>

                      {/* Score Telemetry + Coins + Keyword Pills Footer */}
                      <footer className="pt-3 border-t border-white/10 space-y-2.5">
                        {/* Priority Component Breakdown */}
                        <div className="grid grid-cols-4 gap-1 text-[10px] font-mono text-neutral-400 bg-[#0b0e14] border border-white/5 rounded-lg px-2 py-1.5">
                          <div>
                            <span className="text-neutral-500 block">Coin</span>
                            <span className="text-white font-bold">
                              +{article.coinScore.toFixed(0)}
                            </span>
                          </div>
                          <div>
                            <span className="text-neutral-500 block">Keyword</span>
                            <span className="text-sky-400 font-bold">
                              +{article.keywordScore.toFixed(0)}
                            </span>
                          </div>
                          <div>
                            <span className="text-neutral-500 block">Recency</span>
                            <span className="text-emerald-400 font-bold">
                              +{article.recencyScore.toFixed(0)}
                            </span>
                          </div>
                          <div className="text-right">
                            <span className="text-neutral-500 block">Source</span>
                            <span className="text-amber-300 font-bold">
                              +{article.sourceScore.toFixed(0)}
                            </span>
                          </div>
                        </div>

                        {/* Matched Keywords */}
                        {article.keywordMatches.length > 0 && (
                          <div className="flex flex-wrap gap-1 font-mono text-[10px]">
                            {article.keywordMatches.slice(0, 5).map((km) => (
                              <span
                                key={km.keyword}
                                className={`px-1.5 py-0.5 rounded border ${tierPillStyle(
                                  km.tier,
                                  km.category
                                )}`}
                              >
                                {km.keyword}
                              </span>
                            ))}
                          </div>
                        )}

                        {/* Coin Badges & Action */}
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex flex-wrap items-center gap-1">
                            {article.matchedCoins.slice(0, 4).map((coin) => (
                              <button
                                key={coin}
                                type="button"
                                onClick={() => handleSelectWatchlistCoin(coin)}
                                className="px-2 py-1 rounded-lg bg-[#0e121b] hover:bg-emerald-500/15 border border-white/10 hover:border-emerald-500/40 text-[10px] font-mono font-bold text-white flex items-center gap-1 transition"
                              >
                                <CoinLogo symbol={coin} size={13} />
                                <span>{coin}</span>
                              </button>
                            ))}
                          </div>

                          {article.matchedCoins.length > 0 && (
                            <button
                              type="button"
                              onClick={() =>
                                onNavigateToMarket(`${article.matchedCoins[0]}USDT`)
                              }
                              className="text-[10px] font-mono font-bold text-emerald-400 hover:text-emerald-300 flex items-center gap-1 transition"
                            >
                              <span>Analyze {article.matchedCoins[0]}</span>
                              <ArrowUpRight className="w-3 h-3" />
                            </button>
                          )}
                        </div>
                      </footer>
                    </div>
                  </article>
                );
              })}
            </div>
          )}

          {!loading && !error && (feedData?.items?.length ?? 0) === 0 && (
            <div className="p-10 rounded-2xl bg-[#07080a] border border-white/10 text-center font-mono text-xs text-neutral-400">
              No crypto articles matched the current filter ({focusCoin}). Try toggling{" "}
              <span className="text-sky-400 font-bold">Altcoin Google RSS: ON</span> or switching to{" "}
              <span className="text-amber-400 font-bold">ALL</span>.
            </div>
          )}
        </section>
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
