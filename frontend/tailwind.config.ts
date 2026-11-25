import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        darkBg: "#08090c",
        darkSurface: "#101319",
        darkCard: "#141821",
        darkBorder: "#1d2330",
        darkBorderSubtle: "#283245",
        accentEmerald: "#10b981",
        accentSky: "#38bdf8",
        accentAmber: "#f59e0b",
        accentRose: "#f43f5e",
      },
      fontFamily: {
        sans: ["var(--font-sans)", "sans-serif"],
        mono: ["var(--font-mono)", "monospace"],
      },
    },
  },
  plugins: [],
};
export default config;
