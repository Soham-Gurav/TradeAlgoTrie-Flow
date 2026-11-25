package algo.approx;

import java.util.*;

/**
 * Unit 5: Network Flows & Approximation Algorithms - Metric TSP 2-Approximation.
 *
 * Domain Mapping (Portfolio Rebalancing & Correlation Routing):
 * - Computes Pearson return correlation rho_{ij} across N crypto assets.
 * - Defines correlation metric distance:
 *       d(i, j) = sqrt(2 * (1 - rho_{ij})) in [0, 2]
 *   which equals the Euclidean L2 distance ||x_i - x_j||_2 between normalized return vectors
 *   on the unit hypersphere and therefore strictly satisfies symmetry and triangle inequality.
 *
 * Algorithm (2-Approximation):
 * 1. Construct a Minimum Spanning Tree (MST) via Prim's Algorithm in O(V^2).
 * 2. Perform a Preorder DFS traversal of the MST (shortcutting repeats) to form a Hamiltonian cycle.
 * 3. Approximation Guarantee: Cost(Tour) <= 2 * Cost(MST) <= 2 * Cost(OPT).
 *
 * Baselines:
 * - Held-Karp Exact DP (O(V^2 * 2^V)) when V <= 12 to compute empirical ratio Cost(Approx) / Cost(OPT).
 * - Greedy Nearest Neighbor heuristic (O(V^2)).
 */
public class MetricTspApproximator {

    public record AssetNode(
            int index,
            String symbol,
            double x,
            double y
    ) {}

    public record MstEdge(
            int from,
            int to,
            double weight
    ) {}

    public record TspResult(
            List<AssetNode> nodes,
            double[][] distanceMatrix,
            List<MstEdge> mstEdges,
            List<Integer> approxTour,
            List<Integer> nearestNeighborTour,
            List<Integer> exactOptimalTour,
            double mstCost,
            double approxCost,
            double nearestNeighborCost,
            double exactOptimalCost,
            double approxRatioVsOpt,
            double approxBoundVsMst,
            boolean validMetric,
            long approxTimeNs,
            long exactTimeNs
    ) {}

    /**
     * Builds the metric correlation distance matrix from aligned asset log-return series and solves TSP.
     */
    public TspResult solveFromReturnSeries(List<String> symbols, List<double[]> alignedLogReturns) {
        if (symbols == null || alignedLogReturns == null || symbols.size() != alignedLogReturns.size()) {
            throw new IllegalArgumentException("Symbols and return series must have matching non-null sizes.");
        }
        int n = symbols.size();
        double[][] dist = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double corr = pearsonCorrelation(alignedLogReturns.get(i), alignedLogReturns.get(j));
                double d = Math.sqrt(Math.max(0.0, 2.0 * (1.0 - corr)));
                dist[i][j] = d;
                dist[j][i] = d;
            }
        }
        return solveFromDistanceMatrix(symbols, dist);
    }

    /**
     * Solves Metric TSP 2-Approximation + Baselines on an explicit symmetric distance matrix.
     */
    public TspResult solveFromDistanceMatrix(List<String> symbols, double[][] dist) {
        if (symbols == null || dist == null || symbols.size() != dist.length) {
            throw new IllegalArgumentException("Invalid symbols or distance matrix.");
        }
        int n = symbols.size();
        if (n == 0) {
            return new TspResult(
                    Collections.emptyList(), new double[0][0], Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    0.0, 0.0, 0.0, 0.0, 1.0, 1.0, true, 0L, 0L
            );
        }
        if (n == 1) {
            List<AssetNode> single = List.of(new AssetNode(0, symbols.get(0), 0.5, 0.5));
            List<Integer> tour = List.of(0, 0);
            return new TspResult(
                    single, dist, Collections.emptyList(), tour, tour, tour,
                    0.0, 0.0, 0.0, 0.0, 1.0, 1.0, true, 0L, 0L
            );
        }

        boolean validMetric = isValidMetric(dist);

        // 1. Solve 2-Approximation (Prim's MST + Preorder DFS)
        long t0 = System.nanoTime();
        List<MstEdge> mstEdges = computePrimMst(dist);
        double mstCost = 0.0;
        List<List<Integer>> mstAdj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) mstAdj.add(new ArrayList<>());
        for (MstEdge e : mstEdges) {
            mstCost += e.weight();
            mstAdj.get(e.from()).add(e.to());
            mstAdj.get(e.to()).add(e.from());
        }
        for (List<Integer> adj : mstAdj) Collections.sort(adj);

        List<Integer> approxTour = new ArrayList<>(n + 1);
        boolean[] visited = new boolean[n];
        preorderDfs(0, mstAdj, visited, approxTour);
        approxTour.add(approxTour.get(0)); // Close Hamiltonian cycle
        double approxCost = tourCost(approxTour, dist);
        long approxTimeNs = System.nanoTime() - t0;

        // 2. Greedy Nearest-Neighbor Heuristic Baseline
        List<Integer> nnTour = computeNearestNeighbor(dist);
        double nnCost = tourCost(nnTour, dist);

        // 3. Exact Held-Karp DP Baseline (for N <= 12)
        double exactCost = -1.0;
        List<Integer> exactTour = Collections.emptyList();
        long exactTimeNs = 0L;
        if (n <= 12) {
            long t1 = System.nanoTime();
            ExactSolution exact = solveExactHeldKarp(dist);
            exactTimeNs = System.nanoTime() - t1;
            exactCost = exact.cost();
            exactTour = exact.tour();
        }

        double approxRatioVsOpt = (exactCost > 1e-9) ? (approxCost / exactCost) : 1.0;
        double approxBoundVsMst = (mstCost > 1e-9) ? (approxCost / mstCost) : 1.0;
        List<AssetNode> layoutNodes = compute2DLayout(symbols, dist);

        return new TspResult(
                layoutNodes,
                dist,
                mstEdges,
                approxTour,
                nnTour,
                exactTour,
                mstCost,
                approxCost,
                nnCost,
                exactCost,
                approxRatioVsOpt,
                approxBoundVsMst,
                validMetric,
                approxTimeNs,
                exactTimeNs
        );
    }

    public static boolean isValidMetric(double[][] dist) {
        int n = dist.length;
        for (int i = 0; i < n; i++) {
            if (dist[i].length != n) return false;
            if (Math.abs(dist[i][i]) > 1e-9) return false;
            for (int j = 0; j < n; j++) {
                if (dist[i][j] < -1e-9) return false;
                if (Math.abs(dist[i][j] - dist[j][i]) > 1e-6) return false;
                for (int k = 0; k < n; k++) {
                    if (dist[i][j] > dist[i][k] + dist[k][j] + 1e-6) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private List<MstEdge> computePrimMst(double[][] dist) {
        int n = dist.length;
        double[] minEdge = new double[n];
        int[] parent = new int[n];
        boolean[] inMst = new boolean[n];
        Arrays.fill(minEdge, Double.MAX_VALUE);
        Arrays.fill(parent, -1);

        minEdge[0] = 0.0;
        List<MstEdge> edges = new ArrayList<>(n - 1);

        for (int step = 0; step < n; step++) {
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inMst[v] && (u == -1 || minEdge[v] < minEdge[u])) {
                    u = v;
                }
            }
            if (u == -1) break;
            inMst[u] = true;
            if (parent[u] != -1) {
                edges.add(new MstEdge(parent[u], u, dist[parent[u]][u]));
            }

            for (int v = 0; v < n; v++) {
                if (!inMst[v] && dist[u][v] < minEdge[v]) {
                    minEdge[v] = dist[u][v];
                    parent[v] = u;
                }
            }
        }
        return edges;
    }

    private static void preorderDfs(int u, List<List<Integer>> adj, boolean[] visited, List<Integer> tour) {
        visited[u] = true;
        tour.add(u);
        for (int v : adj.get(u)) {
            if (!visited[v]) {
                preorderDfs(v, adj, visited, tour);
            }
        }
    }

    private List<Integer> computeNearestNeighbor(double[][] dist) {
        int n = dist.length;
        boolean[] visited = new boolean[n];
        List<Integer> tour = new ArrayList<>(n + 1);
        int curr = 0;
        visited[curr] = true;
        tour.add(curr);

        for (int step = 1; step < n; step++) {
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int v = 0; v < n; v++) {
                if (!visited[v] && dist[curr][v] < bestDist) {
                    bestDist = dist[curr][v];
                    best = v;
                }
            }
            if (best == -1) break;
            visited[best] = true;
            tour.add(best);
            curr = best;
        }
        tour.add(0);
        return tour;
    }

    private record ExactSolution(double cost, List<Integer> tour) {}

    private ExactSolution solveExactHeldKarp(double[][] dist) {
        int n = dist.length;
        int fullMask = (1 << n) - 1;
        double[][] dp = new double[1 << n][n];
        int[][] prev = new int[1 << n][n];
        for (double[] row : dp) Arrays.fill(row, Double.MAX_VALUE);
        for (int[] row : prev) Arrays.fill(row, -1);

        dp[1][0] = 0.0;

        for (int mask = 1; mask <= fullMask; mask++) {
            if ((mask & 1) == 0) continue;
            for (int u = 0; u < n; u++) {
                if ((mask & (1 << u)) == 0 || dp[mask][u] >= Double.MAX_VALUE / 2) continue;
                for (int v = 0; v < n; v++) {
                    if ((mask & (1 << v)) != 0) continue;
                    int nextMask = mask | (1 << v);
                    double cand = dp[mask][u] + dist[u][v];
                    if (cand < dp[nextMask][v]) {
                        dp[nextMask][v] = cand;
                        prev[nextMask][v] = u;
                    }
                }
            }
        }

        double bestCost = Double.MAX_VALUE;
        int lastNode = -1;
        for (int u = 1; u < n; u++) {
            if (dp[fullMask][u] >= Double.MAX_VALUE / 2) continue;
            double total = dp[fullMask][u] + dist[u][0];
            if (total < bestCost) {
                bestCost = total;
                lastNode = u;
            }
        }

        List<Integer> tour = new ArrayList<>(n + 1);
        tour.add(0);
        List<Integer> rev = new ArrayList<>();
        int mask = fullMask;
        int curr = lastNode;
        while (curr > 0) {
            rev.add(curr);
            int p = prev[mask][curr];
            mask ^= (1 << curr);
            curr = p;
        }
        Collections.reverse(rev);
        tour.addAll(rev);
        tour.add(0);
        return new ExactSolution(bestCost, tour);
    }

    public static double tourCost(List<Integer> tour, double[][] dist) {
        double cost = 0.0;
        for (int i = 0; i < tour.size() - 1; i++) {
            cost += dist[tour.get(i)][tour.get(i + 1)];
        }
        return cost;
    }

    private static double pearsonCorrelation(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        if (len < 2) return 0.0;
        double meanA = 0.0, meanB = 0.0;
        for (int i = 0; i < len; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= len;
        meanB /= len;

        double num = 0.0, denA = 0.0, denB = 0.0;
        for (int i = 0; i < len; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            num += da * db;
            denA += da * da;
            denB += db * db;
        }
        double denom = Math.sqrt(denA * denB);
        if (denom < 1e-12) return 0.0;
        return Math.max(-1.0, Math.min(1.0, num / denom));
    }

    /**
     * Computes a clean force/MDS-relaxed 2D embedding in [0.14, 0.86] so correlated assets sit closer.
     */
    private static List<AssetNode> compute2DLayout(List<String> symbols, double[][] dist) {
        int n = symbols.size();
        double[] x = new double[n];
        double[] y = new double[n];

        for (int i = 0; i < n; i++) {
            double angle = (2.0 * Math.PI * i) / n - Math.PI / 2.0;
            x[i] = Math.cos(angle);
            y[i] = Math.sin(angle);
        }

        // Light deterministic spring relaxation towards correlation distances
        for (int iter = 0; iter < 80; iter++) {
            double[] fx = new double[n];
            double[] fy = new double[n];
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double dx = x[j] - x[i];
                    double dy = y[j] - y[i];
                    double curDist = Math.max(0.05, Math.hypot(dx, dy));
                    double target = Math.max(0.25, dist[i][j]);
                    double force = (curDist - target) * 0.04;
                    double ux = dx / curDist;
                    double uy = dy / curDist;
                    fx[i] += force * ux;
                    fy[i] += force * uy;
                    fx[j] -= force * ux;
                    fy[j] -= force * uy;
                }
            }
            for (int i = 0; i < n; i++) {
                x[i] += fx[i];
                y[i] += fy[i];
            }
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }
        double spanX = Math.max(maxX - minX, 1e-6);
        double spanY = Math.max(maxY - minY, 1e-6);

        List<AssetNode> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double nx = 0.14 + 0.72 * ((x[i] - minX) / spanX);
            double ny = 0.16 + 0.68 * ((y[i] - minY) / spanY);
            nodes.add(new AssetNode(i, symbols.get(i), nx, ny));
        }
        return nodes;
    }
}
