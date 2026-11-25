package algo.priority;

import algo.priority.NewsModels.FeedFetchStatus;
import algo.priority.NewsModels.RawNewsItem;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concurrent Pure-Java RSS/Atom Aggregator & Deduplicator for Crypto Market News.
 *
 * Fetches 6 verified crypto editorial RSS feeds plus optional coin-targeted Google News RSS queries
 * in parallel via CompletableFuture with strict per-request timeouts and a 120s in-memory cache.
 */
public class RssFeedFetcher {

    private static final long CACHE_TTL_MS = 120_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");

    private record FeedSource(String name, String url, double credibilityScore) {}

    private record CachedFeed(List<RawNewsItem> items, long fetchedAtMs) {}

    public record FetchBundle(List<RawNewsItem> items, List<FeedFetchStatus> statuses) {}

    private static final List<FeedSource> DIRECT_FEEDS = List.of(
            new FeedSource("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/", 25.0),
            new FeedSource("The Block", "https://www.theblock.co/rss.xml", 25.0),
            new FeedSource("Cointelegraph", "https://cointelegraph.com/rss", 21.0),
            new FeedSource("Decrypt", "https://decrypt.co/feed", 20.0),
            new FeedSource("CryptoSlate", "https://cryptoslate.com/feed/", 19.0),
            new FeedSource("Bitcoin Magazine", "https://bitcoinmagazine.com/feed", 19.0)
    );

    private static final Map<String, String> GOOGLE_NEWS_COIN_QUERIES = Map.ofEntries(
            Map.entry("BTC", "Bitcoin OR BTC crypto"),
            Map.entry("ETH", "Ethereum OR ETH crypto"),
            Map.entry("SOL", "Solana OR SOL crypto"),
            Map.entry("BNB", "\"BNB Chain\" OR \"Binance Coin\" OR BNB crypto"),
            Map.entry("XRP", "Ripple OR XRP crypto"),
            Map.entry("DOGE", "Dogecoin OR DOGE crypto"),
            Map.entry("SUI", "\"Sui Network\" OR \"SUI crypto\" OR Mysten"),
            Map.entry("NEAR", "\"NEAR Protocol\" OR \"NEAR crypto\""),
            Map.entry("PEPE", "Pepe OR PEPE memecoin crypto"),
            Map.entry("AVAX", "Avalanche OR AVAX crypto"),
            Map.entry("TRX", "TRON OR TRX crypto"),
            Map.entry("LINK", "Chainlink OR \"LINK crypto\""),
            Map.entry("ADA", "Cardano OR ADA crypto"),
            Map.entry("SHIB", "\"Shiba Inu\" OR SHIB crypto")
    );

    private final HttpClient httpClient;
    private final Map<String, CachedFeed> feedCache = new ConcurrentHashMap<>();

    public RssFeedFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Fetches configured RSS feeds concurrently, merges, and deduplicates news items.
     */
    public FetchBundle fetchAll(String focusCoin, boolean includeGoogleNews, boolean forceRefresh) {
        List<FeedSource> targets = new ArrayList<>(DIRECT_FEEDS);
        if (includeGoogleNews) {
            String cleanFocus = CryptoKeywordAnalyzer.normalizeSymbol(focusCoin);
            if (!cleanFocus.isEmpty() && !"ALL".equals(cleanFocus)) {
                String q = GOOGLE_NEWS_COIN_QUERIES.getOrDefault(cleanFocus, cleanFocus + " cryptocurrency");
                targets.add(new FeedSource("Google News (" + cleanFocus + ")", buildGoogleNewsUrl(q), 14.0));
            } else {
                targets.add(new FeedSource("Google News (Majors)", buildGoogleNewsUrl("Solana OR XRP OR BNB OR Dogecoin crypto"), 14.0));
                targets.add(new FeedSource("Google News (Sui/NEAR)", buildGoogleNewsUrl("\"Sui Network\" OR \"Sui crypto\" OR \"NEAR Protocol\" OR Mysten crypto"), 14.0));
                targets.add(new FeedSource("Google News (AVAX/LINK)", buildGoogleNewsUrl("Avalanche OR AVAX OR Chainlink crypto"), 14.0));
                targets.add(new FeedSource("Google News (Alts/Meme)", buildGoogleNewsUrl("Cardano OR \"Shiba Inu\" OR PEPE OR TRON crypto"), 14.0));
            }
        }

        List<CompletableFuture<FeedResult>> futures = targets.stream()
                .map(src -> CompletableFuture.supplyAsync(() -> fetchSingleFeed(src, forceRefresh)))
                .toList();

        List<RawNewsItem> merged = new ArrayList<>();
        List<FeedFetchStatus> statuses = new ArrayList<>();
        for (CompletableFuture<FeedResult> f : futures) {
            FeedResult r = f.join();
            statuses.add(r.status());
            merged.addAll(r.items());
        }

        List<RawNewsItem> deduped = deduplicate(merged);
        return new FetchBundle(deduped, statuses);
    }

    private record FeedResult(List<RawNewsItem> items, FeedFetchStatus status) {}

    private FeedResult fetchSingleFeed(FeedSource source, boolean forceRefresh) {
        long t0 = System.currentTimeMillis();
        long now = t0;
        CachedFeed cached = feedCache.get(source.url());
        if (!forceRefresh && cached != null && (now - cached.fetchedAtMs()) < CACHE_TTL_MS) {
            return new FeedResult(
                    cached.items(),
                    new FeedFetchStatus(source.name(), source.url(), "CACHED", cached.items().size(), System.currentTimeMillis() - t0)
            );
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(source.url()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (compatible; MarketCentsRSS/1.0)")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null || resp.body().isBlank()) {
                if (cached != null) {
                    return new FeedResult(cached.items(), new FeedFetchStatus(source.name(), source.url(), "STALE_CACHE", cached.items().size(), System.currentTimeMillis() - t0));
                }
                return new FeedResult(List.of(), new FeedFetchStatus(source.name(), source.url(), "HTTP_" + resp.statusCode(), 0, System.currentTimeMillis() - t0));
            }

            List<RawNewsItem> parsed = parseRssXml(resp.body(), source.name(), source.credibilityScore());
            feedCache.put(source.url(), new CachedFeed(parsed, System.currentTimeMillis()));
            return new FeedResult(
                    parsed,
                    new FeedFetchStatus(source.name(), source.url(), "OK", parsed.size(), System.currentTimeMillis() - t0)
            );
        } catch (Exception ex) {
            if (cached != null) {
                return new FeedResult(cached.items(), new FeedFetchStatus(source.name(), source.url(), "STALE_CACHE", cached.items().size(), System.currentTimeMillis() - t0));
            }
            return new FeedResult(List.of(), new FeedFetchStatus(source.name(), source.url(), "ERROR", 0, System.currentTimeMillis() - t0));
        }
    }

    /**
     * Parses RSS 2.0 (<item>) or Atom (<entry>) XML safely with XXE protections enabled.
     */
    public static List<RawNewsItem> parseRssXml(String xml, String defaultSourceName, double defaultCredibility) throws Exception {
        if (xml == null || xml.isBlank()) return List.of();
        String trimmed = xml.trim();
        int xmlStart = trimmed.indexOf('<');
        if (xmlStart > 0) trimmed = trimmed.substring(xmlStart);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(trimmed)));

        List<RawNewsItem> results = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        if (items.getLength() == 0) {
            items = doc.getElementsByTagName("entry");
        }

        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            if (!(n instanceof Element el)) continue;

            String rawTitle = childText(el, "title");
            String link = extractLink(el);
            String rawDesc = childText(el, "description");
            if (rawDesc.isEmpty()) rawDesc = childText(el, "summary");
            if (rawDesc.isEmpty()) rawDesc = childText(el, "content");

            String pubDateStr = childText(el, "pubDate");
            if (pubDateStr.isEmpty()) pubDateStr = childText(el, "published");
            if (pubDateStr.isEmpty()) pubDateStr = childText(el, "updated");

            // Google News embeds "<source>Publisher</source>" and appends " - Publisher" to title
            String embeddedSource = childText(el, "source");
            String sourceName = defaultSourceName;
            String title = cleanText(rawTitle);
            if (!embeddedSource.isEmpty() && defaultSourceName.startsWith("Google News")) {
                sourceName = embeddedSource + " (via Google News)";
                String suffix = " - " + embeddedSource;
                if (title.endsWith(suffix)) {
                    title = title.substring(0, title.length() - suffix.length()).trim();
                }
            }

            if (title.isEmpty() || link.isEmpty()) continue;

            String imageUrl = extractImageUrl(el, rawDesc);
            String summary = truncateSummary(cleanText(rawDesc), 260);
            long epochSec = parseEpochSeconds(pubDateStr);
            List<String> categories = collectCategories(el);
            String id = stableHashId(link, title);

            results.add(new RawNewsItem(
                    id,
                    title,
                    summary,
                    canonicalizeUrl(link),
                    sourceName,
                    defaultCredibility,
                    imageUrl,
                    epochSec,
                    categories
            ));
        }

        return results;
    }

    private static List<RawNewsItem> deduplicate(List<RawNewsItem> all) {
        Map<String, RawNewsItem> byUrl = new LinkedHashMap<>();
        Set<String> seenHeadlines = new HashSet<>();

        for (RawNewsItem item : all) {
            String normHeadline = item.title().toLowerCase().replaceAll("[^a-z0-9]", "");
            if (normHeadline.length() > 60) normHeadline = normHeadline.substring(0, 60);

            String key = item.link();
            if (byUrl.containsKey(key) || seenHeadlines.contains(normHeadline)) {
                continue;
            }
            byUrl.put(key, item);
            seenHeadlines.add(normHeadline);
        }
        return new ArrayList<>(byUrl.values());
    }

    private static String buildGoogleNewsUrl(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return "https://news.google.com/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en";
    }

    private static String childText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element ce) {
                String name = ce.getLocalName() != null ? ce.getLocalName() : ce.getTagName();
                if (localName.equalsIgnoreCase(name)) {
                    return ce.getTextContent() == null ? "" : ce.getTextContent().trim();
                }
            }
        }
        return "";
    }

    private static String extractLink(Element el) {
        String textLink = childText(el, "link");
        if (!textLink.isEmpty()) return textLink;
        NodeList links = el.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            if (links.item(i) instanceof Element le && le.hasAttribute("href")) {
                return le.getAttribute("href").trim();
            }
        }
        return childText(el, "guid");
    }

    private static String extractImageUrl(Element el, String rawDesc) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element ce) {
                String name = ce.getLocalName() != null ? ce.getLocalName() : ce.getTagName();
                if (("content".equalsIgnoreCase(name) || "thumbnail".equalsIgnoreCase(name) || "enclosure".equalsIgnoreCase(name))
                        && ce.hasAttribute("url")) {
                    String url = ce.getAttribute("url").trim();
                    if (url.startsWith("http")) return url;
                }
            }
        }
        if (rawDesc != null && !rawDesc.isEmpty()) {
            Matcher m = IMG_SRC_PATTERN.matcher(rawDesc);
            if (m.find()) {
                String candidate = m.group(1).trim();
                if (candidate.startsWith("http")) return candidate;
            }
        }
        return "";
    }

    private static List<String> collectCategories(Element el) {
        List<String> cats = new ArrayList<>();
        NodeList nodes = el.getElementsByTagName("category");
        for (int i = 0; i < nodes.getLength(); i++) {
            String txt = nodes.item(i).getTextContent();
            if (txt != null && !txt.isBlank()) cats.add(txt.trim());
        }
        return cats;
    }

    private static long parseEpochSeconds(String raw) {
        if (raw == null || raw.isBlank()) return System.currentTimeMillis() / 1000L;
        String cleaned = raw.trim();
        try {
            return ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        } catch (Exception ignored) {}
        try {
            return OffsetDateTime.parse(cleaned).toEpochSecond();
        } catch (Exception ignored) {}
        try {
            return Instant.parse(cleaned).getEpochSecond();
        } catch (Exception ignored) {}
        return System.currentTimeMillis() / 1000L;
    }

    private static String cleanText(String input) {
        if (input == null || input.isEmpty()) return "";
        String noTags = HTML_TAG_PATTERN.matcher(input).replaceAll(" ");
        return noTags
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&#8217;", "'")
                .replace("&#8216;", "'")
                .replace("&#8220;", "\"")
                .replace("&#8221;", "\"")
                .replace("&#8211;", "-")
                .replace("&#8212;", "—")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncateSummary(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        int cut = text.lastIndexOf(' ', maxLen);
        if (cut < maxLen / 2) cut = maxLen;
        return text.substring(0, cut).trim() + "...";
    }

    private static String canonicalizeUrl(String url) {
        int qIdx = url.indexOf("?utm_");
        if (qIdx > 0) return url.substring(0, qIdx);
        return url;
    }

    private static String stableHashId(String link, String title) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((link + "|" + title).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (Exception ex) {
            return Integer.toHexString((link + title).hashCode());
        }
    }
}
