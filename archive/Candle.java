import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a single daily OHLCV candlestick with classification methods
 * for 15+ distinct single-candlestick types.
 */
public record Candle(String date, double open, double high, double low, double close, long volume) {

    private static final Pattern OBJ_PATTERN = Pattern.compile("\\{([^\\}]+)\\}");
    private static final Pattern OPEN_PAT = Pattern.compile("\"open\"\\s*:\\s*([\\d\\.]+)");
    private static final Pattern HIGH_PAT = Pattern.compile("\"high\"\\s*:\\s*([\\d\\.]+)");
    private static final Pattern LOW_PAT = Pattern.compile("\"low\"\\s*:\\s*([\\d\\.]+)");
    private static final Pattern CLOSE_PAT = Pattern.compile("\"close\"\\s*:\\s*([\\d\\.]+)");
    private static final Pattern VOL_PAT = Pattern.compile("\"volume\"\\s*:\\s*(\\d+)");
    private static final Pattern DATE_PAT = Pattern.compile("\"date\"\\s*:\\s*\"([^\"]+)\"");

    public double body() {
        return Math.abs(close - open);
    }

    public double range() {
        return Math.max(high - low, 0.001);
    }

    public double upperWick() {
        return high - Math.max(open, close);
    }

    public double lowerWick() {
        return Math.min(open, close) - low;
    }

    public double bodyPercentage() {
        return (body() / open) * 100.0;
    }

    public boolean isBullish() {
        return close > open;
    }

    public boolean isBearish() {
        return close < open;
    }

    public enum Type {
        BULLISH_MARUBOZU("Bullish Marubozu", "Pure buying power with virtually no wicks", true),
        BEARISH_MARUBOZU("Bearish Marubozu", "Pure selling pressure with virtually no wicks", false),
        HAMMER("Hammer", "Long lower wick rejecting lower prices at support", true),
        INVERTED_HAMMER("Inverted Hammer", "Long upper wick with buying push after downtrend", true),
        SHOOTING_STAR("Shooting Star", "Long upper wick rejecting higher prices at resistance", false),
        HANGING_MAN("Hanging Man", "Lower wick showing vulnerability at the top of an uptrend", false),
        DRAGONFLY_DOJI("Dragonfly Doji", "Open/High/Close at the extreme top, long lower shadow", true),
        GRAVESTONE_DOJI("Gravestone Doji", "Open/Low/Close at the extreme bottom, long upper shadow", false),
        LONG_LEGGED_DOJI("Long-Legged Doji", "Extreme indecision with very long shadows on both sides", false),
        STANDARD_DOJI("Standard Doji", "Perfect deadlock between buyers and sellers", false),
        BULLISH_SPINNING_TOP("Bullish Spinning Top", "Small green body centered with equal upper & lower wicks", true),
        BEARISH_SPINNING_TOP("Bearish Spinning Top", "Small red body centered with equal upper & lower wicks", false),
        HIGH_WAVE("High Wave Candle", "Tiny body with unusually massive upper & lower shadows", false),
        STRONG_BULLISH("Strong Bullish", "Solid green candle (>0.8% gain) with healthy wicks", true),
        STRONG_BEARISH("Strong Bearish", "Solid red candle (>0.8% loss) with healthy wicks", false),
        NORMAL_BULLISH("Normal Bullish", "Moderate green candle", true),
        NORMAL_BEARISH("Normal Bearish", "Moderate red candle", false);

        private final String displayName;
        private final String description;
        private final boolean isBullishBias;

        Type(String displayName, String description, boolean isBullishBias) {
            this.displayName = displayName;
            this.description = description;
            this.isBullishBias = isBullishBias;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public boolean isBullishBias() { return isBullishBias; }
    }

    /**
     * Classifies this candle into one of the 15+ standard single candlestick types.
     */
    public Type getCandleType() {
        double b = body();
        double r = range();
        double uw = upperWick();
        double lw = lowerWick();
        double bPct = bodyPercentage();

        // 1. Doji Family (Body is less than 10% of total candle range or < 0.1% price move)
        if (b <= 0.10 * r || bPct < 0.10) {
            if (lw >= 0.70 * r && uw <= 0.10 * r) {
                return Type.DRAGONFLY_DOJI;
            }
            if (uw >= 0.70 * r && lw <= 0.10 * r) {
                return Type.GRAVESTONE_DOJI;
            }
            if (uw >= 0.35 * r && lw >= 0.35 * r) {
                return Type.LONG_LEGGED_DOJI;
            }
            return Type.STANDARD_DOJI;
        }

        // 2. Marubozu Family (Body covers > 85% of total range, virtually no wicks)
        if (b >= 0.85 * r) {
            return isBullish() ? Type.BULLISH_MARUBOZU : Type.BEARISH_MARUBOZU;
        }

        // 3. Hammer & Hanging Man (Lower wick >= 2x body, upper wick <= 15% of body)
        if (lw >= 2.0 * b && uw <= 0.20 * b) {
            return isBullish() ? Type.HAMMER : Type.HANGING_MAN;
        }

        // 4. Inverted Hammer & Shooting Star (Upper wick >= 2x body, lower wick <= 15% of body)
        if (uw >= 2.0 * b && lw <= 0.20 * b) {
            return isBullish() ? Type.INVERTED_HAMMER : Type.SHOOTING_STAR;
        }

        // 5. Spinning Tops & High Wave (Small body, wicks on both sides)
        if (b <= 0.35 * r) {
            if (uw >= 0.40 * r && lw >= 0.40 * r) {
                return Type.HIGH_WAVE;
            }
            return isBullish() ? Type.BULLISH_SPINNING_TOP : Type.BEARISH_SPINNING_TOP;
        }

        // 6. Strong vs Normal Candles
        if (bPct >= 0.80) {
            return isBullish() ? Type.STRONG_BULLISH : Type.STRONG_BEARISH;
        }

        return isBullish() ? Type.NORMAL_BULLISH : Type.NORMAL_BEARISH;
    }

    /**
     * Compact character code for Trie sequence matching.
     */
    public char toToken() {
        return switch (getCandleType()) {
            case BULLISH_MARUBOZU, STRONG_BULLISH -> 'G';
            case BEARISH_MARUBOZU, STRONG_BEARISH -> 'R';
            case NORMAL_BULLISH -> 'g';
            case NORMAL_BEARISH -> 'r';
            case HAMMER -> 'H';
            case INVERTED_HAMMER -> 'I';
            case SHOOTING_STAR -> 'S';
            case HANGING_MAN -> 'M';
            case DRAGONFLY_DOJI -> 'Y';
            case GRAVESTONE_DOJI -> 'V';
            case LONG_LEGGED_DOJI, STANDARD_DOJI -> 'D';
            case BULLISH_SPINNING_TOP -> 'T';
            case BEARISH_SPINNING_TOP -> 't';
            case HIGH_WAVE -> 'W';
        };
    }

    /**
     * Parses all candles from a get_stock_history JSON response chunk.
     */
    public static List<Candle> parseFromHistoryJson(String json) {
        List<Candle> candles = new ArrayList<>();
        int dataIdx = json.indexOf("\"data\":");
        if (dataIdx == -1) return candles;

        int arrayStart = json.indexOf('[', dataIdx);
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart == -1 || arrayEnd == -1 || arrayStart >= arrayEnd) return candles;

        String content = json.substring(arrayStart + 1, arrayEnd);
        Matcher objMatcher = OBJ_PATTERN.matcher(content);

        while (objMatcher.find()) {
            String obj = objMatcher.group(1);
            try {
                Matcher mDate = DATE_PAT.matcher(obj);
                Matcher mOpen = OPEN_PAT.matcher(obj);
                Matcher mHigh = HIGH_PAT.matcher(obj);
                Matcher mLow = LOW_PAT.matcher(obj);
                Matcher mClose = CLOSE_PAT.matcher(obj);
                Matcher mVol = VOL_PAT.matcher(obj);

                if (mDate.find() && mOpen.find() && mHigh.find() && mLow.find() && mClose.find() && mVol.find()) {
                    candles.add(new Candle(
                            mDate.group(1),
                            Double.parseDouble(mOpen.group(1)),
                            Double.parseDouble(mHigh.group(1)),
                            Double.parseDouble(mLow.group(1)),
                            Double.parseDouble(mClose.group(1)),
                            Long.parseLong(mVol.group(1))
                    ));
                }
            } catch (Exception ignored) {
            }
        }
        return candles;
    }
}
