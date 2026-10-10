# How the shoes are dealt, and the files the simulator reads

This file is for the author of the independent simulator (`sim/`, PLAN_COMPUTE.md section 1),
who works from ROUND_CONTRACT.md, Loto-Québec's published blackjack rules and this file, which
holds both the shoe's procedure and the file formats of PLAN_COMPUTE.md 1.2, and who reads none
of the engine's code. Part A says how the engine lays out, shuffles and deals
its 60,000 shoes, which round starts it records, how it decides which of them to value, and
when each card enters a count, so that the simulator can deal the same shoes card for card
(check D1). Part B gives the files of the export bundle the simulator reads, and is **the
format `StrategyExport` must write**.

Part A was written on 9 October 2026 from `src/ShoeRun.java` and what it calls
(`ConcreteRound`, `BasicStrategy`, `RoundRules`, `HouseRules`, `StateRun`, `AllStates`,
`CountKeys`, `SystemScorer`, `ScoringStates`), and is held to them by
`test/ShoeSpecTest.java`: the test reads the two data blocks below, compiles the two Java
blocks exactly as they are written here, and compares them with the engine on every round start
of all 60,000 shoes. If this file and the engine ever disagree, the engine is what made
`runs/montreal-80.states`, so this file is the one corrected. Part B describes code that does
not exist yet; StrategyExport's own tests will hold it to Part B.

Words used throughout: a **kind** is one of the 26 card kinds of A1; a **rank** is the
contract's rank, 1 to 10 (ROUND_CONTRACT.md); the **dealt order** is a shoe's 416 cards in the
order they come out, index 0 first; **depth** is how many cards have been dealt from the shoe
before a round starts; a **round start** (a *state*) is the shoe as it stands at that moment.

## Part A. The shoes

### A1. Cards: 26 kinds

A card is one of 26 kinds: its rank with the jack, queen and king kept apart from the ten, and
its colour. Kind `k` is `rank13 × 2 + colour`, where `rank13` runs A, 2, 3, ..., 10, J, Q, K
from 0 to 12 and `colour` is 1 for hearts and diamonds (red) and 0 for spades and clubs
(black). The contract's rank of a kind is 1 for the ace, 2 to 9 for those cards, and 10 for the
ten, jack, queen and king. A deck holds two cards of each kind (two black suits, two red
suits), so an eight-deck shoe holds 16 of each kind.

Colour and face matter only to counts that tag them: Red Seven tags red and black sevens
differently, the KISS counts tag by colour or by face, and so the engine tallies cards by kind
rather than by rank. Every other count tags all the kinds of a rank alike.

The block below is read by ShoeSpecTest; columns are kind, rank13, card, contract rank, colour.

```shoe-spec-kinds
0   0  A   1   black
1   0  A   1   red
2   1  2   2   black
3   1  2   2   red
4   2  3   3   black
5   2  3   3   red
6   3  4   4   black
7   3  4   4   red
8   4  5   5   black
9   4  5   5   red
10  5  6   6   black
11  5  6   6   red
12  6  7   7   black
13  6  7   7   red
14  7  8   8   black
15  7  8   8   red
16  8  9   9   black
17  8  9   9   red
18  9  10  10  black
19  9  10  10  red
20  10 J   10  black
21  10 J   10  red
22  11 Q   10  black
23  11 Q   10  red
24  12 K   10  black
25  12 K   10  red
```

So kinds 18 to 25 are the ten-valued cards and kinds 0 and 1 the aces.

### A2. Constants

Every constant the shoes depend on, read by ShoeSpecTest. Hexadecimal values are 64-bit
two's-complement integers written as Java long literals; `q0` and `u0` are the IEEE doubles
nearest to the decimals given, which is what Java's `Double.parseDouble` and any correctly
rounding parser produce.

```shoe-spec-constants
decks = 8
cards = 416
kinds = 26
cards.per.kind.per.deck = 2
seed.main = 20260928
shoes.main = 60000
q0 = 0.008
u0 = 0.02
cut.70 = 291
cut.75 = 312
cut.80 = 332
stream.cards = 0
stream.valuation = 1
mix.seed = 0x9E3779B97F4A7C15
mix.shoe = 0xBF58476D1CE4E5B9
mix.stream = 0x94D049BB133111EB
mix.shift1 = 30
mix.mult1 = 0xBF58476D1CE4E5B9
mix.shift2 = 27
mix.mult2 = 0x94D049BB133111EB
mix.shift3 = 31
rule.hits.soft.17 = true
rule.blackjack.payout = 1.5
rule.max.hands.aces = 2
rule.max.hands.other = 4
rule.hit.split.aces = false
rule.double.after.split = 2 3 4 5 6 7 8 9 10
rule.blackjack.on.split.pairs = false
rule.surrender = false
rule.surrender.after.split = false
chart.decisions = 253440
chart.fingerprint = 1af534aac9abcb39
rounds.70 = 3197853
rounds.75 = 3426592
rounds.80 = 3644432
valued.70 = 45495
valued.75 = 52611
valued.80 = 60743
weight.70 = 3222940
weight.75 = 3453206
weight.80 = 3667722
```

`seed.main` is the seed of `runs/montreal-80.states`, the main state file every published
figure is scored on. The three `mix.*` multipliers of the first line of `mix` (A3) are the
"three constants" PLAN_COMPUTE.md 1.1 names; the finalizer after them is Stafford's variant 13
of the 64-bit mix, the same one java.util.SplittableRandom uses inside. The `rule.*` lines are
Montreal's rules as the engine plays them (`HouseRules.getMtlCasino25MinBlackjackParams`
through `RoundRules.from`), in ROUND_CONTRACT.md's terms: `max.hands` counts every hand of the
round, so aces split once and other pairs split to four hands, and `double.after.split` lists
the ranks whose split hands may double. The `chart.*`, `rounds.*`, `valued.*` and `weight.*`
lines are explained in B1 and A8.

### A3. The layout, the shuffle and the valuation draw, as code

The block below is the reference: the simulator may copy it as it stands (it uses `java.util`
only), and ShoeSpecTest compiles exactly this text and holds every method to the engine. The
prose of A4 to A6 says the same in words.

- **Layout.** Before shuffling, the 416 cards lie deck by deck, and within a deck kind by kind
  from 0 to 25, two of each kind side by side: kinds `0 0 1 1 2 2 ... 25 25`, eight times.
- **The shuffle.** Shoe number `n` of a run with seed `s` is shuffled by its own generator,
  `new java.util.SplittableRandom(mix(s, n, 0))`, with a Fisher–Yates shuffle from the top index
  down: for `i` from 415 down to 1, `j = nextInt(i + 1)`, then cards `i` and `j` swap. The
  result, index 0 first, is the dealt order. No card is burned.
- **The valuation draw.** A second generator of the shoe's own,
  `new java.util.SplittableRandom(mix(s, n, 1))`, makes exactly one `nextDouble()` draw at every
  round start, in order, whether or not the state is valued. The state is valued when the draw
  is less than `q` (strictly). So the cards never depend on `q`, and a larger `q0` values every
  state a smaller one did.
- **q.** With `left[r]` the cards of rank `r` left and `N` their total,
  `u = (1/N) Σ_r (left[r] − N·p_r)² / (N·p_r)`, `p_r` being 1/13 for ranks 1 to 9 and 4/13 for
  rank 10, and `q = min(1, q0 · (1 + u/u0))`. D1 asks for q to the bit, so the order of the
  floating-point operations matters: follow the code, rank 1 to rank 10, `expected = N·w/13`
  with `w` 1.0 or 4.0 multiplied first, and `u` divided by `N` at the end.

```java ShoeSpec.java
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.ToIntBiFunction;

/** SHOE_SPEC.md's reference code: the shoe, its shuffle, its round starts and its valuation draw. */
final class ShoeSpec {

    static final int DECKS = 8;
    static final int KINDS = 26;
    static final int CARDS = 52 * DECKS;

    private ShoeSpec() {
    }

    /** The contract's rank of a kind: 1 for the ace, 2 to 9, and 10 for the ten, J, Q and K. */
    static int rankOf(int kind) {
        int rank13 = kind / 2;
        return rank13 >= 9 ? 10 : rank13 + 1;
    }

    /** Hearts and diamonds. */
    static boolean red(int kind) {
        return kind % 2 == 1;
    }

    /** The seed of shoe's generator on one stream: 0 shuffles the cards, 1 draws the valuation. */
    static long mix(long seed, long shoe, long stream) {
        long z = seed * 0x9E3779B97F4A7C15L + shoe * 0xBF58476D1CE4E5B9L + stream * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The shoe before shuffling: deck by deck, kinds 0 to 25, two of each side by side. */
    static int[] layout() {
        int[] cards = new int[CARDS];
        int c = 0;
        for (int d = 0; d < DECKS; d++) {
            for (int k = 0; k < KINDS; k++) {
                cards[c++] = k;
                cards[c++] = k;
            }
        }
        return cards;
    }

    /** Shoe number shoe of the run with this seed, as kinds in the order they are dealt. */
    static int[] shuffled(long seed, int shoe) {
        int[] cards = layout();
        SplittableRandom rnd = new SplittableRandom(mix(seed, shoe, 0));
        for (int i = CARDS - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
        return cards;
    }

    /** The cards left by rank 1 to 10 (index 0 unused), from the cards dealt by kind. */
    static int[] ranksLeft(int[] dealt) {
        int[] left = new int[11];
        for (int k = 0; k < KINDS; k++) {
            left[rankOf(k)] += 2 * DECKS - dealt[k];
        }
        return left;
    }

    /** u: the chi-square distance of the cards left from a full shoe's proportions, per card. */
    static double unusualness(int[] left) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += left[r];
        }
        if (n == 0) {
            return 0;
        }
        double u = 0;
        for (int r = 1; r <= 10; r++) {
            double expected = n * (r == 10 ? 4.0 : 1.0) / 13;
            double d = left[r] - expected;
            u += d * d / expected;
        }
        return u / n;
    }

    /** q: the chance a state with these cards left is valued. */
    static double rate(int[] left, double q0, double u0) {
        if (u0 == 0) {
            return q0;
        }
        return Math.min(1, q0 * (1 + unusualness(left) / u0));
    }

    /** A round start: the cards dealt before it, by kind, and whether it was valued. */
    record Start(int shoe, int round, int depth, int[] dealt, double q, boolean valued) {
    }

    /**
     * Deals one shoe to the cut card: a round starts while fewer than cut cards have been
     * dealt. roundEnd.applyAsInt(ranks, from) must return the index of the first card that the
     * round starting at ranks[from] does not use, the round played by the chart (A4); ranks
     * holds the contract's rank of each card in dealt order.
     */
    static List<Start> walk(long seed, int shoe, int cut, double q0, double u0,
                            ToIntBiFunction<int[], Integer> roundEnd) {
        int[] cards = shuffled(seed, shoe);
        int[] ranks = new int[CARDS];
        for (int i = 0; i < CARDS; i++) {
            ranks[i] = rankOf(cards[i]);
        }
        SplittableRandom valuation = new SplittableRandom(mix(seed, shoe, 1));
        List<Start> starts = new ArrayList<>();
        int[] dealt = new int[KINDS];
        int depth = 0;
        int round = 0;
        while (depth < cut) {
            double q = rate(ranksLeft(dealt), q0, u0);
            boolean valued = valuation.nextDouble() < q;
            starts.add(new Start(shoe, round, depth, dealt.clone(), q, valued));
            int next = roundEnd.applyAsInt(ranks, depth);
            for (int i = depth; i < next; i++) {
                dealt[cards[i]]++;
            }
            depth = next;
            round++;
        }
        return starts;
    }

    /**
     * chart.tsv's order (B1): decision n of the 253,440, as {up, total, soft, pair, fromSplit,
     * first, legal}, booleans as 0 or 1. legal's bits: 1 Hit, 2 Double, 4 Split, 8 Surrender.
     */
    static int[][] chartDecisions() {
        int[][] out = new int[253_440][];
        int n = 0;
        for (int up = 1; up <= 10; up++) {
            for (int total = 4; total <= 21; total++) {
                for (int soft = 0; soft <= 1; soft++) {
                    for (int pair = 0; pair <= 10; pair++) {
                        for (int fromSplit = 0; fromSplit <= 1; fromSplit++) {
                            for (int first = 0; first <= 1; first++) {
                                for (int legal = 0; legal < 16; legal++) {
                                    out[n++] = new int[]{up, total, soft, pair, fromSplit, first, legal};
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The chart's fingerprint from its answers in chart.tsv's order: 0 Stand, 1 Hit, 2 Double, 3 Split, 4 Surrender. */
    static String fingerprint(int[] moves) {
        long h = 1125899906842597L;
        for (int m : moves) {
            h = 31 * h + m;
        }
        return Long.toHexString(h);
    }
}
```

### A4. Which cards a round takes

The engine's shoes are walked with every round played by the chart (B1), heads-up, one hand
dealt, whatever count is later scored on them. So the cards a round takes are fixed by the
dealt order, the chart and the rules of A2, and the simulator's own chart walk must take
exactly the same ones. A round that starts at index `from` takes cards `from`, `from + 1`, ...
in order, never skipping one, and its last card is the one before `next`:

1. **The deal.** Four cards: the player's first card is card `from`, the dealer's up-card
   `from + 1`, the player's second card `from + 2`, the hole card `from + 3`. So `next` is at
   least `from + 4`. Nothing is burned, at the start of the shoe or anywhere else.
2. **The peek.** If the up-card is an ace and the hole card ten-valued, or the up-card
   ten-valued and the hole card an ace, the dealer has a natural and the round ends on these
   four cards. No decision is asked. (Insurance, decided before the peek on the deal's play
   key, takes no card.)
3. **The player's natural.** If the player's two cards are an ace and a ten-valued card and the
   dealer has no natural, the round ends on the four cards.
4. **The dealt hand** is played by the chart. Each decision is looked up in chart.tsv by the
   up-card, the hand's total (aces 1, plus 10 once if that stays at 21 or under), whether it is
   soft (that 10 was added), its pair rank (the rank of exactly two equal-rank cards, else 0),
   whether it came from a split, whether this is its first decision (on exactly two cards, before
   anything is done to it), and which moves are legal. Every card drawn is the next card in the
   dealt order.
   - On the dealt hand's first decision Hit, Double and Split (on any pair) are legal, Surrender
     never is.
   - **Stand**: the hand ends. **Double**: one card, and the hand ends. **Hit**: one card; while
     the total is under 21 the chart is asked again, now not a first decision, with only Hit and
     Stand legal; Stand ends the hand and Hit draws another card. Over 21 the hand busts and
     ends. A hand that reaches exactly 21 is asked once more with only Stand legal, which takes
     no card.
5. **Splits.** A pair may be split when the round would then hold no more hands than the limit
   (2 for aces, 4 for every other rank; every hand of the round counts, finished or not). The
   hand becomes two hands of one card each. The next card goes to the first new hand and the
   card after it to the second; then the first is played to its end, including any split of its
   own, which works the same way in its place, and only then the second. A split hand's first
   decision is on its two cards: Hit is legal when the total is under 21 and the hand is not a
   split ace; Double is legal unless it is a split ace (double after split on ranks 2 to 10);
   Split is legal on a pair within the limit; Surrender never. A split ace therefore takes its
   one card and stands, and a split ace that draws a ten, or a split ten that draws an ace, is an
   ordinary 21, not a blackjack. Its later decisions are as in 4.
6. **The dealer.** Once every hand has ended: if at least one hand stood, or doubled without
   passing 21, the dealer turns the hole card and draws the next cards in order while the total
   is under 17, or is a soft 17 (the dealer hits soft 17). If every hand busted, the dealer draws
   nothing.

`next` is then the index after the last card the round drew. The last round of a shoe starts
with at least 85 cards left, and no round of the 60,000 main shoes runs out (ShoeSpecTest deals
them all); the engine would stop with an error if one did.

Two consequences a simulator writer should not miss. The hole card is dealt fourth, before any
player draw, so a player's hits take cards from `from + 4` on. And a split's two second cards
are both drawn before either split hand is played, which is ROUND_CONTRACT.md's order.

### A5. The walk: round starts and the cut card

Shoe `n` is dealt from depth 0. A round starts while the depth is less than the cut, 332 for the
main file; the state recorded at that start holds the shoe, the round's number within the shoe
(from 0), the depth, the cards dealt so far by kind, and `q`. Then the valuation draw is made (A3),
then the round is played (A4) and every card it took is added to the tallies, and the depth
becomes `next`. Once the depth is 332 or more no round starts: the shoe ends, and the next shoe
is a fresh shuffle. Shoes are numbered 0 to 59,999 and are independent: shoe
`n` depends only on the seed and `n`, so any shoe can be dealt alone.

The engine deals to cut 332 once. The 70% and 75% games are the same walk stopped earlier:
their round starts are exactly the 80% walk's round starts whose depth is below 291 or 312, with
the same valuation draws. The simulator deals to 332 and filters by depth, as the engine does.

### A6. When each card enters the count

A count's tally at any moment is over the cards the player has seen. Precisely:

- **At a round start**, every card of every earlier round of the shoe is counted: both player
  cards, the up-card, the hole card, every card drawn by any hand, split hands included, and every
  card the dealer drew. Each round's cards are the contiguous block `from` to `next − 1` of the
  dealt order, and they enter the tallies all at once, when the round ends. So a state's tallies
  are exactly the kinds of the first `depth` cards. The **bet key** is computed from these
  tallies and the depth (B2), before any card of the round is seen.
- **At the dealt hand's first decision, and for insurance**, the count adds the player's two
  cards and the up-card, three cards seen, never the hole card: the deal's **play key**.
- **At a later decision of the dealt hand** (full-play strategies only), the count adds every
  card the player has drawn to that hand so far: `3 + draws` cards seen. The hole card is still
  not seen, so it is counted among the cards left.
- **On split hands** the count does not move: every decision of every split hand, and a resplit,
  is keyed on the deal's play key (FULL_INDEX_PLAYS.md section 1; it keeps the exact split
  valuation possible). This is a convention, and the simulator's variant with a count that keeps
  moving through split hands measures what it changes (PLAN_COMPUTE.md 1.5).
- **After the round** every card it took enters the tallies, the hole card included, as above.

Insurance is decided on the deal's play key, before the peek. Nothing else of a round depends on
a count: the cards taken do not, since the chart plays them (A4).

### A7. Penetrations and folds

A penetration's states are the valued round starts whose depth is below its cut: 291, 312 and 332
cards for 70%, 75% and 80% of 416 (`SystemScorer.CUTS`). A state's **fold** is its shoe's parity,
`shoe & 1`. A strategy file of fold `f` (B2) was fitted on the other fold's states and is scored
on fold `f`'s: every state of fold `f` is played by the fold-`f` strategy, and only by it.

A state's weight in every engine figure is `1/q`: a valued state stands for `1/q` round starts.

### A8. What the main file's shoes must give

Dealing shoes 0 to 59,999 of seed 20260928 to cut 332, with `q0` 0.008 and `u0` 0.02, gives the
totals in A2's block, which are D1's first targets:

| Penetration | Cut | Round starts (`rounds.*`) | Valued (`valued.*`) | Σ 1/q over valued, rounded (`weight.*`) |
|---|---|---|---|---|
| 70% | 291 | 3,197,853 | 45,495 | 3,222,940 |
| 75% | 312 | 3,426,592 | 52,611 | 3,453,206 |
| 80% | 332 | 3,644,432 | 60,743 | 3,667,722 |

They were first printed in the header of `runs/report-final-26f0724.md`, which dealt the shoes
again from the seed and refused the state file unless every valued state it found was the
file's next record, in round, depth, tallies and q (`AllStates.replay`). ShoeSpecTest deals the
60,000 shoes with this file's code and the engine's round, and requires these figures; it reads
nothing from the state file. The rest of D1 (every valued state card for card, q to the bit) is
against states.bin (B3), which the exporter copies from the state file.

### A9. java.util.SplittableRandom, for a port

The simulator, in Java, uses `java.util.SplittableRandom` itself. For anyone porting the shuffle
to another language, here is what the three calls used do in OpenJDK 25.0.1, the JDK this
repository's runs use (`java.util.SplittableRandom` and the defaults it inherits from
`java.util.random.RandomGenerator` and `jdk.internal.util.random.RandomSupport`, read from that
JDK's `src.zip` on 9 October 2026). ShoeSpecTest compiles this block and holds it equal to the
JDK's class on many seeds and every bound from 1 to 416. All arithmetic is on 64-bit two's
complement integers that wrap; `>>>` is the unsigned shift.

```java PortableSplittableRandom.java
/** java.util.SplittableRandom(long seed)'s nextLong, nextInt(bound) and nextDouble, step by step. */
final class PortableSplittableRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private long seed;

    PortableSplittableRandom(long seed) {
        this.seed = seed;
    }

    private long nextSeed() {
        seed += GOLDEN_GAMMA;
        return seed;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int mix32(long z) {
        z = (z ^ (z >>> 33)) * 0x62A9D9ED799705F5L;
        return (int) (((z ^ (z >>> 28)) * 0xCB24D0A5C88C35B3L) >>> 32);
    }

    long nextLong() {
        return mix64(nextSeed());
    }

    int nextInt() {
        return mix32(nextSeed());
    }

    /** Uniform on 0 to bound - 1: a power of two takes the low bits; otherwise draws are rejected until unbiased. */
    int nextInt(int bound) {
        int m = bound - 1;
        int r = nextInt();
        if ((bound & m) == 0) {
            return r & m;
        }
        for (int u = r >>> 1; u + m - (r = u % bound) < 0; u = nextInt() >>> 1) {
            // u + m - r overflows to a negative int exactly when u falls in the last, partial block
        }
        return r;
    }

    /** The top 53 bits of nextLong, times 2^-53: uniform on [0, 1). */
    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
```

So one card shuffle uses 415 or a few more `nextInt` draws (a rejection takes another), and the
valuation stream one `nextLong` per round start.

## Part B. The export bundle: the format StrategyExport must write

PLAN_COMPUTE.md 1.2 names the files; this part fixes their format, so that the simulator's author
can write the reader before the exporter exists and the exporter's author writes exactly this.
Nothing here is built yet. Where the plan left a detail open, B6 lists the choice made here.

### B0. The bundle

`StrategyExport` writes one directory, `runs/sim/export-<sha>/`, `<sha>` being the exporter's
commit, abbreviated to 7 hexadecimal digits:

| File | What |
|---|---|
| `manifest.sha256` | one line per other file, `<sha256 hex>  <path>` (two spaces), sorted by path, as `shasum -a 256` prints and `shasum -a 256 -c` checks |
| `index.tsv` | every other file with its size in bytes, and for each strategy file its row, penetration, fold and scope (B2) |
| `chart.tsv` | the chart's answer to every decision (B1) |
| `strategy-<slug>-p<P>-f<F>.txt` | one fitted strategy: one row, one penetration, one fold (B2) |
| `states.bin` | every valued state of the main file, and the engine's bet key, round value and bets per row and penetration (B3) |
| `keys-sample.tsv` | the engine's bet and play keys for a sample of states and deals (B4) |
| `exact.tsv` | the test folds' sums and per-shoe moments of every exported figure (B5) |

**Conventions for every text file.** UTF-8, lines ending in a single `\n`, the last line too;
fields separated by one tab (in this file's examples and in the keyword table of B2.1, the gap
between fields is a tab character); no field contains a tab or a line break. A line starting with `#`
is a comment and is skipped, except where a section below gives a `#` line a meaning. Integers
are in decimal with an optional leading `-`. A double is written by Java's `Double.toString`
(on Java 19 and later the shortest decimal that reads back to the same double) and read with
`Double.parseDouble`, so it comes back to the bit; `NaN` and `Infinity` never appear. Booleans
are `0` or `1`.

`index.tsv` begins with the line `path	bytes	row	pen	fold	scope` and has one line per file
of the bundle other than itself and `manifest.sha256`, sorted by path; the last four fields are
`-` for files that are not strategy files. The exporter refuses to write a bundle whose total
size it has not measured, and prints the total in its log (PLAN_COMPUTE.md 1.8).

A **row** is one line of the report: a count with its play and its bets (`SystemScorer.Entry`),
named exactly as the report names it, for example `Hi-Lo = Hi-Lo Lite` or
`Hi-Opt II + ace side count`. Its **slug** is the name in lower case with every run of
characters other than `a`-`z` and `0`-`9` replaced by one `-`, and leading or trailing `-`
removed; the exporter refuses two rows with the same slug. `<P>` is 70, 75 or 80 and `<F>` is 0
or 1.

### B1. chart.tsv

The chart (`BasicStrategy`) plays every round of the walk (A4), every decision a first-decision
strategy leaves to it, and every non-contested later decision of a full-play strategy. Its
answers do not depend on the number of cards in the hand beyond whether the decision is a first
decision, nor on whether the hand came from a split (only through which moves are legal), nor on
the shoe or a count.

```
# chart.tsv: SHOE_SPEC.md B1, format 1
# fingerprint 1af534aac9abcb39
up	total	soft	pair	fromSplit	first	legal	move
1	4	0	0	0	0	0	S
...
```

- Line 1 is the comment above. Line 2 is `# fingerprint ` and the chart's fingerprint, which
  must equal `chart.fingerprint` in A2. Line 3 is the header shown.
- Then exactly 253,440 lines, in the order `ShoeSpec.chartDecisions()` gives (A3): up-card 1
  to 10, then total 4 to 21, soft 0 then 1, pair rank 0 to 10, fromSplit 0 then 1, first 0
  then 1, and `legal` 0 to 15, the innermost. `legal` is a bit set: 1 Hit, 2 Double, 4 Split,
  8 Surrender (Stand is always legal).
- `move` is one letter: `S` Stand, `H` Hit, `D` Double, `P` Split, `R` Surrender, numbered 0
  to 4 in that order for the fingerprint.
- The reader rehashes: `ShoeSpec.fingerprint` over the 253,440 move numbers in file order must
  give line 2's fingerprint, or the file is refused.
- Many lines are decisions no round can reach (a soft 4, a pair of tens with a total of 7);
  their answers are still the chart's, so the fingerprint covers them.

A decision is looked up by its up-card, total, softness, pair rank, whether the hand came from a
split, whether it is a first decision, and its legal moves, as A4 says. A hand at a first
decision has two cards; a hand at any other decision has three or more.

### B2. strategy-\<slug\>-p\<P\>-f\<F\>.txt

One file holds everything one row plays at one penetration on the states of one fold: the keys,
the first decisions, insurance, the later plays if it has them, and the bets. A file of fold
`F` was fitted on the other fold's states and plays the states of fold `F` (A7). The simulator
plays a state, or a fresh shoe's round, of fold `F` with the fold-`F` file only; on fresh shoes
(check P3) each fold's file plays every shoe and the two are pooled by the folds' test weights
(PLAN_COMPUTE.md 1.6).

**B2.1 Lines.** Each line is a keyword and its fields, in this order; a keyword marked once
appears exactly once.

| Keyword and fields | Once? | Meaning |
|---|---|---|
| `format	strategy	1` | first line | this format |
| `row	NAME` | once | the row's name, exactly as the report prints it |
| `scope	SCOPE` | once | `first`: count-based first decision, the chart after it; `full`: count-based play on every decision (B2.4); `betting`: the chart on every decision, the count's insurance and bets |
| `pen	P	CUT` | once | 70, 75 or 80, and its cut, 291, 312 or 332 |
| `fold	F	TRAINED` | once | the fold played, 0 or 1, and the weight of the training states fitted on (Σ 1/q) |
| `playcount	NAME` | once | the count whose key plays: the row's count, or for a betting variant its base |
| `tags	t0 … t25` | once | that count's tags by kind (A1), 26 integers at its whole-number scale |
| `scale	S` | once | what the published tags were multiplied by to make them whole: 1 or 2 for a catalog count, 10,000 for a count of our own (COUNT_DESIGN.md), whose tags are real numbers kept to four decimals |
| `mode	MODE` | once | `true` or `running` |
| `divisor	D` | once | the key divisor, at least 1 (1 for every catalog count) |
| `decksum	X` | once | what 52 cards add to the count: Σ over kinds of 2 × tag; 0 exactly when the count is balanced |
| `betcount	NAME` | once | the count whose key bets: the row's count, its ace side-count variant, or its betting vector's variant |
| `bettags	b0 … b25` | once | the tags the bet key counts, by kind (equal to `tags` unless the row bets on its own vector) |
| `betdecksum	Y` | once | Σ over kinds of 2 × bet tag |
| `ace	M` | once | the ace side count's weight for betting, 0 for none |
| `groups	G` | once | the number of first-decision groups |
| `group	g	lo	hi` | G lines, g = 0 to G − 1 | the lowest and highest training play key in group g |
| `first	g	p1	p2	up	M…` | scope `first` and `full`: for every group and every deal but the player's naturals | the deal's legal first moves, best first |
| `insure	g	p1	p2` | any number | the ace-up deal (p1, p2, ace) is insured in group g; a deal not listed is not insured |
| `lgroups	L` | scope `full` only, once | the number of later-play groups |
| `lgroup	l	lo	hi` | L lines | as `group`, for the later-play keys |
| `later	l	total	soft	up	S\|H` | scope `full`: every later group × the 110 contested spots | stand or hit |
| `plan	LABEL	RULE	EXIT` | one per plan | a bet plan (B2.5) |
| `bet	LABEL	KEY	UNITS	PLAY` | for every plan and every key of the fold's test states | the bet at that key |
| `unseen	nearest	lower` | once | the rule for a key with no `bet` line (B2.5) |
| `end` | last line | the file is whole |

Lines appear in the order of the table. Within a keyword: `group` and `lgroup` lines by g (or l);
`first` lines by g, then up-card, then p1, then p2 (so within a group in the engine's deal order,
`Deals`); `insure` lines by g, then p1, then p2; `later` lines by l, then up-card, then hard before
soft, then total; `plan` lines in the order the plans are scored; `bet` lines by plan in `plan`
order, then by key, increasing. A deal is `p1 ≤ p2` and `up`, ranks 1 to 10: the player's two
cards as an unordered pair and the dealer's up-card. The player's naturals (1, 10, any up) have no first decision and no line; so
each group has 540 `first` lines, one for each of the 550 deals less the 10 naturals.

**B2.2 Keys.** All three keys are whole numbers, computed from integers; where a ratio is
rounded it is rounded once, from the double nearest to the exact ratio, to the nearest whole
number with ties toward zero:

    round(n, d) = (int) (signum(x) · ceil(|x| − 0.5)),   x = (double) n / (double) d

in Java's double arithmetic (`CountKeys.roundedRatio` and `GranularCount.roundToGrain`). With
`RC` the sum of the tags (`tags`) of every card of the shoe's tallies, `RCb` the same with the
bet tags, `depth` the cards dealt before the round, `left = 416 − depth`, and `aces` the aces
left (16 + 16 less the aces dealt, kinds 0 and 1):

- **Bet key**, at the round start: for `mode true`,
  `round(52 · RCb − Y · depth + M · (52 · aces − 4 · left), left · D)`; for `mode running`,
  `RCb` if D is 1, else `round(RCb, D)`. (`M` is 0 unless the row bets with an ace side count.)
- **Key at a decision**, with `T` the sum of the tags of the cards of this round seen so far and
  `s` how many they are (A6; 3 at the first decision and for insurance, so this is the deal's
  **play key**): for `mode true`, `round(52 · (RC + T) − X · (depth + s), (416 − depth − s) · D)`;
  for `mode running`, `RC + T` if D is 1, else `round(RC + T, D)`.

All products fit in a 64-bit integer; compute them in one. A colour or face count's key at a
decision depends on the kinds of the cards seen, not only their ranks; a simulator dealing real
cards knows them.

**B2.3 Groups.** The group of a key `k` is the first g, in order, whose `hi` is at least `k`;
the last group takes every key above its `hi`. So a key below every training key falls in group 0
and one above every training key in the last group. `lo` is for reading only. Later-play groups
work the same way over `lgroup` lines.

**B2.4 How a file is played.** Each decision of a round is answered as follows; the round's
cards and rules are A4's.

- **The bet**, at the round start, before any card: the bet key (B2.2) from the tallies, then
  the plan's bet at that key (B2.5).
- **Insurance**, when the up-card is an ace, before the peek: the deal's play key gives the group
  g; insure if and only if `insure g p1 p2` is listed. The player's natural (1, 10) is an ace-up
  deal like any other here, and insuring it is what the table calls even money. Insurance costs
  half the bet and pays 2 to 1: +1 bet if the hole card is ten-valued, −0.5 bet if not. A
  betting-scope file insures this way too: its insurance is its count's, fitted in its play
  groups.
- **The dealt hand's first decision.** Scope `first` and `full`: the deal's play key gives the
  group g; play the first move of `first g p1 p2 up`, which is always legal on the dealt hand.
  Scope `betting`: the chart.
- **The dealt hand's later decisions**, on three cards or more. Scope `full`, at a contested spot
  (hard 12 to 16, or soft 13 to 18, against any up-card): the key at the decision (B2.2, with
  `s = 3 + draws`) gives the later group l; stand or hit as `later l total soft up` says. At any
  other spot, and in scopes `first` and `betting`: the chart.
- **Split hands.** Scopes `first` and `betting`: the chart, every decision. Scope `full`: the
  count is frozen at the deal's play key (A6), which gives one first-decision group g and one
  later group l for every split hand of the round:
  - a split hand's first decision on (r, x), r the split rank: if r and x are an ace and a
    ten-valued card, stand; otherwise the moves of `first g min(r,x) max(r,x) up`, in order, and
    play the first that is legal on this split hand (A4's step 5). A resplit is this rule on
    (r, r), whose list may start with `P`. If none is legal, stand.
  - a split hand's later decision: at a contested spot, `later l total soft up`, and hit only if
    Hit is legal; elsewhere the chart.
  A file of scope `full` lists in `first` every legal move of the deal, best first by its fitted
  value, so this always finds the best legal move by the same fit. A (group, deal) that no
  training state reached lists the chart's order instead: the chart's move with every move
  offered, its move with Split taken away, then Stand (so a move may appear twice).
- **The payoff.** A dealer natural loses the bet, or pushes against a player's natural; a
  player's natural against no dealer natural is paid 3 to 2; every other round is settled hand
  by hand as ROUND_CONTRACT.md settles it (which values a round given no dealer natural; the
  engine's round values, B3, include the dealer's naturals). All of it is times the bet, plus the
  insurance result times the bet.

`first` lines for scope `first` are read the same way, but only their first move is used.
`later` cells that no training state reached, or whose fitted sum was exactly zero, are already
written as the chart plays them.

**B2.5 Plans and bets.** A plan is a rule that turns a key into a bet in units:

- `plan	LABEL	RULE	EXIT`: `LABEL` is the plan's name, ASCII letters, digits and `.,+-`
  only (`same-1` to `same-8`, `own-1` to `own-8`, `played-1` to `played-8` for the report's
  plans, and PLAN_COMPUTE.md section 3's names, such as `P16`, `S16`, `B0,8`, `X-1.0,8`). `RULE`
  is `same` when the row hands out the same schedule's bets in order of its fitted advantage, or
  `own` when each key bets its own fitted advantage; the simulator does not need it to play.
  `EXIT` is `-`, or for a plan that leaves the table for the rest of the shoe a key: the player
  stays seated while the lowest bet key of every round start of the shoe so far, this one
  included, is at least `EXIT`, and bets 0 on every later round of the shoe once it is not.
- `bet	LABEL	KEY	UNITS	PLAY`: at bet key `KEY` the round is played with probability `PLAY`
  at `UNITS` units, and sat out (bet 0) otherwise. `PLAY` is 1 except on the one block of keys of
  a back-counting plan that straddles its entry threshold (PLAN_COMPUTE.md 3.2), and a key whose
  plan sits out is written with `UNITS` 0 and `PLAY` 0. The simulator draws the play-or-sit coin
  from a stream of its own. The engine's figures use the expected bet, `UNITS × PLAY`, and so
  does states.bin (B3).
- Every plan has a `bet` line for exactly the keys that occur among the fold's test states at
  this penetration, in increasing key order; those are the keys the engine's figures use.
- `unseen	nearest	lower`: a key with no `bet` line (it can occur on fresh shoes) takes the bet
  of the listed key nearest to it, the lower one when two are equally near. The simulator counts,
  per file and plan, how many rounds and how much weight used this rule, and reports them.

The flat bet (1 unit, every round) needs no plan.

### B3. states.bin

Every valued state of the main file, and, for each exported row and penetration, what the
engine says of each: its bet key, its round value under the fold's fitted play and insurance,
and its bet under each plan. Check D1 compares the simulator's own valued states with the
records; check P2 re-deals each state's cards left and compares the simulator's mean payoff
with the engine's round value, weighted by `1/q` and the bet.

Binary, big-endian (Java's `DataOutputStream`): `i8`, `i32` and `i64` are two's-complement
integers of 1, 4 and 8 bytes, `f64` an IEEE 754 double written by `writeDouble` (its raw bits:
the value exactly), and `str` an `i32` byte count followed by that many bytes of UTF-8.

| Part | Fields |
|---|---|
| header | `magic` 8 bytes, ASCII `SIMSTATE`; `version` i32 = 1; `seed` i64; `q0` f64; `u0` f64; `cut` i32; `shoes` i32; `n` i32, the number of valued states |
| n records, in the state file's order (shoe, then round) | `shoe` i32; `round` i32; `depth` i32; `q` f64; `fold` i8, `shoe & 1`; `dealt` 26 × i8, the tallies by kind (A1) |
| `sections` i32 | how many (row, penetration) sections follow |
| each section | `row` str, the row's name; `pen` i32, 70, 75 or 80; `cut` i32; `plans` i32, then that many `label` str, in the order of the row's strategy files' `plan` lines; `m` i32, the states of this penetration (depth below cut), then m state entries in record order |
| each state entry | `index` i32, the record's place, 0 to n − 1; `betKey` i32; `rvPlay` f64; `rvInsurance` f64; then one `bet` f64 per plan |
| trailer | `magic` 8 bytes, ASCII `SIMSTEND` |

- `seed`, `q0`, `u0` and `cut` are the state file's header (20260928, 0.008, 0.02, 332);
  `shoes` is 60,000, and `n` 60,743.
- A record's `q` is the state file's own double, to the bit, and is the state's weight's
  inverse: every engine figure weights the state `1/q`.
- `rvPlay` is the round value per unit bet under the fitted play of the fold the state belongs
  to (the fold-`fold` strategy file), before insurance, from the engine's exact values: the round
  as it starts at that state, averaged over every card that could come. For a betting-scope row
  it is the chart's value. `rvInsurance` is what that fold's fitted insurance adds, also per unit.
  The round value the bets multiply is their sum.
- `bet` is the engine's expected bet under the plan at the state's bet key, `UNITS × PLAY` from
  the fold's file; for a plan with an exit, 0 once the shoe's lowest bet key so far has fallen
  below it.
- The sections are sorted by row name, then penetration. A row of the report with no strategy
  file in the bundle has no section.

So a P2 estimate for a row, penetration and plan is `Σ (1/q) · bet · payoff / Σ (1/q)` over the
section's states, against the engine's `Σ (1/q) · bet · (rvPlay + rvInsurance) / Σ (1/q)`; with
the flat bet, `bet` is 1. The exporter's round trip (PLAN_COMPUTE.md 1.2) checks that these
records, scored as the engine scores, give the report's figures within 1e-12 relative.

### B4. keys-sample.tsv

The engine's keys for a sample of states and deals, for every count the comparison scores, so
that check D2 can hold the simulator's own key code (from its own reading of
counting-systems.json and B2.2) to the engine's for all of them, simulated or not. The values in
the example are illustrative.

```
# keys-sample.tsv: SHOE_SPEC.md B4, format 1
# sample seed 20261009
line	state	shoe	round	depth	p1	p2	up	<count 1>	<count 2>	…
bet	0	0	0	0	-	-	-	0	0	…
play	0	0	0	0	1	1	1	0	0	…
```

- Line 1 is the comment above; line 2 is `# sample seed ` and the seed the exporter drew the
  sample with. Line 3 is the header: eight fixed columns, then one column per count, named
  exactly as the report names the row. The counts are every catalog entry (duplicates made one,
  in catalog order), then the null count, then the registered counts, if any, then every betting
  variant (ace side-count variants, then the registered counts' own betting variants, if any).
  The header is the only place the list of counts is given, and the reader takes it from there.
- `state` is the record's place in states.bin, so the tallies and depth come from there; `shoe`,
  `round` and `depth` repeat them for reading.
- A `bet` line holds each count's bet key at the state (B2.2), `-` in `p1`, `p2` and `up`.
- A `play` line holds each count's play key for the deal (p1 ≤ p2, up): the key of the deal's
  three cards seen at the state, for the count that plays the column's row (a betting variant
  plays on its base, so its column repeats its base's key). For a count that tags some rank by
  colour or face, a deal's ranks do not fix its key, and the cell lists every key the deal can
  have with its chance, given the kinds left, the three cards drawn without replacement:
  `key:chance;key:chance;…`, in the order `CountKeys.tagSums` meets them, the chances adding to 1.
  A deal the cards left cannot make is `-` in those columns; the other columns still give its key.
- The lines: for 2,000 states drawn without replacement from the 60,743 with the sample seed,
  in record order, a `bet` line, then `play` lines for 10 deals drawn with the same generator
  from the 550, in deal order (up-card, then p1, then p2). The first 20 records also have `play`
  lines for all 550 deals, whether drawn or not, so every deal is covered. About 31,000 lines.
- The draw, exactly (`StrategyExport.sample`): one `java.util.SplittableRandom(seed)`. The states
  are the first 2,000 places of a Fisher–Yates shuffle of 0 to n − 1 run from the bottom up (for
  t from 0, swap place t with place `t + nextInt(n − t)`), sorted; all n when n is 2,000 or
  fewer. Then, for each state drawn, in record order, 10 deals the same way from 0 to 549, sorted.
  Each of the first 20 records has its `bet` line, drawn or not, and lists all 550 deals in place
  of the 10 drawn for it. The reader need not repeat the draw: the lines say which states and
  deals they are.

D2 requires every key to be equal and every chance to agree within 1e-12 relative.

### B5. exact.tsv

The engine's side of every comparison, with what its standard error needs when the fit is held
fixed. One line per strategy file and figure:

```
# exact.tsv: SHOE_SPEC.md B5, format 1
row	pen	fold	figure	metric	shoes	sumA	sumB	sumAA	sumAB	sumBB
```

- `row`, `pen` and `fold` name the strategy file. `figure` is `flat`, `play`, `insurance`, or
  `ev:LABEL` and `bet:LABEL` for each plan of the file. `metric` is the figure's index in
  `SystemScorer.METRICS` (0 `flat`, 1 `play`, 2 `insurance`, then for s = 1 to 8: 2 + s
  `ev:same-s`, 10 + s `bet:same-s`, 18 + s `ev:own-s`, 26 + s `bet:own-s`, 34 + s
  `ev:played-s`, 42 + s `bet:played-s`), or −1 for a figure the report does not have.
- For a state j of the file's fold in this penetration, with weight `w = 1/q`, let `x` be
  `rvPlay + rvInsurance` for `flat`, `rvPlay` for `play`, `rvInsurance` for `insurance`,
  `bet × (rvPlay + rvInsurance)` for `ev:LABEL` and `bet` for `bet:LABEL` (B3). For each shoe i
  of the fold, `a_i = Σ w·x` and `b_i = Σ w` over its states (both 0 for a shoe with none). Then
  `shoes` is the number of shoes in the fold (30,000, those with no state in this penetration
  included), `sumA = Σ a_i`, `sumB = Σ b_i`, `sumAA = Σ a_i²`, `sumAB = Σ a_i·b_i` and
  `sumBB = Σ b_i²`.

The fold's estimate is `sumA / sumB`. Pooled over the two folds, `R = (sumA₀ + sumA₁) /
(sumB₀ + sumB₁)` is the report's figure (`Entry.estimate[pen][metric]`, within 1e-12
relative). Shoes are independent, so with the fit held fixed the linearised standard error of
R over the `n = shoes₀ + shoes₁` shoes is
`sqrt(n/(n − 1) · Σ(sumAA − 2R·sumAB + R²·sumBB)) / Σ sumB`, the sums over both folds.
SIM_CHECK.md fixes the exact form it uses before the first pilot.

### B6. Choices this part made that the plan left open

PLAN_COMPUTE.md 1.2 names the files and what they hold; these details are this file's, and
StrategyExport follows them unless this file is changed first:

1. The two counts of a row are named in the strategy file (`playcount`, `betcount`) and their
   key parameters given (`tags`, `bettags`, `decksum`, `betdecksum`, `ace`, `divisor`, `mode`),
   with the formulas written once, in B2.2, rather than as text in every file. The plan's "each
   key formula as an exact integer ratio" is B2.2's ratios with the file's parameters in them.
2. Later-play groups get their own lines (`lgroups`, `lgroup`), since the engine grows them
   apart from the first-decision groups (FullIndexFit).
3. `first` lines list moves as the full-play fit ranks them (FullIndexFit.rankings), including
   the chart's order with repeats for a (group, deal) no training state reached, and are written
   for scope `first` too, where only the first move is read.
4. A key with no `bet` line takes the nearest listed key's bet, the lower on a tie, and the file
   says so (`unseen nearest lower`); the plan said "nearest present key" without a tie rule.
5. `bet` lines carry the coin's probability (`PLAY`) beside the units, so the back-counting plans
   of PLAN_COMPUTE.md 3.2 need no other line; an exit plan carries its exit key on its `plan`
   line.
6. states.bin keeps the round value in two parts, the play's and the insurance's, so P2 can be
   split by them; it holds per state the fold's own strategy's values only, since a state is
   scored by one fold's strategy.
7. exact.tsv gives per-shoe moments (`sumAA`, `sumAB`, `sumBB`) rather than a standard error, so
   that pooling the folds, or another SE formula, needs no second export.
8. keys-sample.tsv takes its list of counts from its header, and a betting variant's play column
   repeats its base's key.
9. The bundle has `index.tsv`, with every file's size and each strategy file's row, penetration,
   fold and scope, so the simulator need not open a file to know what it holds.

### B7. Where each line comes from, for the exporter's author

The simulator's author can skip this; it names engine code. For fold `f` at penetration `p`,
with `pen = scorer.pen(p)` and the play fitted on `pen.train[f]` under `pen.weight`, as the
estimate fits it (SystemScorer.foldPlay; for full play the play in force when
`FullIndexFit.Strategy` finishes, `now`, which at the pass cap is the best pass's play after its
clean-ups):

- `group`: the play's `Groups.lo` and `hi`; `lgroup`: `Strategy.later`'s.
- `first`: `FullIndexFit.rankings(st, rules, fit)[g][deal]`, which is `fit.move[g][deal]`
  followed by the other legal moves by `fit.sum`, or `chartRanking` where `fit.reach` is 0.
- `insure`: the (g, ace-up deal) cells whose `insuranceSums(ip, play.insuranceGroup, groups,
  pen.train[f], pen.weight)` entry is positive, as `insuranceValues` insures them.
- `later`: `Policy.stands[l × CELLS + cell]`, `S` when true, the spot from
  `FullIndexFit.spotOf(cell)`.
- `bet`: from `bets(..., detail)` for the fold: the keys `k` with test weight (`detail[k][0] > 0`)
  are `BetKeys.value[k]`; `same-s` is `detail[k][2 + s]`, `own-s` is
  `detail[k][2 + SPREADS + s]`, and `played-s` is `Math.floor(own + 0.5)`, as `bets` sums them.
- states.bin's `rvPlay` is `play.base[j]` (`pen.chart[j]` for a betting row) and `rvInsurance`
  the `insuranceValues` entry, j the state's place in the penetration.

The round trip of PLAN_COMPUTE.md 1.2 is what shows these choices right: the bundle read back
and scored must give `Entry.estimate` and `Entry.shoeSums` within 1e-12 relative.

## How this file is held to the engine

`test/ShoeSpecTest.java` (run it alone: `java -Xmx1g -jar lib/junit-platform-console-standalone-1.10.2.jar
--class-path build/classes:build/test-classes:lib/mongo-java-driver-3.12.14.jar --select-class
ShoeSpecTest`, from the repository's root, 17 tests in about 30 seconds):

- A2's block against `ShoeRun`, `SystemScorer.CUTS`, `StateRun.CUT_80` and `StateRun.montreal()`;
  A1's block against `ShoeRun.rankOf`, `red`, `simulatorRank` and `suit`.
- A2's mix constants, assembled as A3 describes, against `ShoeRun.mix` on 20,000 inputs, and
  A3's words on the shuffle and the valuation draw, coded from A2's constants, against
  `ShoeRun.shuffled` and `ShoeRun.deal`.
- A3's code, compiled from this file, against ShoeRun: `mix`, the layout, the shuffle on five
  seeds, u and q to the bit on 50,000 tallies, and the walk over all 60,000 main shoes, every
  round start compared in shoe, round, depth, tallies, q's bits and the valuation, with the
  totals of A8.
- A4's prose, transcribed in the test, against `ConcreteRound` in every round of those shoes
  (3,644,432 rounds).
- B1's order and the fingerprint against `BasicStrategy.fingerprint`, and the chart's
  independence of the number of cards and of a split.
- A9's code against `java.util.SplittableRandom`: every bound from 1 to 416 and 50 doubles and
  longs on 400 seeds.
- Nine planted changes to A3's code (a mix constant, a shift, the shuffle's direction, the
  layout, the valuation's stream, u's last division, a round at the cut card, a round's cards
  counted at its own start, the hole card left out of the count), each compiled and each seen.
- Part B's claims about the engine, each transcribed from this file's words: B2.2's keys against
  `CountKeys.betKey`, `playKey` and `keyAfter` for every count the comparison scores, every
  betting variant and counts of our own at four divisors; B2.3's group lookup against
  `Groups.of`; B5's figure numbers against `SystemScorer`'s metric indices; B2.1's 540 deals and
  110 contested cells in their order; B2.4's order for an unreached cell against
  `FullIndexFit.chartRanking`; and B2.4's play of split hands against `SplitPlay` on random
  tables.

Planted by hand on 9 October 2026, one at a time, in a copy of the source or of this file (each
copy compiled and the test run against it, the worktree untouched), to show the test fails when
the spec and the engine diverge in either direction:

| Planted | Where | Tests that failed (of the 11 then) | First failure |
|---|---|---|---|
| a mix multiplier changed | `ShoeRun.mix` | 5 of 11 | shoe 0 round 1: the spec's depth 5 tallies differ from ShoeRun's |
| shuffle from the bottom up | `ShoeRun.shuffled` | 3 | shoe 0 is shuffled differently |
| valuation on stream 2 | `ShoeRun.deal` | 3 | shoe 0 round 8 valued by ShoeRun, not by the spec |
| dealer draws even when every hand busted | `ConcreteRound` | 1 | A4's prose ends the round at another card |
| `q0 = 0.0081` | A2 | 1 | 46,011 states valued at 70%, not 45,495 |
| kind 13 written as black | A1 | 1 | kind 13 is red |
| `nextInt(i)` for `nextInt(i + 1)` (Sattolo's shuffle) | A3 | 3 | shoe 0 is shuffled differently |
| `mix.shift2 = 28` | A2 | 3 | the mix from A2's constants differs from ShoeRun.mix |
| `rule.max.hands.other = 3` | A2 | 1 | the engine splits to 4 hands |

Three more, planted the same way in the engine against Part B's checks, with 17 tests:

| Planted | Where | Tests that failed (of 17) | First failure |
|---|---|---|---|
| a key equal to a group's top goes to the next group | `SystemScorer.Groups.of` | 1 | key −16, the top of group 0, stays in group 0 by B2.3 and moves to group 1 in the planted engine |
| a decision's true count over the cards left at the round's start, the seen cards not taken out | `CountKeys.keyAfter` | 1 | Hi-Opt II at depth 0 with 5 cards seen |
| a split hand plays the first listed move even when it is not legal | `SplitPlay` | 1 | a split hand on hard 9 against a 2, where Split is not legal, is played Split |

Part B's files themselves are not tested here, since no code writes them yet. StrategyExport's
tests will read them back with a reader written from Part B, and its round trip will score them.
