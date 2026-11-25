import java.util.ArrayList;
import java.util.List;

/**
 * Detects 15 Classic Multi-Candlestick Trading Patterns and computes
 * quantitative win-rates and 5-day / 10-day forward returns.
 */
public class CandlePatternDetector {

    public enum Bias { BULLISH, BEARISH }

    public record PatternSignal(
            String patternName,
            Bias bias,
            String date,
            int index,
            double triggerPrice,
            double forwardReturn5d,
            double forwardReturn10d
    ) {}

    public static List<PatternSignal> detectAll(List<Candle> candles) {
        List<PatternSignal> signals = new ArrayList<>();
        if (candles == null || candles.size() < 4) return signals;

        for (int i = 2; i < candles.size(); i++) {
            Candle c0 = candles.get(i);      // Current candle (completion)
            Candle c1 = candles.get(i - 1);  // 1 day ago
            Candle c2 = candles.get(i - 2);  // 2 days ago

            // 1. Bullish Engulfing: Red candle fully engulfed by a larger Green candle
            if (c1.isBearish() && c0.isBullish() &&
                c0.open() <= c1.close() && c0.close() >= c1.open() && c0.body() > c1.body() * 1.1) {
                signals.add(createSignal("Bullish Engulfing", Bias.BULLISH, c0, i, candles));
            }

            // 2. Bearish Engulfing: Green candle fully engulfed by a larger Red candle
            if (c1.isBullish() && c0.isBearish() &&
                c0.open() >= c1.close() && c0.close() <= c1.open() && c0.body() > c1.body() * 1.1) {
                signals.add(createSignal("Bearish Engulfing", Bias.BEARISH, c0, i, candles));
            }

            // 3. Morning Star: Bearish -> Doji / Tiny Star -> Bullish closing above midpoint of 1st
            double midpoint2 = (c2.open() + c2.close()) / 2.0;
            if (c2.isBearish() && c2.bodyPercentage() >= 0.5 &&
                c1.bodyPercentage() < 0.35 &&
                c0.isBullish() && c0.close() > midpoint2) {
                signals.add(createSignal("Morning Star", Bias.BULLISH, c0, i, candles));
            }

            // 4. Evening Star: Bullish -> Doji / Tiny Star -> Bearish closing below midpoint of 1st
            if (c2.isBullish() && c2.bodyPercentage() >= 0.5 &&
                c1.bodyPercentage() < 0.35 &&
                c0.isBearish() && c0.close() < midpoint2) {
                signals.add(createSignal("Evening Star", Bias.BEARISH, c0, i, candles));
            }

            // 5. Three White Soldiers: 3 consecutive strong green candles with higher closes
            if (c2.isBullish() && c1.isBullish() && c0.isBullish() &&
                c0.close() > c1.close() && c1.close() > c2.close() &&
                c0.bodyPercentage() >= 0.4 && c1.bodyPercentage() >= 0.4 && c2.bodyPercentage() >= 0.4) {
                signals.add(createSignal("Three White Soldiers", Bias.BULLISH, c0, i, candles));
            }

            // 6. Three Black Crows: 3 consecutive strong red candles with lower closes
            if (c2.isBearish() && c1.isBearish() && c0.isBearish() &&
                c0.close() < c1.close() && c1.close() < c2.close() &&
                c0.bodyPercentage() >= 0.4 && c1.bodyPercentage() >= 0.4 && c2.bodyPercentage() >= 0.4) {
                signals.add(createSignal("Three Black Crows", Bias.BEARISH, c0, i, candles));
            }

            // 7. Piercing Line: Strong Red, then Green opens lower and closes > 50% into Red's body
            double midpoint1 = (c1.open() + c1.close()) / 2.0;
            if (c1.isBearish() && c1.bodyPercentage() >= 0.5 &&
                c0.isBullish() && c0.open() < c1.close() && c0.close() > midpoint1 && c0.close() < c1.open()) {
                signals.add(createSignal("Piercing Line", Bias.BULLISH, c0, i, candles));
            }

            // 8. Dark Cloud Cover: Strong Green, then Red opens higher and closes > 50% into Green's body
            if (c1.isBullish() && c1.bodyPercentage() >= 0.5 &&
                c0.isBearish() && c0.open() > c1.close() && c0.close() < midpoint1 && c0.close() > c1.open()) {
                signals.add(createSignal("Dark Cloud Cover", Bias.BEARISH, c0, i, candles));
            }

            // 9. Bullish Harami (Inside Bar): Large Red followed by small Green inside its body
            if (c1.isBearish() && c0.isBullish() && c1.bodyPercentage() >= 0.6 &&
                c0.open() >= c1.close() && c0.close() <= c1.open() && c0.body() < c1.body() * 0.6) {
                signals.add(createSignal("Bullish Harami", Bias.BULLISH, c0, i, candles));
            }

            // 10. Bearish Harami: Large Green followed by small Red inside its body
            if (c1.isBullish() && c0.isBearish() && c1.bodyPercentage() >= 0.6 &&
                c0.open() <= c1.close() && c0.close() >= c1.open() && c0.body() < c1.body() * 0.6) {
                signals.add(createSignal("Bearish Harami", Bias.BEARISH, c0, i, candles));
            }

            // 11. Tweezer Bottom: Matching lows within 0.15% (Double-bottom bounce on consecutive candles)
            if (Math.abs(c0.low() - c1.low()) / c1.low() < 0.0015 && c1.isBearish() && c0.isBullish()) {
                signals.add(createSignal("Tweezer Bottom", Bias.BULLISH, c0, i, candles));
            }

            // 12. Tweezer Top: Matching highs within 0.15% (Double-top rejection on consecutive candles)
            if (Math.abs(c0.high() - c1.high()) / c1.high() < 0.0015 && c1.isBullish() && c0.isBearish()) {
                signals.add(createSignal("Tweezer Top", Bias.BEARISH, c0, i, candles));
            }

            // 13. Three Inside Up: Bullish Harami confirmed by 3rd candle closing above 1st candle's open
            if (c2.isBearish() && c1.isBullish() && c1.open() >= c2.close() && c1.close() <= c2.open() &&
                c0.isBullish() && c0.close() > c2.open()) {
                signals.add(createSignal("Three Inside Up", Bias.BULLISH, c0, i, candles));
            }

            // 14. Three Inside Down: Bearish Harami confirmed by 3rd candle closing below 1st candle's open
            if (c2.isBullish() && c1.isBearish() && c1.open() <= c2.close() && c1.close() >= c2.open() &&
                c0.isBearish() && c0.close() < c2.open()) {
                signals.add(createSignal("Three Inside Down", Bias.BEARISH, c0, i, candles));
            }

            // 15. Hammer Reversal: Hammer on c1 with strong green confirmation on c0
            if (c1.getCandleType() == Candle.Type.HAMMER && c0.isBullish() && c0.close() > c1.high()) {
                signals.add(createSignal("Hammer Confirmation", Bias.BULLISH, c0, i, candles));
            }
        }

        return signals;
    }

    private static PatternSignal createSignal(String name, Bias bias, Candle candle, int index, List<Candle> all) {
        double entry = candle.close();

        // 5-day return
        double r5 = 0.0;
        int idx5 = index + 5;
        if (idx5 < all.size()) {
            r5 = ((all.get(idx5).close() - entry) / entry) * 100.0;
        }

        // 10-day return
        double r10 = 0.0;
        int idx10 = index + 10;
        if (idx10 < all.size()) {
            r10 = ((all.get(idx10).close() - entry) / entry) * 100.0;
        }

        return new PatternSignal(name, bias, candle.date(), index, entry, r5, r10);
    }
}

