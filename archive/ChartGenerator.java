import java.awt.Desktop;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates an interactive TradingView-style Candlestick chart in HTML
 * with Volume, 20/50/200 SMA overlays, and Algorithmic Pattern Markers.
 */
public class ChartGenerator {

    public record ChartMarker(String date, String label, boolean isBullish) {}

    public static File createChart(String symbol, List<Candle> rawCandles) throws IOException {
        return createChart(symbol, rawCandles, List.of());
    }

    public static File createChart(String symbol, List<Candle> rawCandles, List<ChartMarker> markers) throws IOException {
        // Sort chronologically (oldest to newest)
        List<Candle> candles = new ArrayList<>(rawCandles);
        candles.sort(Comparator.comparing(Candle::date));

        // Build Candlestick Data & Volume Data
        StringBuilder candleJson = new StringBuilder("[");
        StringBuilder volumeJson = new StringBuilder("[");
        StringBuilder sma20Json = new StringBuilder("[");
        StringBuilder sma50Json = new StringBuilder("[");
        StringBuilder sma200Json = new StringBuilder("[");

        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);

            candleJson.append(String.format("{\"time\":\"%s\",\"open\":%.2f,\"high\":%.2f,\"low\":%.2f,\"close\":%.2f},",
                    c.date(), c.open(), c.high(), c.low(), c.close()));

            String volColor = c.close() >= c.open() ? "rgba(38,166,154,0.5)" : "rgba(239,83,80,0.5)";
            volumeJson.append(String.format("{\"time\":\"%s\",\"value\":%d,\"color\":\"%s\"},",
                    c.date(), c.volume(), volColor));

            if (i >= 19) {
                double sma = calculateSma(candles, i, 20);
                sma20Json.append(String.format("{\"time\":\"%s\",\"value\":%.2f},", c.date(), sma));
            }

            if (i >= 49) {
                double sma = calculateSma(candles, i, 50);
                sma50Json.append(String.format("{\"time\":\"%s\",\"value\":%.2f},", c.date(), sma));
            }

            if (i >= 199) {
                double sma = calculateSma(candles, i, 200);
                sma200Json.append(String.format("{\"time\":\"%s\",\"value\":%.2f},", c.date(), sma));
            }
        }

        closeJsonArray(candleJson);
        closeJsonArray(volumeJson);
        closeJsonArray(sma20Json);
        closeJsonArray(sma50Json);
        closeJsonArray(sma200Json);

        // Build Markers JSON for detected patterns
        StringBuilder markersJson = new StringBuilder("[");
        if (markers != null) {
            for (ChartMarker m : markers) {
                String pos = m.isBullish() ? "belowBar" : "aboveBar";
                String color = m.isBullish() ? "#26a69a" : "#ef5350";
                String shape = m.isBullish() ? "arrowUp" : "arrowDown";
                markersJson.append(String.format("{\"time\":\"%s\",\"position\":\"%s\",\"color\":\"%s\",\"shape\":\"%s\",\"text\":\"%s\"},",
                        m.date(), pos, color, shape, m.label()));
            }
        }
        closeJsonArray(markersJson);

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>%s - Candlestick Chart with Pattern Matching</title>
                <script src="https://unpkg.com/lightweight-charts@4.1.3/dist/lightweight-charts.standalone.production.js"></script>
                <style>
                    body {
                        margin: 0;
                        padding: 16px;
                        background-color: #131722;
                        color: #d1d4dc;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    }
                    .header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 8px;
                    }
                    h1 { margin: 0; font-size: 20px; font-weight: 600; color: #f0f3fa; }
                    .legend {
                        display: flex;
                        gap: 16px;
                        font-size: 13px;
                    }
                    .badge { font-weight: 600; }
                    .sma20 { color: #2962ff; }
                    .sma50 { color: #ff9800; }
                    .sma200 { color: #e91e63; }
                    .pattern { color: #ffeb3b; }
                    #chart-container {
                        width: 100%%;
                        height: 750px;
                        position: relative;
                    }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>%s &mdash; NSE Daily Candles (%d bars) with Candlestick Pattern Signals</h1>
                    <div class="legend">
                        <span class="badge sma20">&mdash; SMA 20</span>
                        <span class="badge sma50">&mdash; SMA 50</span>
                        <span class="badge sma200">&mdash; SMA 200</span>
                        <span class="badge pattern">&#9650; Pattern Matches</span>
                    </div>
                </div>
                <div id="chart-container"></div>

                <script>
                    const chart = LightweightCharts.createChart(document.getElementById('chart-container'), {
                        layout: {
                            background: { color: '#131722' },
                            textColor: '#d1d4dc'
                        },
                        grid: {
                            vertLines: { color: '#1e222d' },
                            horzLines: { color: '#1e222d' }
                        },
                        crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                        rightPriceScale: { borderColor: '#2b2f3a' },
                        timeScale: { borderColor: '#2b2f3a', timeVisible: false }
                    });

                    // 1. Candlestick Series
                    const candleSeries = chart.addCandlestickSeries({
                        upColor: '#26a69a',
                        downColor: '#ef5350',
                        borderVisible: false,
                        wickUpColor: '#26a69a',
                        wickDownColor: '#ef5350'
                    });
                    candleSeries.setData(%s);

                    // 2. Pattern Markers on Candlesticks
                    candleSeries.setMarkers(%s);

                    // 3. Volume Series
                    const volumeSeries = chart.addHistogramSeries({
                        priceFormat: { type: 'volume' },
                        priceScaleId: '',
                        scaleMargins: { top: 0.8, bottom: 0 }
                    });
                    volumeSeries.setData(%s);

                    // 4. SMA Lines
                    const sma20Series = chart.addLineSeries({ color: '#2962ff', lineWidth: 2, title: 'SMA 20' });
                    sma20Series.setData(%s);

                    const sma50Series = chart.addLineSeries({ color: '#ff9800', lineWidth: 2, title: 'SMA 50' });
                    sma50Series.setData(%s);

                    const sma200Series = chart.addLineSeries({ color: '#e91e63', lineWidth: 2, title: 'SMA 200' });
                    sma200Series.setData(%s);

                    chart.timeScale().fitContent();

                    window.addEventListener('resize', () => {
                        chart.applyOptions({ width: document.getElementById('chart-container').clientWidth });
                    });
                </script>
            </body>
            </html>
            """,
                symbol, symbol, candles.size(),
                candleJson.toString(),
                markersJson.toString(),
                volumeJson.toString(),
                sma20Json.toString(),
                sma50Json.toString(),
                sma200Json.toString()
        );

        File outputFile = new File(symbol.toLowerCase() + "_chart.html");
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(html);
        }

        return outputFile;
    }

    private static double calculateSma(List<Candle> candles, int endIndex, int period) {
        double sum = 0.0;
        for (int j = endIndex - period + 1; j <= endIndex; j++) {
            sum += candles.get(j).close();
        }
        return sum / period;
    }

    private static void closeJsonArray(StringBuilder sb) {
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') {
            sb.setCharAt(sb.length() - 1, ']');
        } else {
            sb.append(']');
        }
    }

    public static void openInBrowser(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(file.toURI());
                System.out.println("Chart opened in browser: " + file.getAbsolutePath());
            } else {
                System.out.println("Chart saved to: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            System.out.println("Chart saved to: " + file.getAbsolutePath() + " (could not open browser automatically)");
        }
    }
}
