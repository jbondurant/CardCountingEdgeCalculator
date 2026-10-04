import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every hand the player can hold after hitting, for one state: what standing on each is
 * worth, and the walks that value hitting under a count-based play and say how often each
 * hand is reached (FULL_INDEX_PLAYS.md, section 2).
 *
 * A hand is a multiset of ranks, two or more cards with a hard total of 21 or less, reached
 * from a two-card start by hitting: 3,061 of them, 54 of them starts, for each up-card. Its
 * values are unconditional, carrying the indicator that the dealer has no natural, as
 * ExactRound's are before its final division:
 * - standing is worth the dealer's distribution from S - H - u, the hole card dealt last from
 *   that shoe and a natural hole card counting for nothing, against H's total. ExactRound
 *   works the distribution out (dealerOutcomes), with its own dealer hands and in its own
 *   order of summation, so under basic strategy the tree gives the stored values;
 * - busting is worth -(n - nat)/n of the shoe it leaves, n cards of which nat would give the
 *   dealer a natural: the chance there is no natural, lost;
 * - hitting is worth the sum over each rank c of (cards of c in S - H - u)/n x V(H + c). The
 *   draw comes from the shoe with the hole card's place still in it. In the contract's order
 *   every card's kind is exchangeable and no decision reads the hole card, so the hole card
 *   can be dealt last, as ExactRound deals it, even when decisions follow the count.
 * The terms are added in ExactRound's order, rank 1 to 10, so the values are its values but
 * for a bust, which the engine adds up from the dealer's distribution and this works out in
 * closed form; the two differ in the last bit or so.
 *
 * The policy at a hand of three or more cards sees its total, whether it is soft, the up-card
 * and the count key at the decision: the count of every card the player has seen, the cards
 * dealt before the round, the player's cards, the up-card and every card drawn, never the
 * hole card (CountKeys.Count.keyAfter). For a count that tags ranks alone the key is a
 * function of the hand, so a hand's value does not depend on how it was reached. Red Seven
 * and the KISS counts tag a rank by colour or face, and for KISS I the count does not fix
 * which classes a hand holds, so a node of the tree is the hand together with the class
 * counts of each rank the count splits. A draw's class comes from the kinds left less those
 * the node holds and the up-card. A deal then has one Hit value for each part of the
 * partition CountKeys.tagSums makes, in the same order.
 *
 * Reach goes forward over the same nodes, in the same unconditional units: a deal's start
 * gets weight x P(i | S) x the chance of its classes when its first move is Hit, and a node
 * the policy hits passes reach x (cards of c)/n to each child, with no conditioning on the
 * natural. reach x (V_stand - V_hit) at a hand is then its share of P(i)(1 - P(nat|i)) times
 * the conditional value gained by standing there, the units of the first-decision objective.
 *
 * A walk visits either every node, asking the policy at every hand of three or more cards
 * below 21, or only the nodes whose value can depend on a contested decision: the starts,
 * the contested hands (hard 12 to 16, soft 13 to 18) and the hard hands of 11 or less from
 * which they are reached. The second plays the chart everywhere else, hitting hard 11 or less
 * and standing on hard 17 and soft 19 or more whatever the policy says there, and visits
 * about a quarter of the nodes. With a policy that plays the chart outside the contested
 * spots the two give the same values and reach, term for term (PlayerTreeTest).
 *
 * The tree assumes no round from the state can run the shoe out, and checks it: the shoe must
 * hold more cards than a round can take. Every stored state holds at least 85.
 */
final class PlayerTree {

    /** Which nodes a walk visits (see the class comment). */
    enum Mode {
        EVERY_NODE, CONTESTED
    }

    /** The play of a hand of three or more cards, as a walk asks it. */
    interface LaterPlay {
        /** Whether to stand on this total, below 21, soft or not, against this up-card at this count key. */
        boolean stands(int total, boolean soft, int up, int key);
    }

    /** The first move of each deal: whether it hits, per part of the deal's tag-class partition. */
    interface FirstHits {
        boolean hits(int deal, int part);
    }

    /** Receives a walk's gap at each decision it reaches. */
    interface GapSink {
        /** weight x reach x (V_stand - V_hit) at one node: a hand at this spot and count key. */
        void add(int spot, int key, double gap);
    }

    /** Basic strategy after the first move: hit or stand by total, softness and up-card, whatever the count. */
    static final LaterPlay CHART = chartLaterPlay();

    /** Spots are numbered by up-card, softness and total: spot(total, soft, up). */
    static final int SPOTS = 10 * 2 * 22;

    static int spot(int total, boolean soft, int up) {
        return ((up - 1) * 2 + (soft ? 1 : 0)) * 22 + total;
    }

    static int spotTotal(int spot) {
        return spot % 22;
    }

    static boolean spotSoft(int spot) {
        return (spot / 22) % 2 == 1;
    }

    static int spotUp(int spot) {
        return spot / 44 + 1;
    }

    /** Whether a hand of three or more cards at this total is one where the play is fitted: hard 12 to 16, soft 13 to 18. */
    static boolean contested(int total, boolean soft) {
        return soft ? total >= 13 && total <= 18 : total >= 12 && total <= 16;
    }

    private static LaterPlay chartLaterPlay() {
        RoundPolicy chart = BasicStrategy.chart();
        boolean[] stands = new boolean[SPOTS];
        for (int up = 1; up <= 10; up++) {
            for (int total = 4; total <= 20; total++) {
                for (boolean soft : new boolean[]{false, true}) {
                    RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, 3, 0, up, false, false, true,
                            false, false, false);
                    stands[spot(total, soft, up)] = chart.choose(d) == PlayerMove.Stand;
                }
            }
        }
        return (total, soft, up, key) -> stands[spot(total, soft, up)];
    }

    // ------------------------------------------------------------------ the state

    /** The cards left at the round's start, by rank. */
    final int[] shoe;
    final int cards;
    /** stand[u][h]: V_stand of hand h against up-card u, unconditional; NaN where the state cannot hold them. */
    private final double[][] stand = new double[11][];

    /**
     * The tree of one state: the stand value of every hand against every up-card the state can
     * deal. Throws IllegalArgumentException if a round from this shoe could run it out.
     */
    PlayerTree(ExactRound engine, int[] shoe) {
        if (shoe == null || shoe.length != 11) {
            throw new IllegalArgumentException("a shoe is an int[11] indexed by rank 1 to 10");
        }
        this.shoe = shoe.clone();
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            if (shoe[r] < 0) {
                throw new IllegalArgumentException("the shoe holds " + shoe[r] + " cards of rank " + r);
            }
            n += shoe[r];
        }
        this.cards = n;
        int most = mostCardsARoundTakes(shoe);
        if (n < most) {
            throw new IllegalArgumentException("a round can take " + most + " cards and the shoe holds " + n
                    + ": " + Arrays.toString(shoe));
        }
        Hands hands = Hands.ALL;
        double[] outcomes = new double[ExactRound.DealerOutcomes.OUTCOMES];
        double[][] many = new double[ExactRound.DealerOutcomes.OUTCOMES][hands.size];
        int[][] counts = new int[11][hands.size];
        int[] sizes = new int[hands.size];
        int[] which = new int[hands.size];
        int[] left = new int[11];
        for (int u = 1; u <= 10; u++) {
            double[] s = new double[hands.size];
            Arrays.fill(s, Double.NaN);
            stand[u] = s;
            if (shoe[u] == 0) {
                continue;
            }
            // Every hand the state can hold beside the up-card; those whose shoe holds more cards
            // than the dealer can run out of go together, the rest one at a time.
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            int together = 0;
            for (int h = 0; h < hands.size; h++) {
                boolean fits = true;
                for (int r = 1; r <= 10; r++) {
                    left[r] = shoe[r] - hands.held[h * 11 + r] - (r == u ? 1 : 0);
                    fits &= left[r] >= 0;
                }
                if (!fits) {
                    continue;
                }
                int after = cards - hands.cards[h] - 1;
                if (after > dealer.mostCardsToRunOutOf()) {
                    for (int r = 1; r <= 10; r++) {
                        counts[r][together] = left[r];
                    }
                    sizes[together] = after;
                    which[together] = h;
                    together++;
                    continue;
                }
                if (dealer.outcomes(left, outcomes)) {
                    throw new IllegalStateException("the dealer can run out of cards on " + Arrays.toString(left));
                }
                s[h] = ExactRound.DealerOutcomes.stood(hands.total(h), outcomes);
            }
            dealer.outcomes(counts, sizes, together, many);
            for (int k = 0; k < together; k++) {
                s[which[k]] = ExactRound.DealerOutcomes.stood(hands.total(which[k]), many, k);
            }
        }
    }

    /**
     * The most cards a round without a split can take from this shoe, the deal included. A
     * hand hits only while its hard total is 20 or less, from 2 or more on its first two
     * cards, so every card it draws but its last adds up to 18 at most; the dealer draws only
     * while its hard total is 16 or less, from 2 or more with the hole card, so every draw but
     * its last adds up to 14 at most. No set of cards from the shoe adding up to 32 or less
     * holds more than the smallest ones do. Add the deal's three cards, the hole card and the
     * two last cards.
     */
    static int mostCardsARoundTakes(int[] shoe) {
        int budget = 18 + 14;
        int smallest = 0;
        for (int r = 1; r <= 10 && budget >= r; r++) {
            int take = Math.min(shoe[r], budget / r);
            smallest += take;
            budget -= take * r;
        }
        return 3 + 1 + 2 + smallest;
    }

    /** V_stand of the hand holding p1 and p2 against up, unconditional; NaN if the state cannot deal them or they are a natural. */
    double standValue(int p1, int p2, int up) {
        int h = Hands.ALL.start(p1, p2);
        return h < 0 ? Double.NaN : stand[up][h];
    }

    /** The tree for one count, from the cards dealt before the round by kind (ShoeRun), which must leave this state. */
    Counted count(CountKeys.Count count, int[] dealt) {
        return new Counted(count, dealt);
    }

    // ------------------------------------------------------------------ hands by rank

    /** The hands of two or more cards a two-card start reaches by hitting, by rank, fewest cards first. */
    static final class Hands {
        static final Hands ALL = new Hands();
        static final int BUST = -1;

        final int size;
        /** held[h * 11 + r]: the cards of rank r hand h holds. */
        final byte[] held;
        final int[] hard;
        final boolean[] ace;
        final int[] cards;
        /** child[h * 11 + c]: the hand with a card of rank c added, or BUST; BUST too for a hand of 21. */
        final int[] child;
        private final int[] start = new int[11 * 11];

        private Hands() {
            List<byte[]> made = new ArrayList<>();
            Map<Long, Integer> index = new HashMap<>();
            Arrays.fill(start, -1);
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = p1; p2 <= 10; p2++) {
                    if (p1 + p2 == 11 && (p1 == 1 || p2 == 1)) {
                        continue;   // a natural has no move but Stand
                    }
                    byte[] h = new byte[11];
                    h[p1]++;
                    h[p2]++;
                    int at = add(h, made, index);
                    start[p1 * 11 + p2] = at;
                    start[p2 * 11 + p1] = at;
                }
            }
            List<int[]> children = new ArrayList<>();
            int from = 0;
            while (from < made.size()) {
                int to = made.size();
                for (int h = from; h < to; h++) {
                    byte[] held = made.get(h);
                    int[] kids = new int[11];
                    Arrays.fill(kids, BUST);
                    if (total(held) < 21) {
                        for (int c = 1; c <= 10; c++) {
                            if (hard(held) + c <= 21) {
                                byte[] next = held.clone();
                                next[c]++;
                                kids[c] = add(next, made, index);
                            }
                        }
                    }
                    children.add(kids);
                }
                from = to;
            }
            size = made.size();
            child = new int[size * 11];
            for (int h = 0; h < size; h++) {
                System.arraycopy(children.get(h), 0, child, h * 11, 11);
            }
            held = new byte[size * 11];
            hard = new int[size];
            ace = new boolean[size];
            cards = new int[size];
            for (int h = 0; h < size; h++) {
                byte[] m = made.get(h);
                System.arraycopy(m, 0, held, h * 11, 11);
                hard[h] = hard(m);
                ace[h] = m[1] > 0;
                for (int r = 1; r <= 10; r++) {
                    cards[h] += m[r];
                }
            }
        }

        private static int add(byte[] h, List<byte[]> made, Map<Long, Integer> index) {
            long key = 0;
            for (int r = 1; r <= 10; r++) {
                key |= (long) h[r] << (5 * (r - 1));
            }
            Integer at = index.get(key);
            if (at == null) {
                at = made.size();
                made.add(h);
                index.put(key, at);
            }
            return at;
        }

        private static int hard(byte[] h) {
            int t = 0;
            for (int r = 1; r <= 10; r++) {
                t += r * h[r];
            }
            return t;
        }

        private static int total(byte[] h) {
            int t = hard(h);
            return h[1] > 0 && t <= 11 ? t + 10 : t;
        }

        int total(int h) {
            return ace[h] && hard[h] <= 11 ? hard[h] + 10 : hard[h];
        }

        boolean soft(int h) {
            return ace[h] && hard[h] <= 11;
        }

        /** The hand of a two-card start, in either order; -1 for a natural. */
        int start(int p1, int p2) {
            return start[p1 * 11 + p2];
        }
    }

    // ------------------------------------------------------------------ hands by tag class

    /**
     * The nodes of the tree for counts that split the ranks into these numbers of tag classes:
     * each hand with the class counts of the cards it holds, fewest cards first. A rank with
     * one class is a rank; counts that tag by rank alone share the graph whose nodes are the
     * hands. Made once for each way of splitting the ranks, and shared by every state.
     */
    static final class Graph {
        private static final Map<String, Graph> MADE = new ConcurrentHashMap<>();
        static final int BUST = -1;

        /** How many tag classes each rank has, at 1 to 10. */
        final int[] classes = new int[11];
        /** Slots are (rank, class) pairs in rank order, then class order: the order a hit adds its terms in. */
        final int slots;
        final int[] firstSlot = new int[11];
        final int[] slotRank;
        final int maxClasses;
        final int size;
        /** The hand (Hands) each node holds. */
        final int[] hand;
        /** taken[node * slots + s]: the node's cards in slot s. */
        final byte[] taken;
        /** child[node * slots + s]: the node a draw of slot s leads to, or BUST; only for nodes below 21. */
        final int[] child;
        /** startNode[((p1 * 11 + p2) * maxClasses + a) * maxClasses + b]: the start holding p1 of class a and p2 of class b. */
        private final int[] startNode;
        private final Live[] live = new Live[Mode.values().length];

        static Graph of(int[] classes) {
            StringBuilder key = new StringBuilder();
            for (int r = 1; r <= 10; r++) {
                key.append(classes[r]).append(',');
            }
            return MADE.computeIfAbsent(key.toString(), k -> new Graph(classes));
        }

        private Graph(int[] classesByRank) {
            int s = 0;
            int widest = 1;
            for (int r = 1; r <= 10; r++) {
                if (classesByRank[r] < 1) {
                    throw new IllegalArgumentException("rank " + r + " has " + classesByRank[r] + " classes");
                }
                classes[r] = classesByRank[r];
                firstSlot[r] = s;
                s += classes[r];
                widest = Math.max(widest, classes[r]);
            }
            slots = s;
            maxClasses = widest;
            slotRank = new int[slots];
            for (int r = 1; r <= 10; r++) {
                for (int k = 0; k < classes[r]; k++) {
                    slotRank[firstSlot[r] + k] = r;
                }
            }
            Hands hands = Hands.ALL;
            List<byte[]> made = new ArrayList<>();
            List<Integer> handOf = new ArrayList<>();
            Map<String, Integer> index = new HashMap<>();
            startNode = new int[11 * 11 * maxClasses * maxClasses];
            Arrays.fill(startNode, -1);
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = 1; p2 <= 10; p2++) {
                    int h = hands.start(p1, p2);
                    if (h < 0) {
                        continue;
                    }
                    for (int a = 0; a < classes[p1]; a++) {
                        for (int b = 0; b < classes[p2]; b++) {
                            byte[] t = new byte[slots];
                            t[firstSlot[p1] + a]++;
                            t[firstSlot[p2] + b]++;
                            startNode[((p1 * 11 + p2) * maxClasses + a) * maxClasses + b] = add(h, t, made, handOf, index);
                        }
                    }
                }
            }
            List<int[]> children = new ArrayList<>();
            int from = 0;
            while (from < made.size()) {
                int to = made.size();
                for (int node = from; node < to; node++) {
                    int h = handOf.get(node);
                    int[] kids = new int[slots];
                    Arrays.fill(kids, BUST);
                    if (hands.total(h) < 21) {
                        for (int slot = 0; slot < slots; slot++) {
                            int next = hands.child[h * 11 + slotRank[slot]];
                            if (next == Hands.BUST) {
                                continue;
                            }
                            byte[] t = made.get(node).clone();
                            t[slot]++;
                            kids[slot] = add(next, t, made, handOf, index);
                        }
                    }
                    children.add(kids);
                }
                from = to;
            }
            size = made.size();
            hand = new int[size];
            taken = new byte[size * slots];
            child = new int[size * slots];
            for (int node = 0; node < size; node++) {
                hand[node] = handOf.get(node);
                System.arraycopy(made.get(node), 0, taken, node * slots, slots);
                System.arraycopy(children.get(node), 0, child, node * slots, slots);
            }
        }

        private static int add(int h, byte[] t, List<byte[]> made, List<Integer> handOf, Map<String, Integer> index) {
            String key = h + ":" + Arrays.toString(t);
            Integer at = index.get(key);
            if (at == null) {
                at = made.size();
                made.add(t);
                handOf.add(h);
                index.put(key, at);
            }
            return at;
        }

        /** The start holding p1 of class a and p2 of class b; -1 for a natural. */
        int start(int p1, int a, int p2, int b) {
            return startNode[((p1 * 11 + p2) * maxClasses + a) * maxClasses + b];
        }

        synchronized Live live(Mode mode) {
            if (live[mode.ordinal()] == null) {
                live[mode.ordinal()] = new Live(this, mode);
            }
            return live[mode.ordinal()];
        }
    }

    /** What a node is to a walk: a start, a hand the policy is asked about, or one that always hits. */
    static final byte START = 0;
    static final byte ASKED = 1;
    static final byte HITS = 2;

    /**
     * The nodes a walk of one mode visits, fewest cards first, and where each of their draws
     * leads: to another node it visits, to a bust, or to a hand that stands, whose stand
     * value is used as it is.
     */
    static final class Live {
        final Mode mode;
        /** The graph node at each position. */
        final int[] node;
        /** The position of each graph node, or -1 if the walk does not visit it. */
        final int[] position;
        /** target[position * slots + s]: a position, Graph.BUST, or -2 - hand for a hand that stands. */
        final int[] target;
        final byte[] role;

        private Live(Graph g, Mode mode) {
            this.mode = mode;
            Hands hands = Hands.ALL;
            position = new int[g.size];
            Arrays.fill(position, -1);
            List<Integer> visited = new ArrayList<>();
            List<Byte> roles = new ArrayList<>();
            for (int n = 0; n < g.size; n++) {
                int h = g.hand[n];
                int total = hands.total(h);
                boolean soft = hands.soft(h);
                byte r;
                if (hands.cards[h] == 2) {
                    r = START;
                } else if (total >= 21) {
                    continue;
                } else if (mode == Mode.EVERY_NODE || contested(total, soft)) {
                    r = ASKED;
                } else if (!soft && total <= 11) {
                    r = HITS;
                } else {
                    continue;   // the chart stands: hard 17 or more, soft 19 or more
                }
                position[n] = visited.size();
                visited.add(n);
                roles.add(r);
            }
            node = new int[visited.size()];
            role = new byte[visited.size()];
            target = new int[visited.size() * g.slots];
            for (int j = 0; j < node.length; j++) {
                node[j] = visited.get(j);
                role[j] = roles.get(j);
                for (int s = 0; s < g.slots; s++) {
                    int c = g.child[node[j] * g.slots + s];
                    target[j * g.slots + s] = c == Graph.BUST ? Graph.BUST
                            : position[c] >= 0 ? position[c] : -2 - g.hand[c];
                }
            }
        }
    }

    // ------------------------------------------------------------------ one count

    /**
     * The tree of this state for one count: the class counts of the cards left, the count key
     * at every node, and each deal's starts with their chances and parts. Separate trees are
     * kept for each up-card and each class the up-card can be of, since the up-card's class
     * moves the count and the classes left.
     */
    final class Counted {
        final CountKeys.Count count;
        final Graph graph;
        /** The running count of the cards dealt before the round, and how many there were. */
        final int rc;
        final int depth;
        /** The cards left in each slot. */
        final int[] left;
        final int[] slotTag;
        /** The trees: up-card and the up-card's slot. */
        final int trees;
        final int[] treeUp;
        final int[] treeUpSlot;
        /** feasible[t][node]: whether the state holds the node's cards beside the up-card. */
        final boolean[][] feasible;
        /** key[t][node]: the count key at a node of three or more cards below 21. */
        final int[][] key;
        /** The parts of deal i are partFrom[i] to partFrom[i + 1] - 1: each part's tag sum and share of the deal. */
        final int[] partFrom = new int[Deals.COUNT + 1];
        final int[] partTagSum;
        final double[] partShare;
        /** P(i | S), and the chance of no natural once deal i is out. */
        final double[] chance = new double[Deals.COUNT];
        final double[] noNatural = new double[Deals.COUNT];
        /**
         * The class assignments of deal i are compFrom[i] to compFrom[i + 1] - 1, each with its
         * part (an index into the part arrays), tree, start and chance.
         */
        final int[] compFrom = new int[Deals.COUNT + 1];
        final int[] compPart;
        final int[] compTree;
        final int[] compNode;
        final double[] compChance;

        private Counted(CountKeys.Count count, int[] dealt) {
            if (dealt == null || dealt.length != ShoeRun.KINDS) {
                throw new IllegalArgumentException("the cards dealt are counted by the " + ShoeRun.KINDS + " kinds");
            }
            if (!Arrays.equals(ShoeRun.ranksLeft(dealt), shoe)) {
                throw new IllegalArgumentException("these cards dealt leave " + Arrays.toString(ShoeRun.ranksLeft(dealt))
                        + ", not the tree's state " + Arrays.toString(shoe));
            }
            this.count = count;
            int[] classes = new int[11];
            for (int r = 1; r <= 10; r++) {
                classes[r] = count.classes(r);
            }
            graph = Graph.of(classes);
            int d = 0;
            for (int k : dealt) {
                d += k;
            }
            depth = d;
            rc = count.runningCount(dealt);
            int[][] byClass = count.classesLeft(dealt);
            left = new int[graph.slots];
            slotTag = new int[graph.slots];
            for (int r = 1; r <= 10; r++) {
                for (int k = 0; k < classes[r]; k++) {
                    left[graph.firstSlot[r] + k] = byClass[r][k];
                    slotTag[graph.firstSlot[r] + k] = count.classTag(r, k);
                }
            }

            List<int[]> made = new ArrayList<>();
            int[] treeOf = new int[graph.slots];
            Arrays.fill(treeOf, -1);
            for (int u = 1; u <= 10; u++) {
                for (int k = 0; k < classes[u]; k++) {
                    int s = graph.firstSlot[u] + k;
                    if (left[s] > 0) {
                        treeOf[s] = made.size();
                        made.add(new int[]{u, s});
                    }
                }
            }
            trees = made.size();
            treeUp = new int[trees];
            treeUpSlot = new int[trees];
            feasible = new boolean[trees][];
            key = new int[trees][];
            Hands hands = Hands.ALL;
            int slots = graph.slots;
            int[] tagOfNode = new int[graph.size];
            for (int n = 0; n < graph.size; n++) {
                int sum = 0;
                for (int s = 0; s < slots; s++) {
                    sum += graph.taken[n * slots + s] * slotTag[s];
                }
                tagOfNode[n] = sum;
            }
            for (int t = 0; t < trees; t++) {
                int u = made.get(t)[0];
                int upSlot = made.get(t)[1];
                treeUp[t] = u;
                treeUpSlot[t] = upSlot;
                boolean[] ok = new boolean[graph.size];
                int[] keys = new int[graph.size];
                for (int n = 0; n < graph.size; n++) {
                    int h = graph.hand[n];
                    boolean fits = !Double.isNaN(stand[u][h]);
                    for (int s = 0; s < slots && fits; s++) {
                        fits = graph.taken[n * slots + s] + (s == upSlot ? 1 : 0) <= left[s];
                    }
                    ok[n] = fits;
                    if (fits && hands.cards[h] >= 3 && hands.total(h) < 21) {
                        keys[n] = count.keyAfter(rc, depth, tagOfNode[n] + slotTag[upSlot], hands.cards[h] + 1);
                    }
                }
                feasible[t] = ok;
                key[t] = keys;
            }

            // Each deal's class assignments, in the order CountKeys.spread meets them: the
            // first card's class, then the second's, then the up-card's, drawn without
            // replacement from the classes left. Each goes to the part of its tag sum.
            List<Integer> partTags = new ArrayList<>();
            List<Double> partShares = new ArrayList<>();
            List<int[]> comps = new ArrayList<>();
            List<Double> compChances = new ArrayList<>();
            for (int i = 0; i < Deals.COUNT; i++) {
                partFrom[i] = partTags.size();
                compFrom[i] = comps.size();
                chance[i] = Deals.probability(shoe, i);
                if (chance[i] == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                int[] after = Deals.after(shoe, i);
                int n = 0;
                for (int r = 1; r <= 10; r++) {
                    n += after[r];
                }
                int natural = ExactRound.naturalHoleRank(Deals.UP[i]);
                long naturals = natural == 0 ? 0 : after[natural];
                noNatural[i] = (n - naturals) / (double) n;
                double[][] parts = count.tagSums(byClass, i);
                for (double[] e : parts) {
                    partTags.add((int) e[0]);
                    partShares.add(e[1]);
                }
                int p1 = Deals.P1[i];
                int p2 = Deals.P2[i];
                int up = Deals.UP[i];
                int[] l = left.clone();
                for (int a = 0; a < classes[p1]; a++) {
                    double pa = classChance(1.0, l, p1, a);
                    if (pa < 0) {
                        continue;
                    }
                    l[graph.firstSlot[p1] + a]--;
                    for (int b = 0; b < classes[p2]; b++) {
                        double pb = classChance(pa, l, p2, b);
                        if (pb < 0) {
                            continue;
                        }
                        l[graph.firstSlot[p2] + b]--;
                        for (int c = 0; c < classes[up]; c++) {
                            double pc = classChance(pb, l, up, c);
                            if (pc < 0) {
                                continue;
                            }
                            int tag = slotTag[graph.firstSlot[p1] + a] + slotTag[graph.firstSlot[p2] + b]
                                    + slotTag[graph.firstSlot[up] + c];
                            int part = -1;
                            for (int q = 0; q < parts.length; q++) {
                                if ((int) parts[q][0] == tag) {
                                    part = q;
                                }
                            }
                            if (part < 0) {
                                throw new AssertionError("no part of deal " + i + " has tag sum " + tag);
                            }
                            comps.add(new int[]{partFrom[i] + part, treeOf[graph.firstSlot[up] + c],
                                    graph.start(p1, a, p2, b)});
                            compChances.add(pc);
                        }
                        l[graph.firstSlot[p2] + b]++;
                    }
                    l[graph.firstSlot[p1] + a]++;
                }
            }
            partFrom[Deals.COUNT] = partTags.size();
            compFrom[Deals.COUNT] = comps.size();
            partTagSum = new int[partTags.size()];
            partShare = new double[partTags.size()];
            for (int q = 0; q < partTagSum.length; q++) {
                partTagSum[q] = partTags.get(q);
                partShare[q] = partShares.get(q);
            }
            compPart = new int[comps.size()];
            compTree = new int[comps.size()];
            compNode = new int[comps.size()];
            compChance = new double[comps.size()];
            for (int q = 0; q < compPart.length; q++) {
                compPart[q] = comps.get(q)[0];
                compTree[q] = comps.get(q)[1];
                compNode[q] = comps.get(q)[2];
                compChance[q] = compChances.get(q);
            }
        }

        /**
         * p times the chance that a card of rank r is of class k, given the classes left, as
         * CountKeys.spread works it out; -1 where none of the class is left.
         */
        private double classChance(double p, int[] l, int r, int k) {
            int from = graph.firstSlot[r];
            int of = l[from + k];
            if (of <= 0) {
                return -1;
            }
            int all = 0;
            for (int c = 0; c < graph.classes[r]; c++) {
                all += l[from + c];
            }
            return p * of / all;
        }

        /** The parts of deal i: how many, in CountKeys.tagSums' order. */
        int parts(int deal) {
            return partFrom[deal + 1] - partFrom[deal];
        }

        /** Walks the tree under a later play, in one mode, valuing every node backward. */
        Walk walk(LaterPlay later, Mode mode) {
            return new Walk(this, later, mode);
        }
    }

    // ------------------------------------------------------------------ one walk

    /**
     * One count's tree walked under one later play: V_hit and V at every node it visits, a Hit
     * value for every part of every deal, and once reach is spread, the gaps and the terminal
     * mass.
     */
    final class Walk {
        final Counted counted;
        final Mode mode;
        final Live live;
        /** value[t][j]: V at position j of tree t, under the play; hit[t][j]: V_hit there. */
        final double[][] value;
        final double[][] hit;
        /** Whether the play stands at an asked node. */
        final boolean[][] stands;
        /** Each part's Hit value, conditional on no natural, as a stored value is; NaN where the deal cannot be dealt. */
        final double[] partHit;
        private double[][] reach;

        private Walk(Counted c, LaterPlay later, Mode mode) {
            this.counted = c;
            this.mode = mode;
            Graph g = c.graph;
            this.live = g.live(mode);
            Hands hands = Hands.ALL;
            int slots = g.slots;
            int size = live.node.length;
            value = new double[c.trees][];
            hit = new double[c.trees][];
            stands = new boolean[c.trees][];
            for (int t = 0; t < c.trees; t++) {
                int u = c.treeUp[t];
                int upSlot = c.treeUpSlot[t];
                double[] standOf = stand[u];
                boolean[] ok = c.feasible[t];
                int[] keys = c.key[t];
                int natural = ExactRound.naturalHoleRank(u);
                int naturalsLeft = natural == 0 ? 0 : shoe[natural] - (u == natural ? 1 : 0);
                double[] v = new double[size];
                double[] h = new double[size];
                boolean[] st = new boolean[size];
                for (int j = size - 1; j >= 0; j--) {
                    int node = live.node[j];
                    if (!ok[node]) {
                        v[j] = Double.NaN;
                        h[j] = Double.NaN;
                        continue;
                    }
                    int hand = g.hand[node];
                    int n = cards - hands.cards[hand] - 1;
                    int nat = natural == 0 ? 0 : naturalsLeft - hands.held[hand * 11 + natural];
                    double sum = 0;
                    int base = node * slots;
                    int at = j * slots;
                    for (int s = 0; s < slots; s++) {
                        int cnt = c.left[s] - g.taken[base + s] - (s == upSlot ? 1 : 0);
                        if (cnt == 0) {
                            continue;
                        }
                        int to = live.target[at + s];
                        double after;
                        if (to >= 0) {
                            after = v[to];
                        } else if (to == Graph.BUST) {
                            int n1 = n - 1;
                            int nat1 = nat - (g.slotRank[s] == natural ? 1 : 0);
                            after = -(n1 - nat1) / (double) n1;
                        } else {
                            after = standOf[-2 - to];
                        }
                        sum += cnt / (double) n * after;
                    }
                    h[j] = sum;
                    switch (live.role[j]) {
                        case START:
                            v[j] = Double.NaN;   // a start's first move is the deal's, not the later play's
                            break;
                        case HITS:
                            v[j] = sum;
                            break;
                        default:
                            boolean stay = later.stands(hands.total(hand), hands.soft(hand), u, keys[node]);
                            st[j] = stay;
                            v[j] = stay ? standOf[hand] : sum;
                    }
                }
                value[t] = v;
                hit[t] = h;
                stands[t] = st;
            }

            partHit = new double[c.partTagSum.length];
            Arrays.fill(partHit, Double.NaN);
            double[] sums = new double[partHit.length];
            for (int i = 0; i < Deals.COUNT; i++) {
                for (int q = c.compFrom[i]; q < c.compFrom[i + 1]; q++) {
                    sums[c.compPart[q]] += c.compChance[q] * hit[c.compTree[q]][live.position[c.compNode[q]]];
                }
                for (int p = c.partFrom[i]; p < c.partFrom[i + 1]; p++) {
                    partHit[p] = sums[p] / c.partShare[p] / c.noNatural[i];
                }
            }
        }

        /** The Hit value of part p of deal i, conditional on no natural, as the stored V(Hit) is. */
        double hit(int deal, int part) {
            if (part < 0 || part >= counted.parts(deal)) {
                throw new IllegalArgumentException("deal " + deal + " has " + counted.parts(deal) + " parts");
            }
            return partHit[counted.partFrom[deal] + part];
        }

        /**
         * Spreads reach forward from every deal whose first move hits, weight x P(i | S) x the
         * chance of each class assignment, through every node the play hits.
         */
        void reach(FirstHits first, double weight) {
            Counted c = counted;
            Graph g = c.graph;
            Hands hands = Hands.ALL;
            int slots = g.slots;
            int size = live.node.length;
            reach = new double[c.trees][size];
            for (int i = 0; i < Deals.COUNT; i++) {
                for (int q = c.compFrom[i]; q < c.compFrom[i + 1]; q++) {
                    if (first.hits(i, c.compPart[q] - c.partFrom[i])) {
                        reach[c.compTree[q]][live.position[c.compNode[q]]] += weight * c.chance[i] * c.compChance[q];
                    }
                }
            }
            for (int t = 0; t < c.trees; t++) {
                int upSlot = c.treeUpSlot[t];
                double[] r = reach[t];
                boolean[] st = stands[t];
                for (int j = 0; j < size; j++) {
                    double here = r[j];
                    if (here == 0 || (live.role[j] == ASKED && st[j])) {
                        continue;
                    }
                    int node = live.node[j];
                    int n = cards - hands.cards[g.hand[node]] - 1;
                    int base = node * slots;
                    int at = j * slots;
                    for (int s = 0; s < slots; s++) {
                        int to = live.target[at + s];
                        if (to < 0) {
                            continue;
                        }
                        int cnt = c.left[s] - g.taken[base + s] - (s == upSlot ? 1 : 0);
                        if (cnt == 0) {
                            continue;
                        }
                        r[to] += here * (cnt / (double) n);
                    }
                }
            }
        }

        /** reach at position j of tree t, after reach. */
        double reachAt(int t, int j) {
            return reach[t][j];
        }

        /**
         * Gives the sink reach x (V_stand - V_hit) at every node where the play is asked and
         * some reach arrives: every hand of three or more cards below 21 in a walk of every
         * node, the contested ones in a contested walk.
         */
        void gaps(GapSink sink) {
            Counted c = counted;
            Graph g = c.graph;
            Hands hands = Hands.ALL;
            for (int t = 0; t < c.trees; t++) {
                int u = c.treeUp[t];
                double[] r = reach[t];
                for (int j = 0; j < r.length; j++) {
                    if (r[j] == 0 || live.role[j] != ASKED) {
                        continue;
                    }
                    int node = live.node[j];
                    int h = g.hand[node];
                    sink.add(spot(hands.total(h), hands.soft(h), u), c.key[t][node], r[j] * (stand[u][h] - hit[t][j]));
                }
            }
        }

        /**
         * The sum over every hand that ends of its reach times what it ends with: the stand
         * value where the play stands, the bust value where a draw busts, and the stand value of
         * a hand the walk does not visit, where it always stands. It must equal the sum over
         * hitting deals of weight x P(i) x V_hit, the check that reach is right.
         */
        double terminalMass() {
            Counted c = counted;
            Graph g = c.graph;
            Hands hands = Hands.ALL;
            int slots = g.slots;
            double total = 0;
            for (int t = 0; t < c.trees; t++) {
                int u = c.treeUp[t];
                int upSlot = c.treeUpSlot[t];
                int natural = ExactRound.naturalHoleRank(u);
                int naturalsLeft = natural == 0 ? 0 : shoe[natural] - (u == natural ? 1 : 0);
                double[] r = reach[t];
                for (int j = 0; j < r.length; j++) {
                    double here = r[j];
                    if (here == 0) {
                        continue;
                    }
                    int node = live.node[j];
                    int hand = g.hand[node];
                    if (live.role[j] == ASKED && stands[t][j]) {
                        total += here * stand[u][hand];
                        continue;
                    }
                    int n = cards - hands.cards[hand] - 1;
                    int nat = natural == 0 ? 0 : naturalsLeft - hands.held[hand * 11 + natural];
                    int base = node * slots;
                    int at = j * slots;
                    for (int s = 0; s < slots; s++) {
                        int to = live.target[at + s];
                        int cnt = c.left[s] - g.taken[base + s] - (s == upSlot ? 1 : 0);
                        if (to >= 0 || cnt == 0) {
                            continue;
                        }
                        double end;
                        if (to == Graph.BUST) {
                            int n1 = n - 1;
                            int nat1 = nat - (g.slotRank[s] == natural ? 1 : 0);
                            end = -(n1 - nat1) / (double) n1;
                        } else {
                            end = stand[u][-2 - to];
                        }
                        total += here * (cnt / (double) n) * end;
                    }
                }
            }
            return total;
        }
    }
}
