import org.bson.Document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * A published card-counting system, as counting-systems.json at the root of the repository
 * records it. That file is the one home of the catalog: each system's tags, how its author
 * converts the count, its sources and what they left unconfirmed, and the systems left out
 * with the reason. This class reads the tags and the few facts a simulation needs.
 *
 * A tag belongs to a card, not a rank, because a few systems count by colour or face. Red
 * Seven counts the red sevens and not the black; the KISS counts count the black deuces and
 * not the red, and KISS I counts the picture cards but not the ten. Every other system tags
 * a card by its rank, the ten-valued ranks alike.
 */
public final class CountingSystem {

    public static final Path FILE = Paths.get("counting-systems.json");

    public final String name;
    public final List<String> aliases;
    /** "primary-source" or "two-sources": how the tags were confirmed. */
    public final String confidence;
    /** Whether a full deck sums to zero. */
    public final boolean balanced;
    /** The largest tag, in absolute value, once the tags are scaled to whole numbers. */
    public final int level;
    /** Whether the count is converted to a true count; if not, it is played as it runs. */
    public final boolean trueCounted;

    /** Indexed by Rank ordinal, then 0 for a black suit and 1 for a red one. */
    private final double[][] tags;

    private CountingSystem(Document d) {
        name = d.getString("name");
        aliases = Collections.unmodifiableList(new ArrayList<>(d.getList("aliases", String.class)));
        confidence = d.getString("confidence");
        balanced = d.getBoolean("balanced");
        level = d.getInteger("level");
        String count = d.getString("count");
        if (!count.equals("true") && !count.equals("running")) {
            throw new IllegalArgumentException(name + ": count must be \"true\" or \"running\", got " + count);
        }
        trueCounted = count.equals("true");

        tags = new double[Rank.values().length][2];
        boolean[][] set = new boolean[Rank.values().length][2];
        Document perRank = (Document) d.get("tags");
        for (Map.Entry<String, Object> e : perRank.entrySet()) {
            for (Rank r : ranksOf(e.getKey(), true)) {
                tags[r.ordinal()][0] = tags[r.ordinal()][1] = number(e.getValue());
                set[r.ordinal()][0] = set[r.ordinal()][1] = true;
            }
        }
        for (Rank r : Rank.values()) {
            if (!set[r.ordinal()][0]) {
                throw new IllegalArgumentException(name + ": no tag for " + r);
            }
        }
        Document perCard = (Document) d.get("cardTags");
        if (perCard != null) {
            for (Map.Entry<String, Object> e : perCard.entrySet()) {
                String key = e.getKey();
                int[] colours = key.endsWith("red") ? new int[]{1}
                        : key.endsWith("black") ? new int[]{0} : new int[]{0, 1};
                String rank = key.replaceFirst("(red|black)$", "");
                for (Rank r : ranksOf(rank, false)) {
                    for (int c : colours) {
                        tags[r.ordinal()][c] = number(e.getValue());
                    }
                }
            }
        }
    }

    /**
     * The ranks a key names: A, 2 to 9, and for the ten-valued cards either T for all four
     * or, card by card, 10, J, Q and K.
     */
    private EnumSet<Rank> ranksOf(String key, boolean perRank) {
        switch (key) {
            case "A": return EnumSet.of(Rank.ACE);
            case "T": if (perRank) return EnumSet.of(Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING); break;
            case "10": if (!perRank) return EnumSet.of(Rank.TEN); break;
            case "J": if (!perRank) return EnumSet.of(Rank.JACK); break;
            case "Q": if (!perRank) return EnumSet.of(Rank.QUEEN); break;
            case "K": if (!perRank) return EnumSet.of(Rank.KING); break;
            default:
                for (Rank r : Rank.values()) {
                    if (r.getRankpoints() < 10 && key.equals(Integer.toString(r.getRankpoints()))) {
                        return EnumSet.of(r);
                    }
                }
        }
        throw new IllegalArgumentException(name + ": no rank called " + key
                + (perRank ? " among the per-rank tags" : " among the card tags"));
    }

    private static double number(Object o) {
        return ((Number) o).doubleValue();
    }

    public static boolean isRed(Suit s) {
        return s == Suit.HEARTS || s == Suit.DIAMONDS;
    }

    public double tag(Rank r, Suit s) {
        return tags[r.ordinal()][isRed(s) ? 1 : 0];
    }

    public double tag(Card c) {
        return tag(c.rank, c.suit);
    }

    /** What one full deck adds to the count: zero exactly when the system is balanced. */
    public double deckSum() {
        double sum = 0;
        for (Rank r : Rank.values()) {
            for (Suit s : Suit.values()) {
                sum += tag(r, s);
            }
        }
        return sum;
    }

    /** Every system in the catalog, in its order. */
    public static List<CountingSystem> all() {
        try {
            Document catalog = Document.parse(new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8));
            List<CountingSystem> systems = new ArrayList<>();
            for (Document d : catalog.getList("systems", Document.class)) {
                systems.add(new CountingSystem(d));
            }
            return Collections.unmodifiableList(systems);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + FILE.toAbsolutePath(), e);
        }
    }

    public static CountingSystem named(String name) {
        for (CountingSystem s : all()) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("no counting system named " + name);
    }

    @Override
    public String toString() {
        return name;
    }
}
