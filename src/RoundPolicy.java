/**
 * How the player chooses at every decision after the first move, per ROUND_CONTRACT.md.
 *
 * A policy sees only what a player at the table sees: the hand, the up-card, and
 * whether the hand came from a split. It cannot see the hole card or the shoe, and it
 * must be deterministic, so that two implementations valuing the same round ask it the
 * same questions and get the same answers.
 */
public interface RoundPolicy {

    PlayerMove choose(Decision d);

    /** Everything a decision may depend on. */
    final class Decision {
        public final int total;
        public final boolean soft;
        public final int numCards;
        /** The rank of a two-card pair, or 0 if the hand is not a pair. */
        public final int pairRank;
        public final int upCard;
        public final boolean fromSplit;
        /** The hand's first decision on exactly two cards. */
        public final boolean firstDecision;
        public final boolean canHit;
        public final boolean canDouble;
        public final boolean canSplit;
        public final boolean canSurrender;

        public Decision(int total, boolean soft, int numCards, int pairRank, int upCard,
                        boolean fromSplit, boolean firstDecision, boolean canHit,
                        boolean canDouble, boolean canSplit, boolean canSurrender) {
            this.total = total;
            this.soft = soft;
            this.numCards = numCards;
            this.pairRank = pairRank;
            this.upCard = upCard;
            this.fromSplit = fromSplit;
            this.firstDecision = firstDecision;
            this.canHit = canHit;
            this.canDouble = canDouble;
            this.canSplit = canSplit;
            this.canSurrender = canSurrender;
        }

        public boolean isLegal(PlayerMove m) {
            switch (m) {
                case Stand: return true;
                case Hit: return canHit;
                case Double: return canDouble;
                case Split: return canSplit;
                case Surrender: return canSurrender;
                default: return false;
            }
        }

        @Override
        public String toString() {
            return (soft ? "soft " : "hard ") + total + (pairRank > 0 ? " pair " + pairRank : "")
                    + " (" + numCards + " cards) vs " + upCard + (fromSplit ? ", split" : "")
                    + (firstDecision ? ", first" : "") + " [H" + (canHit ? 1 : 0) + " D"
                    + (canDouble ? 1 : 0) + " P" + (canSplit ? 1 : 0) + " R" + (canSurrender ? 1 : 0) + "]";
        }
    }
}
