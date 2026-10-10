package sim;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The planted bugs of SIM_CHECK.md section 10, each a switch that is off unless named with
 * {@code --plant NAME}. They exist to show what the checks can see: a run with a switch on is
 * scored like any other, and every summary prints the state of every switch, so a main run with
 * one on can be refused.
 */
public final class Plants {

    /** The dealer stands on soft 17. */
    public static final String S17 = "s17";
    /** No double after a split. */
    public static final String NO_DAS = "no-das";
    /** Split aces are played like other split hands. */
    public static final String SPLIT_ACES_HIT = "split-aces-hit";
    /** The hole card enters the count when it is dealt, so every key read in the round has it. */
    public static final String HOLE_CARD = "hole-card";
    /** True counts truncated toward zero instead of rounded to nearest. */
    public static final String TRUNCATE = "truncate";
    /** Insurance staked at the whole bet instead of half of it. */
    public static final String INSURE_WHOLE_BET = "insure-whole-bet";
    /** The other fold's strategy plays: P2 plays each state by the instance fitted on its own shoes; P3 swaps the pooling weights. */
    public static final String OTHER_FOLD = "other-fold";
    /** Every key is bet as the next key up. */
    public static final String BET_KEY_PLUS_ONE = "bet-key-plus-one";

    public static final List<String> ALL = List.of(S17, NO_DAS, SPLIT_ACES_HIT, HOLE_CARD, TRUNCATE,
            INSURE_WHOLE_BET, OTHER_FOLD, BET_KEY_PLUS_ONE);

    public static final Plants NONE = new Plants(Set.of());

    private final Set<String> on;

    private Plants(Set<String> on) {
        this.on = on;
    }

    public static Plants of(String... names) {
        Set<String> set = new TreeSet<>(Arrays.asList(names));
        for (String name : set) {
            if (!ALL.contains(name)) {
                throw new IllegalArgumentException("no planted bug named " + name + "; known: " + ALL);
            }
        }
        return new Plants(set);
    }

    public boolean on(String name) {
        return on.contains(name);
    }

    public boolean any() {
        return !on.isEmpty();
    }

    /** Every switch with its state, for a summary: "s17=off no-das=on ...". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String name : ALL) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name).append(on(name) ? "=on" : "=off");
        }
        return sb.toString();
    }
}
