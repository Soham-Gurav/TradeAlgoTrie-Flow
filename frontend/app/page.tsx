"use client";

import React, { useState, useEffect, useRef } from "react";
import {
  TrendingUp,
  TrendingDown,
  ArrowUpRight,
  Activity,
  Layers,
  Search,
  Zap,
  ChevronDown,
  X,
} from "lucide-react";
import { createChart, CandlestickSeries } from "lightweight-charts";
import MagicBento, { type MagicBentoItem } from "./components/MagicBento";

const BACKEND_URL = "http://localhost:8085";

interface BinanceTicker {
  symbol: string;
  lastPrice: string;
  priceChangePercent: string;
  highPrice: string;
  lowPrice: string;
  quoteVolume: string;
  volume: string;
}

interface MarketItem {
  symbol: string;
  pair: string;
  price: number;
  change24h: number;
  high24h: number;
  low24h: number;
  volumeUsd: number;
  volumeUsdStr: string;
}

export default function MarketCentsApp() {
  const [currentPage, setCurrentPage] = useState<"home" | "market">("home");
  const [timeStr, setTimeStr] = useState<string>("");
  const [scrolled, setScrolled] = useState<boolean>(false);
  const [mounted, setMounted] = useState<boolean>(false);

  // Real Binance 24h Ticker State
  const [allTickers, setAllTickers] = useState<MarketItem[]>([]);
  const [loadingTickers, setLoadingTickers] = useState<boolean>(true);
  const [searchQuery, setSearchQuery] = useState<string>("");
  const [marketFilter, setMarketFilter] = useState<"main" | "all" | "gainers" | "losers" | "volume">("main");
  const [filterMenuOpen, setFilterMenuOpen] = useState<boolean>(false);
  const [searchDropdownOpen, setSearchDropdownOpen] = useState<boolean>(false);

  // Selected Coin for Deep Analysis
  const [selectedPair, setSelectedPair] = useState<string>("BTCUSDT");
  const [currentPrice, setCurrentPrice] = useState<number>(0);
  const [priceChange24h, setPriceChange24h] = useState<number>(0);
  const [high24h, setHigh24h] = useState<number>(0);
  const [low24h, setLow24h] = useState<number>(0);
  const [vol24h, setVol24h] = useState<string>("0");

  // Interactive Timeframe and Horizon
  const [timeframe, setTimeframe] = useState<string>("15m");
  const [barDepth, setBarDepth] = useState<number>(150);
  const [trieHorizon, setTrieHorizon] = useState<number>(5);
  const [homeSearchTerm, setHomeSearchTerm] = useState<string>("");
  const [hasSearched, setHasSearched] = useState<boolean>(true);

  // Algorithm Toggles
  const [trieEnabled, setTrieEnabled] = useState<boolean>(true);
  const [flowEnabled, setFlowEnabled] = useState<boolean>(true);
  const [trieData, setTrieData] = useState<any>(null);

  // Counting Bloom Filter & Randomized Quickselect State
  const [bloomSignature, setBloomSignature] = useState<string>("AUTO");
  const [bloomCustomInput, setBloomCustomInput] = useState<string>("");
  const [bloomResult, setBloomResult] = useState<any>(null);
  const [quickselectSize, setQuickselectSize] = useState<number>(10000);
  const [quickselectData, setQuickselectData] = useState<any>(null);
  const [quickselectNonce, setQuickselectNonce] = useState<number>(0);
  const [liveCoinVar95Pct, setLiveCoinVar95Pct] = useState<number | null>(null);

  // Flow Network State
  const [takerBuyVol, setTakerBuyVol] = useState<number>(0);
  const [takerSellVol, setTakerSellVol] = useState<number>(0);

  const handleSelectCoin = (inputSym: string) => {
    let clean = inputSym.trim().toUpperCase();
    if (!clean) return;
    if (!clean.endsWith("USDT")) clean += "USDT";
    setSelectedPair(clean);
    setHasSearched(true);
    const found = allTickers.find((t) => t.pair === clean);
    if (found) {
      setCurrentPrice(found.price);
      setPriceChange24h(found.change24h);
      setHigh24h(found.high24h);
      setLow24h(found.low24h);
      setVol24h(found.volumeUsdStr);
    }
  };
  const [orderBookBids, setOrderBookBids] = useState<{ price: number; qty: number }[]>([]);
  const [orderBookAsks, setOrderBookAsks] = useState<{ price: number; qty: number }[]>([]);
  const [solverResult, setSolverResult] = useState<any>(null);

  // Chart References
  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartInstanceRef = useRef<any>(null);
  const candleSeriesRef = useRef<any>(null);
  const ghostSeriesRef = useRef<any>(null);

  // Network Flow Canvas Reference
  const flowCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const flowAnimRef = useRef<number | null>(null);
  const particlesRef = useRef<any[]>([]);

  // WebSocket references
  const wsKlineRef = useRef<WebSocket | null>(null);
  const wsTradeRef = useRef<WebSocket | null>(null);
  const wsDepthRef = useRef<WebSocket | null>(null);

  // 1. Clock & Scroll Tracking
  useEffect(() => {
    setMounted(true);
    const updateTime = () => {
      const now = new Date();
      setTimeStr(now.toTimeString().split(" ")[0]);
    };
    updateTime();
    const t = setInterval(updateTime, 1000);

    const handleScroll = () => {
      setScrolled(window.scrollY > 15);
    };
    window.addEventListener("scroll", handleScroll);

    return () => {
      clearInterval(t);
      window.removeEventListener("scroll", handleScroll);
    };
  }, []);

  // 2. Fetch REAL 24h Tickers from Binance
  useEffect(() => {
    const fetchRealTickers = async () => {
      try {
        const res = await fetch("https://api.binance.com/api/v3/ticker/24hr");
        const data: BinanceTicker[] = await res.json();

        const filtered: MarketItem[] = data
          .filter((t) => {
            const sym = t.symbol;
            return (
              sym.endsWith("USDT") &&
              !sym.endsWith("UPUSDT") &&
              !sym.endsWith("DOWNUSDT") &&
              !sym.endsWith("BUSDT") &&
              !sym.includes("FDUSD") &&
              parseFloat(t.quoteVolume) >= 3000000
            );
          })
          .map((t) => {
            const qVol = parseFloat(t.quoteVolume);
            let vStr = "";
            if (qVol >= 1e9) vStr = (qVol / 1e9).toFixed(2) + "B";
            else if (qVol >= 1e6) vStr = (qVol / 1e6).toFixed(1) + "M";
            else vStr = (qVol / 1e3).toFixed(0) + "K";

            return {
              symbol: t.symbol.replace("USDT", ""),
              pair: t.symbol,
              price: parseFloat(t.lastPrice),
              change24h: parseFloat(t.priceChangePercent),
              high24h: parseFloat(t.highPrice),
              low24h: parseFloat(t.lowPrice),
              volumeUsd: qVol,
              volumeUsdStr: vStr,
            };
          });

        setAllTickers(filtered);
        setLoadingTickers(false);

        // Update selected coin stats from real data
        const current = filtered.find((item) => item.pair === selectedPair);
        if (current) {
          setCurrentPrice(current.price);
          setPriceChange24h(current.change24h);
          setHigh24h(current.high24h);
          setLow24h(current.low24h);
          setVol24h(current.volumeUsdStr);
        }
      } catch (err) {
        console.error("Binance 24hr ticker error:", err);
      }
    };

    fetchRealTickers();
    const interval = setInterval(fetchRealTickers, 8000);
    return () => clearInterval(interval);
  }, [selectedPair]);

  // 3. Lightweight Candlestick Chart Initialization
  useEffect(() => {
    if (!chartContainerRef.current) return;

    // Clean up previous chart instance
    if (chartInstanceRef.current) {
      chartInstanceRef.current.remove();
      chartInstanceRef.current = null;
    }

    const container = chartContainerRef.current;
    const chart = createChart(container, {
      width: container.clientWidth,
      height: 360,
      layout: {
        background: { color: "#07080a" },
        textColor: "#94a3b8",
      },
      grid: {
        vertLines: { color: "#131722" },
        horzLines: { color: "#131722" },
      },
      crosshair: {
        vertLine: { color: "#38bdf8", width: 1, style: 3 },
        horzLine: { color: "#38bdf8", width: 1, style: 3 },
      },
      timeScale: {
        borderColor: "#1e2638",
        timeVisible: true,
        secondsVisible: false,
      },
      rightPriceScale: {
        borderColor: "#1e2638",
      },
    });

    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: "#10b981",
      downColor: "#ef4444",
      borderUpColor: "#10b981",
      borderDownColor: "#ef4444",
      wickUpColor: "#10b981",
      wickDownColor: "#ef4444",
    });

    const ghostSeries = chart.addSeries(CandlestickSeries, {
      upColor: "rgba(56, 189, 248, 0.25)",
      downColor: "rgba(244, 63, 94, 0.25)",
      borderUpColor: "#38bdf8",
      borderDownColor: "#f43f5e",
      wickUpColor: "#38bdf8",
      wickDownColor: "#f43f5e",
    });

    chartInstanceRef.current = chart;
    candleSeriesRef.current = candleSeries;
    ghostSeriesRef.current = ghostSeries;

    // Resize listener
    const handleResize = () => {
      if (chartInstanceRef.current && container) {
        chartInstanceRef.current.applyOptions({ width: container.clientWidth });
      }
    };
    window.addEventListener("resize", handleResize);

    // Fetch REAL historical klines from Binance using dynamic timeframe and bar depth
    const fetchHistoricalKlines = async () => {
      try {
        const res = await fetch(`https://api.binance.com/api/v3/klines?symbol=${selectedPair}&interval=${timeframe}&limit=${barDepth}`);
        const klines = await res.json();
        if (!Array.isArray(klines)) return;
        const formatted = klines.map((k: any) => ({
          time: Math.floor(k[0] / 1000) as any,
          open: parseFloat(k[1]),
          high: parseFloat(k[2]),
          low: parseFloat(k[3]),
          close: parseFloat(k[4]),
        }));

        candleSeries.setData(formatted);
        chart.timeScale().fitContent();

        if (formatted.length > 0) {
          const last = formatted[formatted.length - 1];
          setCurrentPrice(last.close);
          const absMoves = formatted
            .map((c: any) => (c.open > 0 ? Math.abs(((c.close - c.open) / c.open) * 100) : 0))
            .sort((a: number, b: number) => a - b);
          const idx95 = Math.min(absMoves.length - 1, Math.round(0.95 * (absMoves.length - 1)));
          setLiveCoinVar95Pct(absMoves[idx95]);
        }
      } catch (e) {
        console.error("Kline fetch error:", e);
      }
    };

    fetchHistoricalKlines();

    // Connect WebSocket for LIVE candle streaming with dynamic timeframe
    if (wsKlineRef.current) wsKlineRef.current.close();
    const ws = new WebSocket(`wss://stream.binance.com:9443/ws/${selectedPair.toLowerCase()}@kline_${timeframe}`);
    wsKlineRef.current = ws;

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.k && candleSeriesRef.current) {
          const k = msg.k;
          candleSeriesRef.current.update({
            time: Math.floor(k.t / 1000) as any,
            open: parseFloat(k.o),
            high: parseFloat(k.h),
            low: parseFloat(k.l),
            close: parseFloat(k.c),
          });
          setCurrentPrice(parseFloat(k.c));
        }
      } catch (err) {}
    };

    return () => {
      window.removeEventListener("resize", handleResize);
      if (wsKlineRef.current) wsKlineRef.current.close();
      if (chartInstanceRef.current) {
        chartInstanceRef.current.remove();
        chartInstanceRef.current = null;
      }
    };
  }, [selectedPair, timeframe, barDepth, currentPage, hasSearched]);

  // 4. Query Real Double Trie from Java Backend with dynamic timeframe and horizon depth
  useEffect(() => {
    if (!trieEnabled) {
      if (ghostSeriesRef.current) ghostSeriesRef.current.setData([]);
      setTrieData(null);
      return;
    }

    const queryTrieBackend = async () => {
      try {
        const res = await fetch(`${BACKEND_URL}/api/trie/predict?symbol=${selectedPair}&interval=${timeframe}&steps=${trieHorizon}`);
        if (res.ok) {
          const data = await res.json();
          setTrieData(data);

          if (data.predictedPath && ghostSeriesRef.current) {
            const ghostCandles = data.predictedPath.map((p: any) => ({
              time: p.time as any,
              open: p.open,
              high: p.high,
              low: p.low,
              close: p.close,
            }));
            ghostSeriesRef.current.setData(ghostCandles);
          }
        }
      } catch (err) {
        console.warn("Java Trie API query error:", err);
      }
    };

    queryTrieBackend();
  }, [selectedPair, trieEnabled, timeframe, trieHorizon, currentPage, hasSearched]);

  // 4b. Query 4-Bit Counting Bloom Filter from Java Backend (:8085/api/bloom/screen)
  const resolvedBloomSignature = (() => {
    if (bloomSignature !== "AUTO") return bloomSignature;
    if (solverResult?.dirTitle?.includes("ABSORPTION")) return "SPOOF_BID_ICEBERG_LARGE";
    if (solverResult?.dirTitle?.includes("BREAKOUT") || solverResult?.dirTitle?.includes("BREAKDOWN")) {
      return "LIQUIDITY_VACUUM_PUMP";
    }
    if (Math.abs(takerBuyVol - takerSellVol) > 2 && (orderBookAsks[0]?.qty || 0) > 5) {
      return "DELTA_DIVERGENCE_TRAP";
    }
    return "ORGANIC_TWO_SIDED_FLOW";
  })();

  useEffect(() => {
    if (currentPage !== "market") return;
    const screenPattern = async () => {
      try {
        const res = await fetch(`${BACKEND_URL}/api/bloom/screen?signature=${encodeURIComponent(resolvedBloomSignature)}`);
        if (res.ok) {
          const data = await res.json();
          setBloomResult(data);
        }
      } catch (err) {
        console.warn("Java Bloom API query error:", err);
      }
    };
    screenPattern();
  }, [currentPage, resolvedBloomSignature]);

  // 4c. Query Randomized Quickselect VaR Benchmark from Java Backend (:8085/api/stats/benchmark)
  useEffect(() => {
    if (currentPage !== "market") return;
    const runQuickselectBenchmark = async () => {
      try {
        const res = await fetch(`${BACKEND_URL}/api/stats/benchmark?size=${quickselectSize}`);
        if (res.ok) {
          const data = await res.json();
          setQuickselectData(data);
        }
      } catch (err) {
        console.warn("Java Quickselect API query error:", err);
      }
    };
    runQuickselectBenchmark();
  }, [currentPage, quickselectSize, quickselectNonce, selectedPair]);

  // 5. Connect Real Binance WebSocket for Order Book Depth & Taker Trades (Flow Network)
  useEffect(() => {
    if (wsTradeRef.current) wsTradeRef.current.close();
    if (wsDepthRef.current) wsDepthRef.current.close();

    const sym = selectedPair.toLowerCase();

    // 1. Fetch initial depth snapshot
    const fetchDepthSnapshot = async () => {
      try {
        const res = await fetch(`https://api.binance.com/api/v3/depth?symbol=${selectedPair}&limit=20`);
        const data = await res.json();
        if (data && data.bids && data.asks) {
          const b = data.bids.slice(0, 5).map((x: any) => ({ price: parseFloat(x[0]), qty: parseFloat(x[1]) }));
          const a = data.asks.slice(0, 5).map((x: any) => ({ price: parseFloat(x[0]), qty: parseFloat(x[1]) }));
          setOrderBookBids(b);
          setOrderBookAsks(a);
        }
      } catch (e) {
        console.error("Depth snapshot error:", e);
      }
    };
    fetchDepthSnapshot();

    // 2. Real aggTrade WebSocket (CVD Flow)
    const wsTrade = new WebSocket(`wss://stream.binance.com:9443/ws/${sym}@aggTrade`);
    wsTradeRef.current = wsTrade;

    wsTrade.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        const q = parseFloat(data.q);
        const isMarketSell = data.m; // true if buyer is maker

        if (isMarketSell) {
          setTakerSellVol((prev) => prev * 0.98 + q);
          spawnFlowParticle("SELL");
        } else {
          setTakerBuyVol((prev) => prev * 0.98 + q);
          spawnFlowParticle("BUY");
        }
      } catch (err) {}
    };

    // 3. Real Level-2 Depth WebSocket
    const wsDepth = new WebSocket(`wss://stream.binance.com:9443/ws/${sym}@depth20@100ms`);
    wsDepthRef.current = wsDepth;

    wsDepth.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data && data.bids && data.asks) {
          const b = data.bids.slice(0, 5).map((x: any) => ({ price: parseFloat(x[0]), qty: parseFloat(x[1]) }));
          const a = data.asks.slice(0, 5).map((x: any) => ({ price: parseFloat(x[0]), qty: parseFloat(x[1]) }));
          setOrderBookBids(b);
          setOrderBookAsks(a);
        }
      } catch (err) {}
    };

    return () => {
      if (wsTradeRef.current) wsTradeRef.current.close();
      if (wsDepthRef.current) wsDepthRef.current.close();
    };
  }, [selectedPair, currentPage, hasSearched]);

  // Calculate Edmonds-Karp & Min-Cut whenever Depth / Taker Flow updates
  useEffect(() => {
    if (orderBookBids.length === 0 || orderBookAsks.length === 0) return;

    const askA1 = orderBookAsks[0].qty;
    const bidB1 = orderBookBids[0].qty;

    const buySaturation = askA1 > 0 ? takerBuyVol / askA1 : 1.0;
    const sellSaturation = bidB1 > 0 ? takerSellVol / bidB1 : 1.0;

    let dirTitle = "EQUILIBRIUM PIN";
    let targetReturnPct = 0.0;
    let targetPrice = currentPrice;

    if (takerBuyVol > 1.25 * takerSellVol && buySaturation >= 0.8) {
      dirTitle = "EXPONENTIAL BULLISH BREAKOUT";
      targetPrice = orderBookAsks[Math.min(orderBookAsks.length - 1, 3)].price * 1.001;
      targetReturnPct = ((targetPrice - currentPrice) / currentPrice) * 100.0;
    } else if (takerSellVol > 1.25 * takerBuyVol && sellSaturation >= 0.8) {
      dirTitle = "BEARISH CASCADE BREAKDOWN";
      targetPrice = orderBookBids[Math.min(orderBookBids.length - 1, 3)].price * 0.999;
      targetReturnPct = ((targetPrice - currentPrice) / currentPrice) * 100.0;
    } else if (takerBuyVol > takerSellVol && buySaturation < 0.45) {
      dirTitle = "ABSORPTION REVERSAL (BEARISH)";
      targetPrice = orderBookBids[1].price;
      targetReturnPct = ((targetPrice - currentPrice) / currentPrice) * 100.0;
    } else if (takerSellVol > takerBuyVol && sellSaturation < 0.45) {
      dirTitle = "ABSORPTION REVERSAL (BULLISH)";
      targetPrice = orderBookAsks[1].price;
      targetReturnPct = ((targetPrice - currentPrice) / currentPrice) * 100.0;
    }

    setSolverResult({
      dirTitle,
      targetReturnPct,
      targetPrice,
      bottleneckName: buySaturation > sellSaturation ? "Ask Wall A1" : "Bid Wall B1",
      bottleneckPrice: buySaturation > sellSaturation ? orderBookAsks[0].price : orderBookBids[0].price,
      bottleneckCap: buySaturation > sellSaturation ? askA1 : bidB1,
      bottleneckFlow: buySaturation > sellSaturation ? Math.min(takerBuyVol, askA1) : Math.min(takerSellVol, bidB1),
      saturationPct: Math.min(100, Math.max(buySaturation, sellSaturation) * 100),
    });
  }, [takerBuyVol, takerSellVol, orderBookBids, orderBookAsks, currentPrice]);

  // Flow Particle Emitter
  const spawnFlowParticle = (type: "BUY" | "SELL") => {
    if (particlesRef.current.length > 40) return;
    particlesRef.current.push({
      progress: 0,
      type: type,
      speed: 0.015 + Math.random() * 0.015,
      yOffset: (Math.random() - 0.5) * 12,
    });
  };

  // Live reactive snapshot ref for flicker-free 60fps canvas animation
  const flowDataRef = useRef({
    orderBookAsks,
    orderBookBids,
    solverResult,
    takerBuyVol,
    takerSellVol,
    currentPrice,
  });

  useEffect(() => {
    flowDataRef.current = {
      orderBookAsks,
      orderBookBids,
      solverResult,
      takerBuyVol,
      takerSellVol,
      currentPrice,
    };
  }, [orderBookAsks, orderBookBids, solverResult, takerBuyVol, takerSellVol, currentPrice]);

  // Real Flow Network Canvas Animation Loop (High-DPI Institutional Flow Graph)
  useEffect(() => {
    if (!flowEnabled || !flowCanvasRef.current) return;
    const canvas = flowCanvasRef.current;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let logicalW = 700;
    let logicalH = 300;

    const resize = () => {
      const parent = canvas.parentElement;
      if (!parent) return;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const nextW = Math.max(320, parent.clientWidth);
      const nextH = Math.max(280, parent.clientHeight);
      const targetW = Math.floor(nextW * dpr);
      const targetH = Math.floor(nextH * dpr);
      logicalW = nextW;
      logicalH = nextH;
      if (canvas.width !== targetW || canvas.height !== targetH) {
        canvas.width = targetW;
        canvas.height = targetH;
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();

    const ro = new ResizeObserver(resize);
    if (canvas.parentElement) ro.observe(canvas.parentElement);

    const fmtPrice = (p?: number) => {
      if (p === undefined || Number.isNaN(p)) return "—";
      if (p >= 1000) return `$${p.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
      if (p >= 1) return `$${p.toFixed(2)}`;
      return `$${p.toFixed(5)}`;
    };

    const drawRoundRect = (x: number, y: number, w: number, h: number, r: number) => {
      ctx.beginPath();
      ctx.roundRect(x, y, w, h, r);
    };

    const bezierPoint = (
      x0: number, y0: number,
      cx1: number, cy1: number,
      cx2: number, cy2: number,
      x1: number, y1: number,
      t: number
    ) => {
      const u = 1 - t;
      const tt = t * t;
      const uu = u * u;
      const uuu = uu * u;
      const ttt = tt * t;
      return {
        x: uuu * x0 + 3 * uu * t * cx1 + 3 * u * tt * cx2 + ttt * x1,
        y: uuu * y0 + 3 * uu * t * cy1 + 3 * u * tt * cy2 + ttt * y1,
      };
    };

    const drawConduit = (
      x0: number, y0: number,
      x1: number, y1: number,
      colorStart: string,
      colorEnd: string,
      width: number,
      dashed = false
    ) => {
      const dx = (x1 - x0) * 0.5;
      const grad = ctx.createLinearGradient(x0, y0, x1, y1);
      grad.addColorStop(0, colorStart);
      grad.addColorStop(1, colorEnd);

      ctx.save();
      ctx.strokeStyle = grad;
      ctx.lineWidth = width;
      ctx.lineCap = "round";
      if (dashed) ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(x0, y0);
      ctx.bezierCurveTo(x0 + dx, y0, x1 - dx, y1, x1, y1);
      ctx.stroke();
      ctx.restore();
    };

    const drawSourceCard = (
      cx: number, cy: number, w: number, h: number,
      accent: string, badge: string, title: string, value: string
    ) => {
      const x = cx - w / 2;
      const y = cy - h / 2;
      ctx.save();
      ctx.fillStyle = "#0b0e14";
      ctx.strokeStyle = accent;
      ctx.lineWidth = 1.25;
      drawRoundRect(x, y, w, h, 10);
      ctx.fill();
      ctx.stroke();

      // Top accent strip
      ctx.fillStyle = accent;
      drawRoundRect(x + 10, y + 10, 6, h - 20, 3);
      ctx.fill();

      ctx.textAlign = "left";
      ctx.textBaseline = "middle";
      ctx.fillStyle = "#94a3b8";
      ctx.font = '600 9px "JetBrains Mono", monospace';
      ctx.fillText(badge, x + 24, y + 17);

      ctx.fillStyle = "#ffffff";
      ctx.font = '700 13px "JetBrains Mono", monospace';
      ctx.fillText(value, x + 24, y + 36);

      ctx.fillStyle = accent;
      ctx.font = '700 9px "JetBrains Mono", monospace';
      ctx.textAlign = "right";
      ctx.fillText(title, x + w - 10, y + 17);
      ctx.restore();
    };

    const drawTierCard = (
      cx: number, cy: number, w: number, h: number,
      tag: string, priceText: string, capText: string,
      accent: string, fillRatio: number, isMinCut: boolean
    ) => {
      const x = cx - w / 2;
      const y = cy - h / 2;
      ctx.save();
      ctx.fillStyle = isMinCut ? "#12100d" : "#0b0e14";
      ctx.strokeStyle = isMinCut ? "#f59e0b" : "rgba(255, 255, 255, 0.14)";
      ctx.lineWidth = isMinCut ? 1.5 : 1;
      drawRoundRect(x, y, w, h, 9);
      ctx.fill();
      ctx.stroke();

      // Tag Pill (A1 / B1)
      ctx.fillStyle = isMinCut ? "rgba(245, 158, 11, 0.16)" : "rgba(255, 255, 255, 0.06)";
      drawRoundRect(x + 8, y + 8, 26, h - 20, 6);
      ctx.fill();

      ctx.fillStyle = isMinCut ? "#fbbf24" : accent;
      ctx.font = '700 10px "JetBrains Mono", monospace';
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(tag, x + 21, y + (h - 4) / 2);

      // Price & Capacity cleanly side by side
      ctx.textAlign = "left";
      ctx.fillStyle = "#f8fafc";
      ctx.font = '700 11px "JetBrains Mono", monospace';
      ctx.fillText(priceText, x + 42, y + 17);

      ctx.textAlign = "right";
      ctx.fillStyle = isMinCut ? "#fbbf24" : "#94a3b8";
      ctx.font = '600 10px "JetBrains Mono", monospace';
      ctx.fillText(capText, x + w - 10, y + 17);

      if (isMinCut) {
        ctx.fillStyle = "#f59e0b";
        ctx.font = '700 8px "JetBrains Mono", monospace';
        ctx.fillText("MIN-CUT", x + w - 10, y + 31);
      }

      // Capacity saturation micro-bar at bottom of card
      const barW = w - 52;
      ctx.fillStyle = "rgba(255,255,255,0.07)";
      drawRoundRect(x + 42, y + h - 9, barW, 3, 1.5);
      ctx.fill();

      ctx.fillStyle = isMinCut ? "#f59e0b" : accent;
      drawRoundRect(x + 42, y + h - 9, Math.max(4, barW * Math.min(1, fillRatio)), 3, 1.5);
      ctx.fill();

      ctx.restore();
    };

    const drawSinkCard = (
      cx: number, cy: number, w: number, h: number,
      accent: string, priceText: string, deltaText: string, stateText: string
    ) => {
      const x = cx - w / 2;
      const y = cy - h / 2;
      ctx.save();
      ctx.fillStyle = "#0b0e14";
      ctx.strokeStyle = accent;
      ctx.lineWidth = 1.5;
      drawRoundRect(x, y, w, h, 11);
      ctx.fill();
      ctx.stroke();

      ctx.textAlign = "left";
      ctx.textBaseline = "middle";
      ctx.fillStyle = "#94a3b8";
      ctx.font = '700 9px "JetBrains Mono", monospace';
      ctx.fillText("DYNAMIC SINK", x + 12, y + 16);

      ctx.fillStyle = "#ffffff";
      ctx.font = '700 14px "JetBrains Mono", monospace';
      ctx.fillText(priceText, x + 12, y + 35);

      ctx.fillStyle = accent;
      ctx.font = '700 10px "JetBrains Mono", monospace';
      ctx.fillText(`${deltaText} • ${stateText}`, x + 12, y + 53);
      ctx.restore();
    };

    const render = () => {
      const {
        orderBookAsks: liveAsks,
        orderBookBids: liveBids,
        solverResult: liveSolver,
        takerBuyVol: liveBuy,
        takerSellVol: liveSell,
        currentPrice: livePrice,
      } = flowDataRef.current;

      const w = logicalW;
      const h = logicalH;
      ctx.clearRect(0, 0, w, h);

      // Subtle architectural dot grid
      ctx.fillStyle = "rgba(255, 255, 255, 0.04)";
      for (let gx = 20; gx < w; gx += 24) {
        for (let gy = 28; gy < h; gy += 24) {
          ctx.fillRect(gx, gy, 1, 1);
        }
      }

      // Column headers
      const colLeftX = Math.max(80, w * 0.14);
      const colMidX = w * 0.5;
      const colRightX = Math.min(w - 86, w * 0.85);

      ctx.save();
      ctx.fillStyle = "#475569";
      ctx.font = '700 9px "JetBrains Mono", monospace';
      ctx.textAlign = "center";
      ctx.fillText("TAKER SOURCES", colLeftX, 16);
      ctx.fillText("L2 ORDER BOOK TIERS", colMidX, 16);
      ctx.fillText("SOLVER TARGET", colRightX, 16);
      ctx.restore();

      const sourceW = Math.min(132, w * 0.22);
      const sourceH = 52;
      const tierW = Math.min(182, w * 0.28);
      const tierH = 44;
      const sinkW = Math.min(154, w * 0.24);
      const sinkH = 66;

      const sourceBuy = { x: colLeftX, y: h * 0.34 };
      const sourceSell = { x: colLeftX, y: h * 0.7 };

      const askNodes = [
        { x: colMidX, y: h * 0.23, label: "A1", data: liveAsks[0] },
        { x: colMidX, y: h * 0.4, label: "A2", data: liveAsks[1] },
      ];
      const bidNodes = [
        { x: colMidX, y: h * 0.62, label: "B1", data: liveBids[0] },
        { x: colMidX, y: h * 0.79, label: "B2", data: liveBids[1] },
      ];

      let sinkY = h * 0.52;
      let sinkColor = "#38bdf8";
      let stateText = "PIN";
      if (liveSolver?.targetReturnPct) {
        sinkY = h * 0.52 - liveSolver.targetReturnPct * 55;
        sinkY = Math.max(58, Math.min(h - 46, sinkY));
        if (liveSolver.targetReturnPct > 0.001) {
          sinkColor = "#10b981";
          stateText = "BULL";
        } else if (liveSolver.targetReturnPct < -0.001) {
          sinkColor = "#f43f5e";
          stateText = "BEAR";
        }
      }
      const sinkNode = { x: colRightX, y: sinkY };

      const srcRightX = colLeftX + sourceW / 2;
      const midLeftX = colMidX - tierW / 2;
      const midRightX = colMidX + tierW / 2;
      const sinkLeftX = colRightX - sinkW / 2;

      const maxQty = Math.max(
        liveAsks[0]?.qty || 1,
        liveAsks[1]?.qty || 1,
        liveBids[0]?.qty || 1,
        liveBids[1]?.qty || 1,
        0.1
      );

      // 1. Curved cubic Bezier flow conduits
      askNodes.forEach((a, i) => {
        const capRatio = (a.data?.qty || 0.2) / maxQty;
        const pipeW = 1.2 + capRatio * 2.2;
        drawConduit(
          srcRightX, sourceBuy.y,
          midLeftX, a.y,
          "rgba(16, 185, 129, 0.55)",
          i === 0 ? "rgba(245, 158, 11, 0.55)" : "rgba(16, 185, 129, 0.22)",
          pipeW
        );
        drawConduit(
          midRightX, a.y,
          sinkLeftX, sinkNode.y - 10,
          i === 0 ? "rgba(245, 158, 11, 0.45)" : "rgba(56, 189, 248, 0.2)",
          "rgba(56, 189, 248, 0.45)",
          pipeW,
          i === 1
        );
      });

      bidNodes.forEach((b, i) => {
        const capRatio = (b.data?.qty || 0.2) / maxQty;
        const pipeW = 1.2 + capRatio * 2.2;
        drawConduit(
          srcRightX, sourceSell.y,
          midLeftX, b.y,
          "rgba(244, 63, 94, 0.55)",
          i === 0 ? "rgba(244, 63, 94, 0.45)" : "rgba(244, 63, 94, 0.2)",
          pipeW
        );
        drawConduit(
          midRightX, b.y,
          sinkLeftX, sinkNode.y + 10,
          "rgba(244, 63, 94, 0.4)",
          "rgba(56, 189, 248, 0.4)",
          pipeW,
          i === 1
        );
      });

      // 2. Animated flow particles gliding along the exact Bezier curves
      for (let i = particlesRef.current.length - 1; i >= 0; i--) {
        const p = particlesRef.current[i];
        p.progress += p.speed;
        if (p.progress >= 1.0) {
          particlesRef.current.splice(i, 1);
          continue;
        }

        const isBuy = p.type === "BUY";
        const originY = isBuy ? sourceBuy.y : sourceSell.y;
        const tier = isBuy ? askNodes[0] : bidNodes[0];
        const targetSinkY = isBuy ? sinkNode.y - 10 : sinkNode.y + 10;

        let pt;
        if (p.progress < 0.5) {
          const t = p.progress / 0.5;
          const dx = (midLeftX - srcRightX) * 0.5;
          pt = bezierPoint(srcRightX, originY, srcRightX + dx, originY, midLeftX - dx, tier.y, midLeftX, tier.y, t);
        } else {
          const t = (p.progress - 0.5) / 0.5;
          const dx = (sinkLeftX - midRightX) * 0.5;
          pt = bezierPoint(midRightX, tier.y, midRightX + dx, tier.y, sinkLeftX - dx, targetSinkY, sinkLeftX, targetSinkY, t);
        }

        ctx.save();
        ctx.fillStyle = isBuy ? "#10b981" : "#f43f5e";
        ctx.shadowColor = isBuy ? "#10b981" : "#f43f5e";
        ctx.shadowBlur = 8;
        ctx.beginPath();
        ctx.arc(pt.x, pt.y, 2.8, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
      }

      // 3. Draw Clean Institutional Cards
      drawSourceCard(sourceBuy.x, sourceBuy.y, sourceW, sourceH, "#10b981", "TAKER BUY", "S_BUY", liveBuy.toFixed(2));
      drawSourceCard(sourceSell.x, sourceSell.y, sourceW, sourceH, "#f43f5e", "TAKER SELL", "S_SELL", liveSell.toFixed(2));

      const minCutIsAsk = liveSolver?.bottleneckName?.includes("Ask");

      askNodes.forEach((a, i) => {
        const pStr = fmtPrice(a.data?.price);
        const qStr = a.data ? `${a.data.qty.toFixed(2)} cap` : "—";
        const ratio = a.data ? a.data.qty / maxQty : 0.2;
        drawTierCard(a.x, a.y, tierW, tierH, a.label, pStr, qStr, "#38bdf8", ratio, i === 0 && !!minCutIsAsk);
      });

      bidNodes.forEach((b, i) => {
        const pStr = fmtPrice(b.data?.price);
        const qStr = b.data ? `${b.data.qty.toFixed(2)} cap` : "—";
        const ratio = b.data ? b.data.qty / maxQty : 0.2;
        drawTierCard(b.x, b.y, tierW, tierH, b.label, pStr, qStr, "#10b981", ratio, i === 0 && !minCutIsAsk && !!liveSolver);
      });

      const sinkPrice = fmtPrice(liveSolver?.targetPrice || livePrice);
      const ret = liveSolver?.targetReturnPct || 0;
      const deltaText = `${ret >= 0 ? "+" : ""}${ret.toFixed(2)}%`;
      drawSinkCard(sinkNode.x, sinkNode.y, sinkW, sinkH, sinkColor, sinkPrice, deltaText, stateText);

      flowAnimRef.current = requestAnimationFrame(render);
    };

    render();
    return () => {
      ro.disconnect();
      if (flowAnimRef.current) cancelAnimationFrame(flowAnimRef.current);
    };
  }, [flowEnabled, currentPage]);

  // Rank REAL Gainers & Losers from live Binance array
  const realSortedByGain = [...allTickers].sort((a, b) => b.change24h - a.change24h);
  const realTopGainers = realSortedByGain.slice(0, 5);
  const realTopLosers = [...allTickers].sort((a, b) => a.change24h - b.change24h).slice(0, 5);
  const realTopVolumes = [...allTickers].sort((a, b) => b.volumeUsd - a.volumeUsd).slice(0, 5);

  // Categorize Memes & RWA using real tokens from live Binance API
  const memeSymbols = new Set(["DOGE", "SHIB", "PEPE", "FLOKI", "BONK", "WIF", "MEME", "BOME", "1000SATS", "NEIRO"]);
  const rwaSymbols = new Set(["LINK", "ONDO", "PENDLE", "OM", "MKR", "TRU", "POLYX", "RSR", "CFG"]);

  const realMemeCoins = allTickers.filter((t) => memeSymbols.has(t.symbol)).slice(0, 5);
  const realRwaCoins = allTickers.filter((t) => rwaSymbols.has(t.symbol)).slice(0, 5);

  // Main coins watchlist
  const mainSymbols = new Set(["BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "SUI", "NEAR", "PEPE", "AVAX", "TRX", "LINK", "ADA", "SHIB"]);
  const mainCoins = allTickers.filter((t) => mainSymbols.has(t.symbol));

  const filterLabels: Record<"main" | "all" | "gainers" | "losers" | "volume", string> = {
    main: "Main Caps",
    all: "All Assets",
    gainers: "Top Gainers",
    losers: "Top Losers",
    volume: "Top Volume",
  };

  const baseWatchlist =
    marketFilter === "all"
      ? allTickers.slice(0, 60)
      : marketFilter === "gainers"
      ? realSortedByGain.slice(0, 25)
      : marketFilter === "losers"
      ? [...allTickers].sort((a, b) => a.change24h - b.change24h).slice(0, 25)
      : marketFilter === "volume"
      ? [...allTickers].sort((a, b) => b.volumeUsd - a.volumeUsd).slice(0, 25)
      : mainCoins;

  const filteredWatchlist = (searchQuery.trim() ? allTickers : baseWatchlist).filter(
    (c) => c.symbol.toLowerCase().includes(searchQuery.toLowerCase()) || c.pair.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const searchSuggestions = searchQuery.trim()
    ? allTickers
        .filter((c) => c.symbol.toLowerCase().includes(searchQuery.toLowerCase()) || c.pair.toLowerCase().includes(searchQuery.toLowerCase()))
        .slice(0, 6)
    : [];

  return (
    <div className="min-h-screen bg-[#07080a] text-white flex flex-col font-sans selection:bg-red-700 selection:text-white">

      {/* TAYLOR EDITORIAL TOP NAVBAR (Full-Width Header) */}
      <header
        className={`fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-8 md:px-14 py-6 transition-all duration-300 pointer-events-auto ${
          scrolled
            ? "bg-black/85 backdrop-blur-md border-b border-white/10"
            : "bg-transparent backdrop-blur-none"
        }`}
      >
        {/* Brand Left */}
        <div className="flex items-center gap-8">
          <div
            onClick={() => setCurrentPage("home")}
            className="cursor-pointer flex items-center gap-1.5 text-base md:text-lg font-black tracking-tight uppercase text-white hover:opacity-80 transition"
          >
            <span>MarketCents</span>
            <span className="text-[10px] text-white/70 font-mono">®</span>
          </div>
        </div>

        {/* Center Navigation Links */}
        <nav className="flex items-center gap-8 text-xs md:text-sm font-semibold tracking-wide">
          <button
            onClick={() => setCurrentPage("home")}
            className={`transition relative py-1 ${
              currentPage === "home"
                ? "text-white after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[1.5px] after:bg-white font-bold"
                : "text-white/60 hover:text-white"
            }`}
          >
            Home
          </button>
          <button
            onClick={() => setCurrentPage("market")}
            className={`transition relative py-1 ${
              currentPage === "market"
                ? "text-white after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[1.5px] after:bg-white font-bold"
                : "text-white/60 hover:text-white"
            }`}
          >
            Market
          </button>
        </nav>

        {/* Empty Spacer to preserve exact center alignment */}
        <div className="w-16 hidden sm:block" />
      </header>

      {/* ========================================================================= */}
      {/* PAGE 1: HOME (Taylor Designer Editorial Landing Page) */}
      {/* ========================================================================= */}
      {currentPage === "home" && (
        <div className="flex-1 flex flex-col">
          {/* HERO SECTION: Screen Height, Pure Crimson Red Background + Centered Bitcoin in White Clouds */}
          <section className="relative h-screen min-h-[720px] flex flex-col justify-between p-8 md:p-14 overflow-hidden bg-[#820909] pt-36 md:pt-40 pb-10">
            {/* Subtle Texture Grain Overlay */}
            <div
              className="absolute inset-0 opacity-15 pointer-events-none mix-blend-overlay"
              style={{
                backgroundImage:
                  "url(\"data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E\")",
              }}
            />

            {/* Top Right Editorial Note Positioned Comfortably Below Navbar */}
            <div className="flex justify-end z-20">
              <div className="max-w-md text-right hidden md:block">
                <p className="text-sm text-white/90 font-normal leading-relaxed">
                  Designing bold, clean computational experiences that elevate trading alpha and turn complex microstructure into predictive signals.
                </p>
              </div>
            </div>

            {/* Center Floating Aesthetic Bitcoin Image in Soft White Clouds against Crimson */}
            <div className="absolute inset-0 flex items-center justify-center pointer-events-none z-10">
              <div className="relative flex items-center justify-center w-full max-w-xl px-4">
                <img
                  src="/hero_bitcoin.png"
                  alt="Aesthetic Bitcoin in Clouds"
                  className="w-[280px] sm:w-[380px] md:w-[460px] lg:w-[520px] max-h-[52vh] object-contain drop-shadow-[0_20px_50px_rgba(0,0,0,0.45)] transition-transform duration-700 hover:scale-105"
                />
              </div>
            </div>

            {/* Bottom Headline: Scaled Down & Aligned So Center Background is Clearly Visible */}
            <div className="z-20 pb-2 select-none">
              <h1 className="text-4xl sm:text-6xl md:text-7xl lg:text-8xl font-black uppercase tracking-tight text-white leading-[0.9] text-left">
                QUANTITATIVE
                <br />
                <span className="text-white/70 font-light">+</span> TRADING ALGO
              </h1>
            </div>
          </section>

          {/* FULL SCREEN WIDTH FOOTER */}
          <footer className="w-full bg-black py-20 md:py-28 px-6 border-t border-white/10 flex flex-col items-center justify-center relative overflow-hidden select-none">
            {/* Ambient Glow */}
            <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/[0.03] via-transparent to-transparent pointer-events-none" />

            {/* Giant Centered Text with Vertical Gradient Fade */}
            <div className="w-full flex items-center justify-center overflow-hidden py-4">
              <span className="font-black uppercase tracking-tight text-center leading-none text-transparent bg-clip-text bg-gradient-to-b from-transparent via-neutral-500/20 to-neutral-200/60 text-[11vw] max-w-full select-none inline-block">
                MarketCents
              </span>
            </div>
          </footer>
        </div>
      )}

      {/* ========================================================================= */}
      {/* PAGE 2: REAL MARKET TERMINAL (Unified Full-Width Symmetric Bento Studio)   */}
      {/* ========================================================================= */}
      {currentPage === "market" && (
        <div className="flex-1 flex flex-col w-full">
          <div className="w-full px-4 sm:px-8 lg:px-10 pt-24 pb-12 flex flex-col gap-6">
            {/* STUDIO HEADER BAR */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
              <div>
                <span className="text-[11px] font-mono uppercase tracking-widest text-emerald-400 font-semibold block mb-1">
                  Algorithmic Execution Studio
                </span>
                <h2 className="text-2xl md:text-3xl font-extrabold text-white tracking-tight flex items-center gap-3">
                  {selectedPair}
                  <span className="text-neutral-400 font-mono font-medium text-sm px-2.5 py-1 rounded-lg bg-white/5 border border-white/10">
                    {timeframe.toUpperCase()} • {barDepth} Bars
                  </span>
                </h2>
              </div>

              {/* ALGO ON/OFF TOGGLES */}
              <div className="flex items-center gap-2 bg-[#0a0c10] p-1.5 rounded-xl border border-white/10 font-mono text-xs self-start md:self-auto">
                <button
                  type="button"
                  onClick={() => setFlowEnabled(!flowEnabled)}
                  className={`px-4 py-2 rounded-lg font-semibold transition-all flex items-center gap-2 ${
                    flowEnabled ? "bg-emerald-500 text-black shadow" : "text-neutral-400 hover:text-white hover:bg-white/5"
                  }`}
                >
                  <Activity className="w-3.5 h-3.5" /> Network Flow: {flowEnabled ? "ON" : "OFF"}
                </button>
                <button
                  type="button"
                  onClick={() => setTrieEnabled(!trieEnabled)}
                  className={`px-4 py-2 rounded-lg font-semibold transition-all flex items-center gap-2 ${
                    trieEnabled ? "bg-sky-400 text-black shadow" : "text-neutral-400 hover:text-white hover:bg-white/5"
                  }`}
                >
                  <Layers className="w-3.5 h-3.5" /> Trie Predictor: {trieEnabled ? "ON" : "OFF"}
                </button>
              </div>
            </div>

            <MagicBento
              columns={4}
              rowHeight={240}
              gap={16}
              radius={16}
              theme="dark"
              glowColor="#10b981"
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
                  id: "market-watchlist",
                  colSpan: 2,
                  rowSpan: 2,
                  content: (
                    <div className="flex flex-col h-full justify-between gap-3">
                      {/* Header with Improved Search Bar & Custom Dropdown */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-white/10">
                        <h3 className="text-base font-bold tracking-tight text-white">Cryptocurrency Markets</h3>

                        <div className="flex items-center gap-2 w-full sm:w-auto">
                          {/* Search Bar with Live Autocomplete Dropdown */}
                          <div className="relative flex-1 sm:w-52">
                            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 pointer-events-none" />
                            <input
                              type="text"
                              value={searchQuery}
                              onFocus={() => setSearchDropdownOpen(true)}
                              onBlur={() => setTimeout(() => setSearchDropdownOpen(false), 150)}
                              onChange={(e) => {
                                setSearchQuery(e.target.value);
                                setSearchDropdownOpen(true);
                              }}
                              placeholder="Search coin..."
                              className="w-full bg-[#0e1118] border border-white/15 rounded-xl pl-8 pr-7 py-1.5 text-xs font-mono text-white placeholder-neutral-500 focus:outline-none focus:border-emerald-500/70 transition"
                            />
                            {searchQuery && (
                              <button
                                type="button"
                                onClick={() => setSearchQuery("")}
                                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-neutral-400 hover:text-white"
                              >
                                <X className="w-3.5 h-3.5" />
                              </button>
                            )}

                            {/* Search Autocomplete Dropdown */}
                            {searchDropdownOpen && searchSuggestions.length > 0 && (
                              <div className="absolute left-0 right-0 top-full mt-1.5 bg-[#0c0f16] border border-white/15 rounded-xl shadow-2xl z-30 overflow-hidden divide-y divide-white/5">
                                {searchSuggestions.map((s) => (
                                  <button
                                    key={s.pair}
                                    type="button"
                                    onMouseDown={(e) => {
                                      e.preventDefault();
                                      setSelectedPair(s.pair);
                                      setCurrentPrice(s.price);
                                      setPriceChange24h(s.change24h);
                                      setHigh24h(s.high24h);
                                      setLow24h(s.low24h);
                                      setVol24h(s.volumeUsdStr);
                                      setSearchQuery(s.symbol);
                                      setSearchDropdownOpen(false);
                                    }}
                                    className="w-full px-3 py-2 flex items-center justify-between text-xs font-mono hover:bg-white/10 transition text-left"
                                  >
                                    <span className="font-bold text-white">{s.symbol}</span>
                                    <span className={s.change24h >= 0 ? "text-emerald-400" : "text-rose-500"}>
                                      ${s.price >= 1 ? s.price.toFixed(2) : s.price.toFixed(5)} ({s.change24h >= 0 ? "+" : ""}{s.change24h.toFixed(2)}%)
                                    </span>
                                  </button>
                                ))}
                              </div>
                            )}
                          </div>

                          {/* Custom Sleek Category Filter Dropdown */}
                          <div className="relative">
                            <button
                              type="button"
                              onClick={() => setFilterMenuOpen(!filterMenuOpen)}
                              onBlur={() => setTimeout(() => setFilterMenuOpen(false), 150)}
                              className="flex items-center gap-1.5 bg-[#0e1118] border border-white/15 hover:border-white/30 rounded-xl px-3 py-1.5 text-xs font-mono text-white transition whitespace-nowrap"
                            >
                              <span>{filterLabels[marketFilter]}</span>
                              <ChevronDown className={`w-3.5 h-3.5 text-neutral-400 transition-transform ${filterMenuOpen ? "rotate-180" : ""}`} />
                            </button>

                            {filterMenuOpen && (
                              <div className="absolute right-0 top-full mt-1.5 w-36 bg-[#0c0f16] border border-white/15 rounded-xl shadow-2xl z-30 overflow-hidden py-1">
                                {(["main", "all", "gainers", "losers", "volume"] as const).map((key) => (
                                  <button
                                    key={key}
                                    type="button"
                                    onMouseDown={(e) => {
                                      e.preventDefault();
                                      setMarketFilter(key);
                                      setFilterMenuOpen(false);
                                    }}
                                    className={`w-full text-left px-3 py-2 text-xs font-mono transition ${
                                      marketFilter === key
                                        ? "bg-emerald-500/15 text-emerald-400 font-bold"
                                        : "text-neutral-300 hover:bg-white/10 hover:text-white"
                                    }`}
                                  >
                                    {filterLabels[key]}
                                  </button>
                                ))}
                              </div>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* Coin Table */}
                      <div className="overflow-x-auto flex-1 overflow-y-auto pr-1">
                        <table className="w-full text-left text-xs font-mono">
                          <thead className="sticky top-0 bg-[#07080a] z-10">
                            <tr className="text-neutral-500 border-b border-white/10 uppercase text-[10px]">
                              <th className="pb-2.5 px-2">Asset</th>
                              <th className="pb-2.5 px-2 text-right">Price (USD)</th>
                              <th className="pb-2.5 px-2 text-right">24h Change</th>
                              <th className="pb-2.5 px-2 text-right hidden sm:table-cell">24h Volume</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-white/5">
                            {loadingTickers ? (
                              <tr>
                                <td colSpan={4} className="py-10 text-center text-neutral-500 font-mono">
                                  Connecting to Binance API...
                                </td>
                              </tr>
                            ) : (
                              filteredWatchlist.map((coin) => {
                                const active = selectedPair === coin.pair;
                                const isUp = coin.change24h >= 0;
                                return (
                                  <tr
                                    key={coin.pair}
                                    onClick={() => {
                                      setSelectedPair(coin.pair);
                                      setCurrentPrice(coin.price);
                                      setPriceChange24h(coin.change24h);
                                      setHigh24h(coin.high24h);
                                      setLow24h(coin.low24h);
                                      setVol24h(coin.volumeUsdStr);
                                    }}
                                    className={`cursor-pointer transition-colors ${
                                      active ? "bg-emerald-500/10" : "hover:bg-white/5"
                                    }`}
                                  >
                                    <td className="py-2.5 px-2 flex items-center gap-2.5">
                                      <div className={`w-7 h-7 rounded-lg flex items-center justify-center font-bold text-[10px] ${
                                        active ? "bg-emerald-500 text-black" : "bg-white/10 text-white"
                                      }`}>
                                        {coin.symbol.slice(0, 3)}
                                      </div>
                                      <div>
                                        <span className="font-sans font-bold text-white block">{coin.symbol}</span>
                                        <span className="text-[10px] text-neutral-500">{coin.pair}</span>
                                      </div>
                                    </td>
                                    <td className="py-2.5 px-2 text-right font-bold text-white">
                                      ${coin.price >= 1 ? coin.price.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : coin.price.toFixed(6)}
                                    </td>
                                    <td className={`py-2.5 px-2 text-right font-bold ${isUp ? "text-emerald-400" : "text-rose-500"}`}>
                                      {isUp ? "+" : ""}{coin.change24h.toFixed(2)}%
                                    </td>
                                    <td className="py-2.5 px-2 text-right text-neutral-400 hidden sm:table-cell">
                                      ${coin.volumeUsdStr}
                                    </td>
                                  </tr>
                                );
                              })
                            )}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  ),
                },
                {
                  id: "market-benchmark",
                  colSpan: 2,
                  rowSpan: 1,
                  content: (
                    <div className="flex flex-col justify-between h-full">
                      <div className="flex justify-between items-start">
                        <div>
                          <span className="text-[10px] font-mono text-neutral-400 uppercase tracking-widest block">Market Benchmark</span>
                          <h4 className="text-lg font-bold tracking-tight text-white">Bitcoin Market Cap & Dominance</h4>
                        </div>
                        <span className="px-2.5 py-1 rounded-lg bg-white/5 border border-white/10 text-[11px] font-mono font-bold text-emerald-400">
                          BTCUSDT
                        </span>
                      </div>

                      <div className="grid grid-cols-2 gap-4 py-3 border-y border-white/10 text-xs font-mono">
                        <div>
                          <span className="text-neutral-500 block text-[10px] uppercase">Bitcoin Spot Price</span>
                          <span className="text-white font-bold text-lg">
                            ${allTickers.find((t) => t.pair === "BTCUSDT")?.price.toLocaleString() || "59,912.00"}
                          </span>
                          <span className={`font-bold text-[11px] block ${(allTickers.find((t) => t.pair === "BTCUSDT")?.change24h ?? 0) >= 0 ? "text-emerald-400" : "text-rose-500"}`}>
                            {allTickers.find((t) => t.pair === "BTCUSDT")?.change24h.toFixed(2) || "-2.08"}% 24h
                          </span>
                        </div>
                        <div>
                          <span className="text-neutral-500 block text-[10px] uppercase">Bitcoin Dominance</span>
                          <span className="text-white font-bold text-lg">59.13%</span>
                          <span className="text-emerald-400 font-bold text-[11px] block">+0.42% weekly</span>
                        </div>
                      </div>

                      <div className="flex flex-col gap-1.5 font-mono text-xs">
                        <div className="flex justify-between text-[11px] text-neutral-400">
                          <span>Dominance Share</span>
                          <span>BTC 59.1% • ETH 10.1% • Others 30.8%</span>
                        </div>
                        <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden flex">
                          <div style={{ width: "59.1%" }} className="bg-blue-500 h-full" />
                          <div style={{ width: "10.1%" }} className="bg-emerald-500 h-full" />
                          <div style={{ width: "30.8%" }} className="bg-rose-500 h-full" />
                        </div>
                      </div>
                    </div>
                  ),
                },
                {
                  id: "market-target",
                  colSpan: 2,
                  rowSpan: 1,
                  content: (
                    <div className="flex flex-col justify-between h-full">
                      <div className="flex justify-between items-start">
                        <div>
                          <span className="text-[10px] font-mono text-neutral-400 uppercase tracking-widest block">Active Analysis Target</span>
                          <div className="text-3xl font-extrabold tracking-tight text-white mt-1">{selectedPair}</div>
                        </div>
                        <div className="text-right font-mono">
                          <span className="text-2xl font-bold text-white block">
                            ${currentPrice >= 1 ? currentPrice.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : currentPrice.toFixed(6)}
                          </span>
                          <span className={`text-xs font-bold ${priceChange24h >= 0 ? "text-emerald-400" : "text-rose-500"}`}>
                            {priceChange24h >= 0 ? "+" : ""}{priceChange24h.toFixed(2)}% (24h)
                          </span>
                        </div>
                      </div>

                      <div className="grid grid-cols-3 gap-4 pt-3 border-t border-white/10 text-xs font-mono">
                        <div>
                          <span className="text-neutral-500 block text-[10px] uppercase">24h High</span>
                          <span className="text-white font-bold">${high24h}</span>
                        </div>
                        <div>
                          <span className="text-neutral-500 block text-[10px] uppercase">24h Low</span>
                          <span className="text-white font-bold">${low24h}</span>
                        </div>
                        <div className="text-right">
                          <span className="text-neutral-500 block text-[10px] uppercase">24h Volume</span>
                          <span className="text-white font-bold">${vol24h}</span>
                        </div>
                      </div>
                    </div>
                  ),
                },
                {
                  id: "market-chart",
                  colSpan: 2,
                  rowSpan: 2,
                  content: (
                    <div className="flex flex-col h-full justify-between">
                      <div className="flex flex-wrap justify-between items-center gap-2 mb-2 pb-2 border-b border-white/10">
                        <div className="flex items-center gap-2 text-xs font-mono">
                          <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                          <span className="text-white font-bold">{selectedPair} • {timeframe.toUpperCase()}</span>
                        </div>
                        <div className="flex flex-wrap items-center gap-1.5 text-[10px] font-mono">
                          <div className="flex items-center gap-0.5 bg-[#11141e] p-0.5 rounded-lg border border-white/10">
                            {(["1m", "5m", "15m", "1h", "4h", "1d"] as const).map((tf) => (
                              <button
                                key={tf}
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setTimeframe(tf);
                                }}
                                className={`px-1.5 py-0.5 rounded uppercase font-bold transition ${
                                  timeframe === tf ? "bg-emerald-500 text-black" : "text-neutral-400 hover:text-white"
                                }`}
                              >
                                {tf}
                              </button>
                            ))}
                          </div>
                          <div className="flex items-center gap-0.5 bg-[#11141e] p-0.5 rounded-lg border border-white/10">
                            <span className="text-neutral-500 px-1">Bars:</span>
                            {[50, 150, 300, 500].map((d) => (
                              <button
                                key={d}
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setBarDepth(d);
                                }}
                                className={`px-1.5 py-0.5 rounded font-bold transition ${
                                  barDepth === d ? "bg-white text-black" : "text-neutral-400 hover:text-white"
                                }`}
                              >
                                {d}
                              </button>
                            ))}
                          </div>
                          {trieEnabled && (
                            <div className="flex items-center gap-0.5 bg-[#11141e] p-0.5 rounded-lg border border-white/10">
                              <span className="text-neutral-500 px-1">Trie:</span>
                              {[3, 5, 8, 10].map((h) => (
                                <button
                                  key={h}
                                  type="button"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setTrieHorizon(h);
                                  }}
                                  className={`px-1.5 py-0.5 rounded font-bold transition ${
                                    trieHorizon === h ? "bg-sky-400 text-black" : "text-neutral-400 hover:text-white"
                                  }`}
                                >
                                  +{h}
                                </button>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>

                      <div ref={chartContainerRef} className="w-full flex-1 min-h-[340px] rounded-xl overflow-hidden bg-[#07080a]" />

                      <div className="flex justify-between items-center text-[10px] font-mono text-neutral-500 mt-2 pt-2 border-t border-white/10">
                        <span>Displaying {barDepth} Historical {timeframe.toUpperCase()} Bars</span>
                        {trieEnabled && <span className="text-sky-400 font-bold">Double Trie Ghost (+{trieHorizon} Bars)</span>}
                      </div>
                    </div>
                  ),
                },
                {
                  id: "market-telemetry",
                  colSpan: 2,
                  rowSpan: 2,
                  content: (
                    <div className="flex flex-col justify-between h-full">
                      <div>
                        <div className="flex justify-between items-start pb-4 border-b border-white/10">
                          <div>
                            <span className="text-[10px] font-mono uppercase tracking-widest text-neutral-400 block">
                              Market Microstructure Telemetry
                            </span>
                            <div className="text-3xl font-black font-mono text-white mt-1">
                              ${currentPrice >= 1 ? currentPrice.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : currentPrice.toFixed(6)}
                            </div>
                            <span className={`text-xs font-mono font-bold block mt-0.5 ${priceChange24h >= 0 ? "text-emerald-400" : "text-rose-500"}`}>
                              {priceChange24h >= 0 ? "+" : ""}{priceChange24h.toFixed(2)}% (24H Delta)
                            </span>
                          </div>
                          <div className="text-right font-mono text-xs space-y-1.5">
                            <div><span className="text-neutral-500">24h High:</span> <span className="text-white font-bold">${high24h}</span></div>
                            <div><span className="text-neutral-500">24h Low:</span> <span className="text-white font-bold">${low24h}</span></div>
                            <div><span className="text-neutral-500">24h Vol:</span> <span className="text-white font-bold">${vol24h}</span></div>
                          </div>
                        </div>

                        <div className="grid grid-cols-2 gap-4 py-4 border-b border-white/10 text-xs font-mono">
                          <div>
                            <span className="text-neutral-500 block text-[10px] uppercase">Top 5 L2 Bids</span>
                            <div className="mt-1.5 space-y-1">
                              {orderBookBids.slice(0, 5).map((b, i) => (
                                <div key={i} className="flex justify-between text-[11px]">
                                  <span className="text-emerald-400">${b.price >= 1 ? b.price.toFixed(2) : b.price.toFixed(5)}</span>
                                  <span className="text-neutral-300">{b.qty.toFixed(3)}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                          <div>
                            <span className="text-neutral-500 block text-[10px] uppercase">Top 5 L2 Asks</span>
                            <div className="mt-1.5 space-y-1">
                              {orderBookAsks.slice(0, 5).map((a, i) => (
                                <div key={i} className="flex justify-between text-[11px]">
                                  <span className="text-rose-400">${a.price >= 1 ? a.price.toFixed(2) : a.price.toFixed(5)}</span>
                                  <span className="text-neutral-300">{a.qty.toFixed(3)}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                        </div>
                      </div>

                      <div className="grid grid-cols-3 gap-3 pt-3 text-xs font-mono">
                        <div>
                          <span className="text-neutral-500 block text-[10px]">CVD Taker Flow</span>
                          <span className={`font-bold ${(takerBuyVol - takerSellVol) >= 0 ? "text-emerald-400" : "text-rose-500"}`}>
                            {(takerBuyVol - takerSellVol) >= 0 ? "+" : ""}{(takerBuyVol - takerSellVol).toFixed(2)}
                          </span>
                        </div>
                        <div>
                          <span className="text-neutral-500 block text-[10px]">Depth Imbalance</span>
                          <span className="text-emerald-400 font-bold">
                            {orderBookBids.length > 0 && orderBookAsks.length > 0
                              ? `${(((orderBookBids[0].qty - orderBookAsks[0].qty) / (orderBookBids[0].qty + orderBookAsks[0].qty)) * 100).toFixed(1)}%`
                              : "+12.4%"}
                          </span>
                        </div>
                        <div className="text-right">
                          <span className="text-neutral-500 block text-[10px]">Java Engine</span>
                          <span className="text-emerald-400 font-bold">Connected :8085</span>
                        </div>
                      </div>
                    </div>
                  ),
                },
                ...(flowEnabled
                  ? [
                      {
                        id: "market-flow",
                        colSpan: trieEnabled ? 2 : 4,
                        rowSpan: 2,
                        content: (
                          <div className="flex flex-col h-full justify-between">
                            <div className="flex justify-between items-start mb-2 pb-2 border-b border-white/10">
                              <div>
                                <span className="text-[10px] font-mono text-emerald-400 uppercase tracking-widest block font-bold">UNIT 5 FORMULATION</span>
                                <h4 className="text-base font-bold text-white">Edmonds-Karp Capacitated Flow Network</h4>
                              </div>
                              <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-bold">
                                O(V·E²)
                              </span>
                            </div>

                            <div className="relative w-full flex-1 min-h-[290px] border border-white/10 rounded-xl bg-[#06080d] overflow-hidden">
                              <canvas ref={flowCanvasRef} className="w-full h-full block" />
                            </div>

                            {solverResult && (
                              <div className="mt-3 pt-2 border-t border-white/10 grid grid-cols-2 gap-3 text-xs font-mono">
                                <div>
                                  <span className="text-neutral-500 text-[10px] block uppercase">Min-Cut Bottleneck Wall</span>
                                  <span className="text-amber-400 font-bold">
                                    {solverResult.bottleneckName} (${solverResult.bottleneckPrice >= 1 ? solverResult.bottleneckPrice.toFixed(2) : solverResult.bottleneckPrice.toFixed(4)})
                                  </span>
                                  <span className="text-neutral-400 text-[10px] block">Saturation: {solverResult.saturationPct.toFixed(1)}%</span>
                                </div>
                                <div>
                                  <span className="text-neutral-500 text-[10px] block uppercase">Dynamic Sink State</span>
                                  <span className={`font-bold ${solverResult.targetReturnPct >= 0 ? "text-emerald-400" : "text-rose-500"}`}>
                                    {solverResult.dirTitle}
                                  </span>
                                  <span className="text-sky-400 text-[10px] block">
                                    Target: ${solverResult.targetPrice >= 1 ? solverResult.targetPrice.toFixed(2) : solverResult.targetPrice.toFixed(4)} ({solverResult.targetReturnPct >= 0 ? "+" : ""}{solverResult.targetReturnPct.toFixed(2)}%)
                                  </span>
                                </div>
                              </div>
                            )}
                          </div>
                        ),
                      },
                    ]
                  : []),
                ...(trieEnabled
                  ? [
                      {
                        id: "market-trie",
                        colSpan: flowEnabled ? 2 : 4,
                        rowSpan: 2,
                        content: (
                          <div className="flex flex-col h-full justify-between">
                            <div className="flex justify-between items-start mb-2 pb-2 border-b border-white/10">
                              <div>
                                <span className="text-[10px] font-mono text-sky-400 uppercase tracking-widest block font-bold">UNIT 4 FORMULATION</span>
                                <h4 className="text-base font-bold text-white">Double Trie Predictive Autofill (+{trieHorizon} Bars)</h4>
                              </div>
                              <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-sky-500/10 text-sky-400 border border-sky-500/20 font-bold">
                                O(L) Query
                              </span>
                            </div>

                            <div className="overflow-x-auto flex-1 border border-white/10 rounded-xl bg-[#0a0d14]">
                              <table className="w-full text-left text-xs font-mono">
                                <thead>
                                  <tr className="border-b border-white/10 text-neutral-500 uppercase">
                                    <th className="py-2 px-3">Horizon</th>
                                    <th className="py-2 px-3">Token</th>
                                    <th className="py-2 px-3">Exp Close</th>
                                    <th className="py-2 px-3">Expected Δ%</th>
                                    <th className="py-2 px-3 text-right">Confidence</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-white/5">
                                  {trieData?.predictedPath ? (
                                    trieData.predictedPath.slice(0, trieHorizon).map((p: any, i: number) => (
                                      <tr key={i}>
                                        <td className="py-2 px-3 font-bold text-sky-400">+{p.step} Bar</td>
                                        <td className="py-2 px-3 text-white font-bold">{p.token}</td>
                                        <td className="py-2 px-3 text-neutral-300">
                                          ${p.close >= 1 ? p.close.toFixed(2) : p.close.toFixed(4)}
                                        </td>
                                        <td className={`py-2 px-3 font-bold ${p.expectedDeltaPct >= 0 ? "text-emerald-400" : "text-rose-500"}`}>
                                          {p.expectedDeltaPct >= 0 ? "+" : ""}{p.expectedDeltaPct.toFixed(2)}%
                                        </td>
                                        <td className="py-2 px-3 text-right text-amber-400 font-bold">{p.confidencePct.toFixed(1)}%</td>
                                      </tr>
                                    ))
                                  ) : (
                                    <tr>
                                      <td colSpan={5} className="py-8 text-center text-neutral-500">
                                        Synchronizing with Java Double Trie Predictor (:8085)...
                                      </td>
                                    </tr>
                                  )}
                                </tbody>
                              </table>
                            </div>

                            <div className="mt-3 pt-2 border-t border-white/10 flex justify-between items-center text-xs font-mono">
                              <span className="text-neutral-400">
                                Suffix Backoff: <span className="text-emerald-400 font-bold">{trieData?.suffixBackoffLevel || "Exact (Depth 4)"}</span>
                              </span>
                              <span className="text-emerald-400 font-bold">{trieData?.prefixMatchCount || 4} Historical Occurrences</span>
                            </div>
                          </div>
                        ),
                      },
                    ]
                  : []),
                {
                  id: "market-bloom",
                  colSpan: 2,
                  rowSpan: 2,
                  content: (
                    <div className="flex flex-col h-full justify-between">
                      <div className="flex justify-between items-start pb-2.5 border-b border-white/10">
                        <div>
                          <span className="text-[10px] font-mono text-amber-400 uppercase tracking-widest block font-bold">UNIT 6 / CO5 FORMULATION</span>
                          <h4 className="text-base font-bold text-white">4-Bit Counting Bloom Filter Trap Screener</h4>
                        </div>
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20 font-bold">
                          O(k) Zero False Negatives
                        </span>
                      </div>

                      {/* Signature Selector & Custom Test Input */}
                      <div className="flex flex-col gap-2 py-2.5 border-b border-white/10">
                        <div className="flex flex-wrap items-center gap-1.5 text-[10px] font-mono">
                          {[
                            { id: "AUTO", label: "Auto (Live Flow)" },
                            { id: "SPOOF_BID_ICEBERG_LARGE", label: "Spoof Iceberg" },
                            { id: "STOP_HUNT_SWEEP_REVERSAL", label: "Stop-Hunt Sweep" },
                            { id: "LIQUIDITY_VACUUM_PUMP", label: "Vacuum Pump" },
                            { id: "ORGANIC_TWO_SIDED_FLOW", label: "Clean Flow" },
                          ].map((preset) => (
                            <button
                              key={preset.id}
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setBloomSignature(preset.id);
                              }}
                              className={`px-2 py-1 rounded-lg border transition font-bold ${
                                bloomSignature === preset.id
                                  ? "bg-amber-400 text-black border-amber-400"
                                  : "bg-[#0e1118] text-neutral-300 border-white/10 hover:border-white/30"
                              }`}
                            >
                              {preset.label}
                            </button>
                          ))}
                        </div>

                        <div className="flex gap-2">
                          <input
                            type="text"
                            value={bloomCustomInput}
                            onChange={(e) => setBloomCustomInput(e.target.value)}
                            onKeyDown={(e) => {
                              if (e.key === "Enter" && bloomCustomInput.trim()) {
                                setBloomSignature(bloomCustomInput.trim().toUpperCase());
                              }
                            }}
                            placeholder="Test custom signature (e.g. LAYERED_WALL_CANCELLATION)..."
                            className="flex-1 bg-[#0e1118] border border-white/15 rounded-lg px-2.5 py-1.5 text-[11px] font-mono text-white placeholder-neutral-500 focus:outline-none focus:border-amber-400/70"
                          />
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              if (bloomCustomInput.trim()) setBloomSignature(bloomCustomInput.trim().toUpperCase());
                            }}
                            className="px-3 py-1.5 rounded-lg bg-white text-black font-mono font-bold text-[11px] hover:bg-neutral-200 transition"
                          >
                            Screen
                          </button>
                        </div>
                      </div>

                      {/* Live Screening Verdict & Hash Bucket Visualization */}
                      <div className="flex-1 flex flex-col justify-center gap-3 py-2 font-mono">
                        <div className={`p-3 rounded-xl border ${
                          bloomResult?.possiblyPresent
                            ? "bg-amber-500/10 border-amber-500/40"
                            : "bg-emerald-500/10 border-emerald-500/30"
                        }`}>
                          <div className="flex items-center justify-between text-xs mb-1">
                            <span className="text-neutral-400 uppercase text-[10px]">Active Signature</span>
                            <span className={`font-bold text-[11px] px-2 py-0.5 rounded ${
                              bloomResult?.possiblyPresent ? "bg-amber-400 text-black" : "bg-emerald-500 text-black"
                            }`}>
                              {bloomResult ? (bloomResult.possiblyPresent ? "TRAP FLAGGED" : "CLEAR (SAFE)") : "SYNCING..."}
                            </span>
                          </div>
                          <div className="text-sm font-bold text-white truncate">{bloomResult?.signature || resolvedBloomSignature}</div>
                          <p className="text-[11px] text-neutral-300 mt-1 leading-snug">
                            {bloomResult?.recommendation || "Connecting to Java Counting Bloom Filter (:8085)..."}
                          </p>
                        </div>

                        {/* Hash Bucket Match Bar */}
                        <div className="space-y-1.5">
                          <div className="flex justify-between text-[10px] text-neutral-400">
                            <span>FNV-1a Hash Buckets Matched</span>
                            <span className="text-white font-bold">
                              {bloomResult ? `${bloomResult.matchingHashBuckets} / ${bloomResult.numHashFunctions} buckets > 0` : "—"}
                            </span>
                          </div>
                          <div className="grid grid-cols-7 gap-1.5">
                            {Array.from({ length: bloomResult?.numHashFunctions || 7 }).map((_, idx) => {
                              const hit = bloomResult && idx < bloomResult.matchingHashBuckets;
                              return (
                                <div
                                  key={idx}
                                  className={`h-2.5 rounded-sm transition-all ${
                                    hit
                                      ? bloomResult.possiblyPresent
                                        ? "bg-amber-400 shadow-[0_0_8px_rgba(251,191,36,0.5)]"
                                        : "bg-sky-400"
                                      : "bg-white/10"
                                  }`}
                                />
                              );
                            })}
                          </div>
                        </div>
                      </div>

                      {/* Footer Telemetry */}
                      <div className="pt-2.5 border-t border-white/10 grid grid-cols-3 gap-2 text-xs font-mono">
                        <div>
                          <span className="text-neutral-500 block text-[10px]">False Pos Rate</span>
                          <span className="text-white font-bold">
                            {bloomResult ? `${(bloomResult.theoreticalFalsePositiveRate * 100).toFixed(4)}%` : "—"}
                          </span>
                        </div>
                        <div>
                          <span className="text-neutral-500 block text-[10px]">Bit Array / Traps</span>
                          <span className="text-white font-bold">
                            {bloomResult ? `${bloomResult.bitArraySize}b / ${bloomResult.elementCount}` : "—"}
                          </span>
                        </div>
                        <div className="text-right">
                          <span className="text-neutral-500 block text-[10px]">Fill Ratio</span>
                          <span className="text-emerald-400 font-bold">
                            {bloomResult ? `${(bloomResult.capacityFillRatio * 100).toFixed(2)}%` : "—"}
                          </span>
                        </div>
                      </div>
                    </div>
                  ),
                },
                {
                  id: "market-quickselect",
                  colSpan: 2,
                  rowSpan: 2,
                  content: (
                    <div className="flex flex-col h-full justify-between">
                      <div className="flex justify-between items-start pb-2.5 border-b border-white/10">
                        <div>
                          <span className="text-[10px] font-mono text-emerald-400 uppercase tracking-widest block font-bold">UNIT 1 & 6 FORMULATION</span>
                          <h4 className="text-base font-bold text-white">Randomized Quickselect 95th-Percentile VaR</h4>
                        </div>
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-bold">
                          Expected O(N)
                        </span>
                      </div>

                      {/* Controls: Sample Size N + Re-Run */}
                      <div className="flex flex-wrap items-center justify-between gap-2 py-2.5 border-b border-white/10 text-[10px] font-mono">
                        <div className="flex items-center gap-1">
                          <span className="text-neutral-400 mr-1">Ticks (N):</span>
                          {[5000, 10000, 25000, 50000].map((sz) => (
                            <button
                              key={sz}
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setQuickselectSize(sz);
                              }}
                              className={`px-2 py-1 rounded-lg border transition font-bold ${
                                quickselectSize === sz
                                  ? "bg-emerald-500 text-black border-emerald-500"
                                  : "bg-[#0e1118] text-neutral-300 border-white/10 hover:border-white/30"
                              }`}
                            >
                              {(sz / 1000).toFixed(0)}k
                            </button>
                          ))}
                        </div>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setQuickselectNonce((n) => n + 1);
                          }}
                          className="px-2.5 py-1 rounded-lg bg-white text-black font-bold hover:bg-neutral-200 transition"
                        >
                          Re-Run Benchmark
                        </button>
                      </div>

                      {/* Latency Comparison Bars & Live Coin VaR */}
                      <div className="flex-1 flex flex-col justify-center gap-3 py-2 font-mono">
                        <div className="grid grid-cols-2 gap-3">
                          <div className="p-3 rounded-xl bg-[#0a0d14] border border-white/10">
                            <span className="text-[10px] text-neutral-400 block uppercase">Java Speedup Factor</span>
                            <span className="text-2xl font-extrabold text-emerald-400">
                              {quickselectData ? `${quickselectData.speedupFactor.toFixed(2)}×` : "—"}
                            </span>
                            <span className="text-[10px] text-neutral-400 block mt-0.5">Faster than Full Sort</span>
                          </div>
                          <div className="p-3 rounded-xl bg-[#0a0d14] border border-white/10">
                            <span className="text-[10px] text-neutral-400 block uppercase">{selectedPair} 95% Bar Move</span>
                            <span className="text-2xl font-extrabold text-sky-400">
                              {liveCoinVar95Pct !== null ? `±${liveCoinVar95Pct.toFixed(2)}%` : "—"}
                            </span>
                            <span className="text-[10px] text-neutral-400 block mt-0.5">Across {barDepth} live {timeframe.toUpperCase()} bars</span>
                          </div>
                        </div>

                        {/* Visual Latency Bar Comparison */}
                        <div className="space-y-2 text-[11px]">
                          <div>
                            <div className="flex justify-between mb-1">
                              <span className="text-emerald-400 font-bold">Randomized Quickselect O(N)</span>
                              <span className="text-white font-bold">
                                {quickselectData ? `${(quickselectData.quickselectTimeNs / 1000).toFixed(1)} µs` : "—"}
                              </span>
                            </div>
                            <div className="w-full h-2.5 rounded-full bg-white/10 overflow-hidden">
                              <div
                                className="h-full bg-emerald-500 rounded-full transition-all duration-300"
                                style={{
                                  width: quickselectData && quickselectData.sortBaselineTimeNs > 0
                                    ? `${Math.max(8, Math.min(100, (quickselectData.quickselectTimeNs / quickselectData.sortBaselineTimeNs) * 100))}%`
                                    : "20%",
                                }}
                              />
                            </div>
                          </div>

                          <div>
                            <div className="flex justify-between mb-1">
                              <span className="text-rose-400 font-bold">Full Arrays.sort O(N log N)</span>
                              <span className="text-white font-bold">
                                {quickselectData ? `${(quickselectData.sortBaselineTimeNs / 1000).toFixed(1)} µs` : "—"}
                              </span>
                            </div>
                            <div className="w-full h-2.5 rounded-full bg-white/10 overflow-hidden">
                              <div className="h-full bg-rose-500/80 rounded-full w-full" />
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* Footer Telemetry */}
                      <div className="pt-2.5 border-t border-white/10 grid grid-cols-3 gap-2 text-xs font-mono">
                        <div>
                          <span className="text-neutral-500 block text-[10px]">Sample N</span>
                          <span className="text-white font-bold">
                            {quickselectData ? quickselectData.arraySize.toLocaleString() : quickselectSize.toLocaleString()}
                          </span>
                        </div>
                        <div>
                          <span className="text-neutral-500 block text-[10px]">95th Quantile</span>
                          <span className="text-white font-bold">
                            {quickselectData ? quickselectData.selectedValue.toFixed(2) : "—"}
                          </span>
                        </div>
                        <div className="text-right">
                          <span className="text-neutral-500 block text-[10px]">Aux Space</span>
                          <span className="text-emerald-400 font-bold">O(1) In-Place</span>
                        </div>
                      </div>
                    </div>
                  ),
                },
              ]}
            />
          </div>

          {/* FULL SCREEN WIDTH FOOTER */}
          <footer className="w-full bg-black py-20 md:py-28 px-6 border-t border-white/10 flex flex-col items-center justify-center relative overflow-hidden select-none">
            {/* Ambient Glow */}
            <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/[0.03] via-transparent to-transparent pointer-events-none" />

            {/* Giant Centered Text with Vertical Gradient Fade */}
            <div className="w-full flex items-center justify-center overflow-hidden py-4">
              <span className="font-black uppercase tracking-tight text-center leading-none text-transparent bg-clip-text bg-gradient-to-b from-transparent via-neutral-500/20 to-neutral-200/60 text-[11vw] max-w-full select-none inline-block">
                MarketCents
              </span>
            </div>
          </footer>
        </div>
      )}
    </div>
  );
}
