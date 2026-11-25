import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Order Flow Network Engine: Max-Flow / Min-Cut Formulation of CVD and Order Book Depth.
 * 
 * Conceptual Model:
 * 1. Sources (S):
 *    - S_Buy: Aggressive market taker buy volume and positive Cumulative Volume Delta (CVD) rate.
 *    - S_Sell: Aggressive market taker sell volume and negative CVD rate.
 * 
 * 2. Connecting Nodes (V):
 *    - Order book depth levels stratified into Ask Tiers (A1..Ak) and Bid Tiers (B1..Bk).
 *    - Each tier node has a flow capacity C(u, v) equal to resting limit liquidity at that price.
 *    - Edges model flow penetration and slippage coupling across adjacent book tiers.
 * 
 * 3. The Bottleneck (Min-Cut):
 *    - By the Max-Flow Min-Cut theorem, the maximum aggressive flow that can push price
 *      is bounded by the saturated capacity of the minimum cut.
 *    - If C_mincut > Incoming Flow: Flow is trapped/absorbed -> Absorption Wall Reversal.
 *    - If Incoming Flow >= C_mincut: Wall breached -> Liquidity Vacuum Breakout.
 * 
 * 4. The Dynamic Sink (T):
 *    - The sink represents the final directional target and state.
 *    - It dynamically shifts its price target, direction, and intensity based on the
 *      unabsorbed residual flow pressure:
 *      * BULLISH_BREAKOUT_SINK: Asks punctured, upward vacuum.
 *      * BEARISH_BREAKDOWN_SINK: Bids punctured, downward cascade.
 *      * ABSORPTION_REVERSAL_DOWN: Buys absorbed at heavy ask wall.
 *      * ABSORPTION_REVERSAL_UP: Sells absorbed at heavy bid wall.
 *      * EQUILIBRIUM_PIN: Balanced flows fully dampened.
 */
public class OrderFlowNetwork {

    public enum NodeType {
        SOURCE_BUY,
        SOURCE_SELL,
        ASK_TIER,
        BID_TIER,
        INTERMEDIATE_ROUTER,
        DYNAMIC_SINK
    }

    public enum DirectionType {
        BULLISH_BREAKOUT("EXPONENTIAL BULLISH BREAKOUT", "Ask depth saturated, unabsorbed buy flow pushing into liquidity vacuum", +1),
        BEARISH_BREAKDOWN("BEARISH CASCADE BREAKDOWN", "Bid depth saturated, unabsorbed sell flow cascading into liquidity vacuum", -1),
        ABSORPTION_REVERSAL_BEAR("ABSORPTION REVERSAL (BEARISH)", "Aggressive buy flow completely absorbed by passive Ask wall. Reversal down expected.", -1),
        ABSORPTION_REVERSAL_BULL("ABSORPTION REVERSAL (BULLISH)", "Aggressive sell flow completely absorbed by passive Bid wall. Reversal up expected.", +1),
        EQUILIBRIUM_PIN("EQUILIBRIUM PIN / CHOP", "Order flow balanced across bid/ask network. Price pinned in range.", 0);

        public final String title;
        public final String description;
        public final int bias;

        DirectionType(String title, String description, int bias) {
            this.title = title;
            this.description = description;
            this.bias = bias;
        }
    }

    public static class FlowEdge {
        public final int from;
        public final int to;
        public final double capacity;
        public double flow;
        public FlowEdge residual;

        public FlowEdge(int from, int to, double capacity) {
            this.from = from;
            this.to = to;
            this.capacity = capacity;
            this.flow = 0.0;
        }

        public double remainingCapacity() {
            return capacity - flow;
        }
    }

    public static class NetworkNode {
        public final int id;
        public final String label;
        public final NodeType type;
        public final double priceLevel;
        public final double baseLiquidity;

        public NetworkNode(int id, String label, NodeType type, double priceLevel, double baseLiquidity) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.priceLevel = priceLevel;
            this.baseLiquidity = baseLiquidity;
        }
    }

    public record BookLevel(double price, double quantity) {}

    public record MinCutBottleneck(
            String wallName,
            double priceLevel,
            double saturatedCapacity,
            double flowPushed,
            boolean isBreached
    ) {}

    public record DynamicSinkResolution(
            DirectionType directionType,
            double netSinkPressure,       // Positive = Bullish pressure, Negative = Bearish pressure
            double targetPrice,           // Dynamic target price pulled by sink
            double targetReturnPct,       // Expected % move to sink
            double maxBuyFlow,            // Maximum flow passed through asks
            double maxSellFlow,           // Maximum flow passed through bids
            double totalBuySupply,        // Total aggressive buy flow injected
            double totalSellSupply,       // Total aggressive sell flow injected
            MinCutBottleneck primaryBottleneck,
            List<String> networkTelemetry
    ) {}

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Solves the Dynamic Order Flow Network using live Order Book Depth and CVD aggTrades.
     */
    public DynamicSinkResolution solveNetwork(
            double currentMidPrice,
            double takerBuyVol,
            double takerSellVol,
            List<BookLevel> bids,
            List<BookLevel> asks
    ) {
        // Build graph nodes
        // 0: S_BUY
        // 1: S_SELL
        // 2..K+1: Ask tiers (A1..Ak)
        // K+2..2K+1: Bid tiers (B1..Bk)
        // 2K+2: DYNAMIC_SINK
        int kAsks = Math.min(asks.size(), 8);
        int kBids = Math.min(bids.size(), 8);

        int buySourceId = 0;
        int sellSourceId = 1;
        int sinkId = 2 + kAsks + kBids;

        List<NetworkNode> nodes = new ArrayList<>();
        nodes.add(new NetworkNode(buySourceId, "SOURCE_BUY (CVD Taker Flow)", NodeType.SOURCE_BUY, currentMidPrice, takerBuyVol));
        nodes.add(new NetworkNode(sellSourceId, "SOURCE_SELL (CVD Taker Flow)", NodeType.SOURCE_SELL, currentMidPrice, takerSellVol));

        int nextId = 2;
        int firstAskId = nextId;
        for (int i = 0; i < kAsks; i++) {
            BookLevel al = asks.get(i);
            nodes.add(new NetworkNode(nextId++, String.format("ASK_A%d ($%.2f)", i + 1, al.price()), NodeType.ASK_TIER, al.price(), al.quantity()));
        }

        int firstBidId = nextId;
        for (int i = 0; i < kBids; i++) {
            BookLevel bl = bids.get(i);
            nodes.add(new NetworkNode(nextId++, String.format("BID_B%d ($%.2f)", i + 1, bl.price()), NodeType.BID_TIER, bl.price(), bl.quantity()));
        }

        nodes.add(new NetworkNode(sinkId, "DYNAMIC_SINK", NodeType.DYNAMIC_SINK, currentMidPrice, 0.0));

        // 1. Solve Buy Flow Sub-Network (S_Buy -> Ask Tiers -> Dynamic Sink)
        MaxFlowSolver buySolver = new MaxFlowSolver(sinkId + 1);
        // Connect Source Buy to first Ask tier
        buySolver.addEdge(buySourceId, firstAskId, takerBuyVol);

        // Chain ask tiers with cascading liquidity coupling
        for (int i = 0; i < kAsks; i++) {
            int currNode = firstAskId + i;
            double tierDepth = asks.get(i).quantity();
            // Edge to next deeper ask tier (overflow if current tier is eaten)
            if (i < kAsks - 1) {
                int nextNode = currNode + 1;
                buySolver.addEdge(currNode, nextNode, tierDepth * 1.5);
            }
            // Edge from ask tier to Dynamic Sink (breach through resistance)
            double resistancePenetrationCapacity = tierDepth;
            buySolver.addEdge(currNode, sinkId, resistancePenetrationCapacity);
        }

        double maxBuyFlowThroughAsks = buySolver.edmondsKarp(buySourceId, sinkId);
        boolean[] buyCut = buySolver.getMinCut(buySourceId);

        // 2. Solve Sell Flow Sub-Network (S_Sell -> Bid Tiers -> Dynamic Sink)
        MaxFlowSolver sellSolver = new MaxFlowSolver(sinkId + 1);
        sellSolver.addEdge(sellSourceId, firstBidId, takerSellVol);

        for (int i = 0; i < kBids; i++) {
            int currNode = firstBidId + i;
            double tierDepth = bids.get(i).quantity();
            if (i < kBids - 1) {
                int nextNode = currNode + 1;
                sellSolver.addEdge(currNode, nextNode, tierDepth * 1.5);
            }
            double supportPenetrationCapacity = tierDepth;
            sellSolver.addEdge(currNode, sinkId, supportPenetrationCapacity);
        }

        double maxSellFlowThroughBids = sellSolver.edmondsKarp(sellSourceId, sinkId);
        boolean[] sellCut = sellSolver.getMinCut(sellSourceId);

        // 3. Find Primary Min-Cut Bottleneck
        MinCutBottleneck bottleneck = null;
        // Check Ask side cut (where cut edges cross from reachable to non-reachable)
        for (int i = 0; i < kAsks; i++) {
            int u = firstAskId + i;
            if (buyCut[u] && (u + 1 >= firstAskId + kAsks || !buyCut[u + 1])) {
                BookLevel al = asks.get(i);
                boolean breached = takerBuyVol > al.quantity();
                bottleneck = new MinCutBottleneck(String.format("Ask Wall A%d", i + 1), al.price(), al.quantity(), maxBuyFlowThroughAsks, breached);
                break;
            }
        }
        if (bottleneck == null && !asks.isEmpty()) {
            BookLevel al = asks.get(0);
            bottleneck = new MinCutBottleneck("Ask Wall A1", al.price(), al.quantity(), maxBuyFlowThroughAsks, takerBuyVol > al.quantity());
        }

        // 4. Determine Dynamic Sink State & Potential
        // Net terminal pressure arriving at the sink
        double unabsorbedBuyPressure = Math.max(0, takerBuyVol - maxBuyFlowThroughAsks);
        double unabsorbedSellPressure = Math.max(0, takerSellVol - maxSellFlowThroughBids);
        double netSinkPressure = maxBuyFlowThroughAsks - maxSellFlowThroughBids;

        double cvdDelta = takerBuyVol - takerSellVol;
        double cvdRatio = (takerBuyVol + takerSellVol) > 0 ? (takerBuyVol / (takerBuyVol + takerSellVol)) : 0.5;

        DirectionType directionType;
        double targetPrice = currentMidPrice;
        double targetReturnPct = 0.0;

        List<String> telemetry = new ArrayList<>();
        telemetry.add(String.format(Locale.US, "S_Buy Taker Flow: %.3f | S_Sell Taker Flow: %.3f (CVD Delta: %+.3f)", takerBuyVol, takerSellVol, cvdDelta));
        telemetry.add(String.format(Locale.US, "Max Flow through Ask Resistance: %.3f / %.3f (Saturation: %.1f%%)",
                maxBuyFlowThroughAsks, takerBuyVol, takerBuyVol > 0 ? (maxBuyFlowThroughAsks / takerBuyVol) * 100.0 : 0.0));
        telemetry.add(String.format(Locale.US, "Max Flow through Bid Support: %.3f / %.3f (Saturation: %.1f%%)",
                maxSellFlowThroughBids, takerSellVol, takerSellVol > 0 ? (maxSellFlowThroughBids / takerSellVol) * 100.0 : 0.0));

        // Classification Rules for Dynamic Sink
        if (takerBuyVol > 1.25 * takerSellVol && maxBuyFlowThroughAsks >= asks.get(0).quantity() * 0.85) {
            // Aggressive buy volume is puncturing Ask Wall -> BULLISH BREAKOUT
            directionType = DirectionType.BULLISH_BREAKOUT;
            int targetIdx = Math.min(kAsks - 1, 2);
            targetPrice = asks.get(targetIdx).price() * 1.001;
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Punctured Ask liquidity. Vacuum pulling price to $%.2f (%+.2f%%)", targetPrice, targetReturnPct));

        } else if (takerSellVol > 1.25 * takerBuyVol && maxSellFlowThroughBids >= bids.get(0).quantity() * 0.85) {
            // Aggressive sell volume is puncturing Bid Wall -> BEARISH BREAKDOWN
            directionType = DirectionType.BEARISH_BREAKDOWN;
            int targetIdx = Math.min(kBids - 1, 2);
            targetPrice = bids.get(targetIdx).price() * 0.999;
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Punctured Bid liquidity. Cascade pulling price to $%.2f (%+.2f%%)", targetPrice, targetReturnPct));

        } else if (takerBuyVol > takerSellVol && maxBuyFlowThroughAsks < asks.get(0).quantity() * 0.40) {
            // Aggressive buyers tried to push, but were completely absorbed by thick Ask Wall -> ABSORPTION REVERSAL DOWN
            directionType = DirectionType.ABSORPTION_REVERSAL_BEAR;
            targetPrice = bids.get(Math.min(kBids - 1, 1)).price();
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Heavy Ask Wall absorbed buy pressure. Rejection target: $%.2f (%+.2f%%)", targetPrice, targetReturnPct));

        } else if (takerSellVol > takerBuyVol && maxSellFlowThroughBids < bids.get(0).quantity() * 0.40) {
            // Aggressive sellers tried to dump, but were absorbed by massive Bid Wall -> ABSORPTION REVERSAL UP
            directionType = DirectionType.ABSORPTION_REVERSAL_BULL;
            targetPrice = asks.get(Math.min(kAsks - 1, 1)).price();
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Heavy Bid Wall absorbed sell pressure. Bounce target: $%.2f (%+.2f%%)", targetPrice, targetReturnPct));

        } else {
            // Flow balanced or absorbed within tight spread -> EQUILIBRIUM
            directionType = DirectionType.EQUILIBRIUM_PIN;
            targetPrice = currentMidPrice;
            targetReturnPct = 0.0;
            telemetry.add("Dynamic Sink: Balanced multi-tier flow. Price pinned at equilibrium.");
        }

        return new DynamicSinkResolution(
                directionType,
                netSinkPressure,
                targetPrice,
                targetReturnPct,
                maxBuyFlowThroughAsks,
                maxSellFlowThroughBids,
                takerBuyVol,
                takerSellVol,
                bottleneck,
                telemetry
        );
    }

    /**
     * Edmonds-Karp Max-Flow Implementation (BFS Augmenting Paths).
     */
    public static class MaxFlowSolver {
        private final int n;
        private final List<List<FlowEdge>> graph;

        public MaxFlowSolver(int n) {
            this.n = n;
            this.graph = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                graph.add(new ArrayList<>());
            }
        }

        public void addEdge(int from, int to, double capacity) {
            FlowEdge forward = new FlowEdge(from, to, capacity);
            FlowEdge backward = new FlowEdge(to, from, 0.0);
            forward.residual = backward;
            backward.residual = forward;
            graph.get(from).add(forward);
            graph.get(to).add(backward);
        }

        public double edmondsKarp(int source, int sink) {
            double totalFlow = 0.0;

            while (true) {
                FlowEdge[] parentEdge = new FlowEdge[n];
                Queue<Integer> queue = new ArrayDeque<>();
                queue.add(source);

                while (!queue.isEmpty() && parentEdge[sink] == null) {
                    int curr = queue.poll();
                    for (FlowEdge edge : graph.get(curr)) {
                        if (parentEdge[edge.to] == null && edge.to != source && edge.remainingCapacity() > 1e-9) {
                            parentEdge[edge.to] = edge;
                            queue.add(edge.to);
                        }
                    }
                }

                if (parentEdge[sink] == null) break; // No augmenting path found

                // Find bottleneck residual capacity along augmenting path
                double push = Double.MAX_VALUE;
                for (int curr = sink; curr != source; curr = parentEdge[curr].from) {
                    push = Math.min(push, parentEdge[curr].remainingCapacity());
                }

                // Augment flow
                for (int curr = sink; curr != source; curr = parentEdge[curr].from) {
                    parentEdge[curr].flow += push;
                    parentEdge[curr].residual.flow -= push;
                }

                totalFlow += push;
            }

            return totalFlow;
        }

        public boolean[] getMinCut(int source) {
            boolean[] visited = new boolean[n];
            Queue<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            visited[source] = true;

            while (!queue.isEmpty()) {
                int curr = queue.poll();
                for (FlowEdge edge : graph.get(curr)) {
                    if (!visited[edge.to] && edge.remainingCapacity() > 1e-9) {
                        visited[edge.to] = true;
                        queue.add(edge.to);
                    }
                }
            }
            return visited;
        }
    }

    /**
     * Fetches live market data from Binance (aggTrades + Depth) and runs the network solver.
     */
    public DynamicSinkResolution analyzeLiveSymbol(String symbol) throws Exception {
        // 1. Fetch Order Book Depth
        String depthUrl = String.format("https://api.binance.com/api/v3/depth?symbol=%s&limit=20", symbol);
        HttpRequest dReq = HttpRequest.newBuilder().uri(URI.create(depthUrl)).header("User-Agent", "Mozilla/5.0").GET().build();
        HttpResponse<String> dResp = httpClient.send(dReq, HttpResponse.BodyHandlers.ofString());
        String depthJson = dResp.body();

        List<BookLevel> bids = new ArrayList<>();
        List<BookLevel> asks = new ArrayList<>();
        parseOrderBook(depthJson, bids, asks);

        // 2. Fetch Aggregated Trades for CVD Flow
        String tradesUrl = String.format("https://api.binance.com/api/v3/aggTrades?symbol=%s&limit=300", symbol);
        HttpRequest tReq = HttpRequest.newBuilder().uri(URI.create(tradesUrl)).header("User-Agent", "Mozilla/5.0").GET().build();
        HttpResponse<String> tResp = httpClient.send(tReq, HttpResponse.BodyHandlers.ofString());
        String tradesJson = tResp.body();

        double buyVol = 0.0, sellVol = 0.0;
        Pattern pat = Pattern.compile("\"q\"\\s*:\\s*\"([0-9.]+)\"[^}]+?\"m\"\\s*:\\s*(true|false)");
        Matcher m = pat.matcher(tradesJson);
        while (m.find()) {
            double q = Double.parseDouble(m.group(1));
            boolean buyerIsMaker = Boolean.parseBoolean(m.group(2));
            if (buyerIsMaker) sellVol += q;
            else buyVol += q;
        }

        double midPrice = (bids.isEmpty() || asks.isEmpty()) ? 100.0 : (bids.get(0).price() + asks.get(0).price()) / 2.0;

        return solveNetwork(midPrice, buyVol, sellVol, bids, asks);
    }

    private static void parseOrderBook(String json, List<BookLevel> bids, List<BookLevel> asks) {
        int bidsIdx = json.indexOf("\"bids\":");
        int asksIdx = json.indexOf("\"asks\":");
        if (bidsIdx == -1 || asksIdx == -1) return;

        String bidsPart = json.substring(bidsIdx, asksIdx);
        String asksPart = json.substring(asksIdx);

        Pattern pairPat = Pattern.compile("\\[\\s*\"([0-9.]+)\"\\s*,\\s*\"([0-9.]+)\"\\s*\\]");

        Matcher mb = pairPat.matcher(bidsPart);
        while (mb.find()) {
            bids.add(new BookLevel(Double.parseDouble(mb.group(1)), Double.parseDouble(mb.group(2))));
        }

        Matcher ma = pairPat.matcher(asksPart);
        while (ma.find()) {
            asks.add(new BookLevel(Double.parseDouble(ma.group(1)), Double.parseDouble(ma.group(2))));
        }
    }

    public static void main(String[] args) {
        System.out.println("==========================================================================================");
        System.out.println("      NETWORK FLOW ORDER BOOK & CVD ENGINE: DYNAMIC SINK SOLVER                           ");
        System.out.println("==========================================================================================");

        String symbol = args.length > 0 ? args[0].toUpperCase() : "SOLUSDT";
        System.out.printf("Fetching live CVD aggTrades and multi-tier Depth for %s...%n%n", symbol);

        try {
            OrderFlowNetwork engine = new OrderFlowNetwork();
            long t0 = System.currentTimeMillis();
            DynamicSinkResolution res = engine.analyzeLiveSymbol(symbol);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.printf(">> Network Flow solved in %d ms!%n%n", elapsed);
            System.out.println("------------------------------------------------------------------------------------------");
            System.out.printf("DYNAMIC SINK DIRECTION TYPE : %s%n", res.directionType().title);
            System.out.printf("SINK EXPLANATION            : %s%n", res.directionType().description);
            System.out.printf("DYNAMIC TARGET PRICE        : $%.4f (Expected Move: %+.2f%%)%n", res.targetPrice(), res.targetReturnPct());
            System.out.printf("NET TERMINAL SINK PRESSURE  : %+.3f Flow Units%n", res.netSinkPressure());
            System.out.println("------------------------------------------------------------------------------------------");

            if (res.primaryBottleneck() != null) {
                MinCutBottleneck b = res.primaryBottleneck();
                System.out.printf("MIN-CUT BOTTLENECK (KEY WALL): %s at $%.4f%n", b.wallName(), b.priceLevel());
                System.out.printf("   * Wall Capacity           : %.3f tokens%n", b.saturatedCapacity());
                System.out.printf("   * Flow Pushed Into Wall   : %.3f tokens%n", b.flowPushed());
                System.out.printf("   * Status                  : %s%n", b.isBreached() ? "BREACHED / LIQUIDITY VACUUM" : "HOLDING / ABSORBING FLOW");
            }

            System.out.println("\nNETWORK FLOW TELEMETRY LOG:");
            for (String t : res.networkTelemetry()) {
                System.out.printf("  [FLOW] %s%n", t);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

