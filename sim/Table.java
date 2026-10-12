package sim;

/**
 * One round start, played by many strategies on the same cards (mode A, PLAN_COMPUTE.md 1.5).
 *
 * <p>A strategy of scope {@code first} or {@code betting} plays the chart after its first move,
 * so its round's result, insurance aside, depends only on that move. The table plays each first
 * move once per round, with the chart after it, and every such strategy reads its result from
 * there: the playouts shared by first move that SIM_CHECK.md section 3 prices. A strategy of
 * scope {@code full} plays its own round. Insurance is settled from the hole card, the same for
 * every strategy that takes it. RoundTest-style equality between the shared result and the
 * strategy's own round, to the bit, is held by TableTest.
 *
 * <p>One object a thread; not thread-safe.
 */
public final class Table {

    private final Round round;
    private final Chart chart;
    private final Rules rules;
    private final boolean holeCardSeen;
    /** The result of each first move (0 to 4) played with the chart after it, and the chart's own round (5). */
    private final double[] main = new double[6];
    private final boolean[] known = new boolean[6];
    private final boolean[] splitAt = new boolean[6];
    /** Whether the player split in the round of the last {@link #result} or {@link #chartRound} (the swings' SPLITS). */
    public boolean split;
    private final int[] seen = new int[4];
    private Round.Deck deck;
    private int from;
    private int p1;
    private int p2;
    private int up;
    private int hole;
    private boolean natural;
    /** Where the chart's own round ended: the next round's start in mode A. */
    private int chartNext = -1;

    public Table(Rules rules, Plants plants, Chart chart) {
        this.rules = rules;
        this.round = new Round(rules, plants);
        this.chart = chart;
        this.holeCardSeen = plants.on(Plants.HOLE_CARD);
    }

    /** A new round start: the round's cards are deck's from position from on. */
    public void begin(Round.Deck deck, int from) {
        this.deck = deck;
        this.from = from;
        java.util.Arrays.fill(known, false);
        chartNext = -1;
        seen[0] = deck.kind(from);
        seen[1] = deck.kind(from + 1);
        seen[2] = deck.kind(from + 2);
        seen[3] = deck.kind(from + 3);
        p1 = Shoe.rank(seen[0]);
        up = Shoe.rank(seen[1]);
        p2 = Shoe.rank(seen[2]);
        hole = Shoe.rank(seen[3]);
        natural = p1 + p2 == 11 && (p1 == 1 || p2 == 1) || up + hole == 11 && (up == 1 || hole == 1);
    }

    /** The chart's own round: its result (the chart never insures), and where it ended. */
    public double chartRound() {
        double x = main(-1);
        split = splitAt[5];
        return x;
    }

    /** The next round's start after the chart's round (mode A's walk). */
    public int chartNext() {
        main(-1);
        return chartNext;
    }

    private double main(int move) {
        int slot = move < 0 ? 5 : move;
        if (!known[slot]) {
            round.play(deck, from, chart, slot == 5 ? -1 : move);
            main[slot] = round.main;
            splitAt[slot] = round.handsPlayed > 1;
            known[slot] = true;
            if (slot == 5) {
                chartNext = round.next;
            }
        }
        return main[slot];
    }

    /**
     * A strategy's result on this round for one unit bet, insurance included (SIM_CHECK.md's X).
     * play must have been started at this round start.
     */
    public double result(Strategy.Play play) {
        Strategy s = play.strategy();
        if ("full".equals(s.scope)) {
            round.play(deck, from, play, -1);
            split = round.handsPlayed > 1;
            return round.main + round.insurance;
        }
        int key = play.keyOfDeal(seen, holeCardSeen ? 4 : 3);
        double insurance = 0;
        if (up == 1 && play.insuredAt(key, p1, p2)) {
            insurance = hole == 10 ? 2 * rules.insuranceStake : -rules.insuranceStake;
        }
        int move = natural || "betting".equals(s.scope) ? -1 : play.firstMoveAt(key, p1, p2, up);
        double x = main(move) + insurance;
        split = splitAt[move < 0 ? 5 : move];
        return x;
    }
}
