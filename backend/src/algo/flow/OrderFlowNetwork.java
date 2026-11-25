package algo.flow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit 5: Network Flow & Max-Flow / Min-Cut Formulation of Order Flow & CVD.
 * 
 * Mathematical Formulation:
 * - Solves Max-Flow via Edmonds-Karp algorithm (BFS augmenting paths) in O(V * E^2).
 * - Identifies Min-Cut (S-T cut) to find the primary liquidity bottleneck wall.
 * - Dynamic Sink: Calculates residual terminal pressure and vectors price to target vacuum.
 */
public class OrderFlowNetwork {

    public enum DirectionType {
        BULLISH_BREAKOUT("EXPONENTIAL BULLISH BREAKOUT", "Ask depth saturated, unabsorbed buy flow pushing into liquidity vacuum", +1),
        BEARISH_BREAKDOWN("BEARISH CASCADE BREAKDOWN", "Bid depth saturated, unabsorbed sell flow cascading into liquidity vacuum", -1),
        ABSORPTION_REVERSAL_BEAR("ABSORPTION REVERSAL (BEARISH)", "Aggressive buy flow completely absorbed by passive Ask wall. Rejection down.", -1),
        ABSORPTION_REVERSAL_BULL("ABSORPTION REVERSAL (BULLISH)", "Aggressive sell flow completely absorbed by passive Bid wall. Bounce up.", +1),
        EQUILIBRIUM_PIN("EQUILIBRIUM PIN / CHOP", "Order flow balanced across bid/ask network. Pinned in range.", 0);

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

        public double remainingCapacity() { return capacity - flow; }
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
            double netSinkPressure,
            double targetPrice,
            double targetReturnPct,
            double maxBuyFlow,
            double maxSellFlow,
            double totalBuySupply,
            double totalSellSupply,
            MinCutBottleneck primaryBottleneck,
            List<String> networkTelemetry
    ) {}

    public DynamicSinkResolution solveNetwork(
            double currentMidPrice,
            double takerBuyVol,
            double takerSellVol,
            List<BookLevel> bids,
            List<BookLevel> asks
    ) {
        int kAsks = Math.min(asks.size(), 8);
        int kBids = Math.min(bids.size(), 8);

        int buySourceId = 0;
        int sellSourceId = 1;
        int sinkId = 2 + kAsks + kBids;

        int firstAskId = 2;
        int firstBidId = firstAskId + kAsks;

        // 1. Solve Buy Flow Sub-Network (S_Buy -> Asks -> Dynamic Sink)
        MaxFlowSolver buySolver = new MaxFlowSolver(sinkId + 1);
        buySolver.addEdge(buySourceId, firstAskId, takerBuyVol);

        for (int i = 0; i < kAsks; i++) {
            int currNode = firstAskId + i;
            double tierDepth = asks.get(i).quantity();
            if (i < kAsks - 1) {
                buySolver.addEdge(currNode, currNode + 1, tierDepth * 1.5);
            }
            buySolver.addEdge(currNode, sinkId, tierDepth);
        }

        double maxBuyFlowThroughAsks = buySolver.edmondsKarp(buySourceId, sinkId);
        boolean[] buyCut = buySolver.getMinCut(buySourceId);

        // 2. Solve Sell Flow Sub-Network (S_Sell -> Bids -> Dynamic Sink)
        MaxFlowSolver sellSolver = new MaxFlowSolver(sinkId + 1);
        sellSolver.addEdge(sellSourceId, firstBidId, takerSellVol);

        for (int i = 0; i < kBids; i++) {
            int currNode = firstBidId + i;
            double tierDepth = bids.get(i).quantity();
            if (i < kBids - 1) {
                sellSolver.addEdge(currNode, currNode + 1, tierDepth * 1.5);
            }
            sellSolver.addEdge(currNode, sinkId, tierDepth);
        }

        double maxSellFlowThroughBids = sellSolver.edmondsKarp(sellSourceId, sinkId);

        // 3. Find Primary Min-Cut Bottleneck
        MinCutBottleneck bottleneck = null;
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

        // 4. Dynamic Sink Calculation
        double netSinkPressure = maxBuyFlowThroughAsks - maxSellFlowThroughBids;
        double cvdDelta = takerBuyVol - takerSellVol;

        DirectionType directionType;
        double targetPrice = currentMidPrice;
        double targetReturnPct = 0.0;
        List<String> telemetry = new ArrayList<>();

        telemetry.add(String.format(Locale.US, "S_Buy Taker: %.2f | S_Sell Taker: %.2f (CVD Delta: %+.2f)", takerBuyVol, takerSellVol, cvdDelta));
        telemetry.add(String.format(Locale.US, "Max Buy Flow Through Asks: %.2f (Saturation: %.1f%%)",
                maxBuyFlowThroughAsks, takerBuyVol > 0 ? (maxBuyFlowThroughAsks / takerBuyVol) * 100.0 : 0.0));
        telemetry.add(String.format(Locale.US, "Max Sell Flow Through Bids: %.2f (Saturation: %.1f%%)",
                maxSellFlowThroughBids, takerSellVol > 0 ? (maxSellFlowThroughBids / takerSellVol) * 100.0 : 0.0));

        if (takerBuyVol > 1.25 * takerSellVol && maxBuyFlowThroughAsks >= asks.get(0).quantity() * 0.85) {
            directionType = DirectionType.BULLISH_BREAKOUT;
            int targetIdx = Math.min(kAsks - 1, 2);
            targetPrice = asks.get(targetIdx).price() * 1.001;
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Punctured Ask depth. Vacuum pulling price to $%.4f (%+.2f%%)", targetPrice, targetReturnPct));
        } else if (takerSellVol > 1.25 * takerBuyVol && maxSellFlowThroughBids >= bids.get(0).quantity() * 0.85) {
            directionType = DirectionType.BEARISH_BREAKDOWN;
            int targetIdx = Math.min(kBids - 1, 2);
            targetPrice = bids.get(targetIdx).price() * 0.999;
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Punctured Bid depth. Cascade pulling price to $%.4f (%+.2f%%)", targetPrice, targetReturnPct));
        } else if (takerBuyVol > takerSellVol && maxBuyFlowThroughAsks < asks.get(0).quantity() * 0.40) {
            directionType = DirectionType.ABSORPTION_REVERSAL_BEAR;
            targetPrice = bids.get(Math.min(kBids - 1, 1)).price();
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Ask wall absorbed buy pressure. Rejection target: $%.4f (%+.2f%%)", targetPrice, targetReturnPct));
        } else if (takerSellVol > takerBuyVol && maxSellFlowThroughBids < bids.get(0).quantity() * 0.40) {
            directionType = DirectionType.ABSORPTION_REVERSAL_BULL;
            targetPrice = asks.get(Math.min(kAsks - 1, 1)).price();
            targetReturnPct = ((targetPrice - currentMidPrice) / currentMidPrice) * 100.0;
            telemetry.add(String.format(Locale.US, "Dynamic Sink: Bid wall absorbed sell pressure. Bounce target: $%.4f (%+.2f%%)", targetPrice, targetReturnPct));
        } else {
            directionType = DirectionType.EQUILIBRIUM_PIN;
            targetPrice = currentMidPrice;
            targetReturnPct = 0.0;
            telemetry.add("Dynamic Sink: Balanced multi-tier flow. Pinned in equilibrium.");
        }

        return new DynamicSinkResolution(
                directionType, netSinkPressure, targetPrice, targetReturnPct,
                maxBuyFlowThroughAsks, maxSellFlowThroughBids, takerBuyVol, takerSellVol, bottleneck, telemetry
        );
    }

    public static class MaxFlowSolver {
        private final int n;
        private final List<List<FlowEdge>> graph;

        public MaxFlowSolver(int n) {
            this.n = n;
            this.graph = new ArrayList<>(n);
            for (int i = 0; i < n; i++) graph.add(new ArrayList<>());
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

                if (parentEdge[sink] == null) break;

                double push = Double.MAX_VALUE;
                for (int curr = sink; curr != source; curr = parentEdge[curr].from) {
                    push = Math.min(push, parentEdge[curr].remainingCapacity());
                }

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
}

