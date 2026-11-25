"use client";

import React, { useState, useEffect } from "react";

interface CoinLogoProps {
  symbol: string;
  size?: number;
  active?: boolean;
  className?: string;
}

/**
 * Renders a cryptocurrency logo from CDN with automatic fallback
 * to a secondary icon CDN and finally a clean monogram badge.
 */
export default function CoinLogo({
  symbol,
  size = 28,
  active = false,
  className = "",
}: CoinLogoProps) {
  const displaySym = symbol.replace(/USDT$/i, "").toUpperCase();
  const lookupKey = displaySym.replace(/^1000/, "").toLowerCase();

  const sources = [
    `https://cdn.jsdelivr.net/gh/spothq/cryptocurrency-icons@master/128/color/${lookupKey}.png`,
    `https://assets.coincap.io/assets/icons/${lookupKey}@2x.png`,
  ];

  const [sourceIdx, setSourceIdx] = useState<number>(0);

  useEffect(() => {
    setSourceIdx(0);
  }, [lookupKey]);

  if (sourceIdx >= sources.length || !lookupKey) {
    return (
      <div
        style={{ width: size, height: size }}
        className={`rounded-full flex items-center justify-center font-mono font-bold text-[10px] shrink-0 ${
          active ? "bg-emerald-500 text-black" : "bg-white/10 text-white"
        } ${className}`}
      >
        {displaySym.slice(0, 3)}
      </div>
    );
  }

  return (
    <img
      src={sources[sourceIdx]}
      alt={`${displaySym} logo`}
      width={size}
      height={size}
      loading="lazy"
      onError={() => setSourceIdx((prev) => prev + 1)}
      style={{ width: size, height: size }}
      className={`rounded-full object-contain shrink-0 bg-white/5 ${
        active ? "ring-2 ring-emerald-400" : ""
      } ${className}`}
    />
  );
}
