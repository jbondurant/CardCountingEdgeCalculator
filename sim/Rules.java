package sim;

/**
 * The table's rules, and the planted bugs that change them.
 *
 * <p>Where each rule comes from. The game is Loto-Québec's, from its published rules ("Règles de
 * jeu, Black Jack", https://casinos.lotoquebec.com/dam/jcr:fa66ea03-a3d8-4d78-9c85-74383d7b959e/Regles_Blackjack_f.pdf,
 * read 9 October 2026), in ROUND_CONTRACT.md's terms, with SHOE_SPEC.md A2's {@code rule.*}
 * lines for what the PDF leaves to the sign at the table. The phrases used, quoted short:
 * <ul>
 * <li>The deal: one card to each player, then the dealer's "celle-ci face visible"; a second to
 * each player, then the dealer's "celle-ci face cachée". So player, up-card, player, hole card.
 * <li>The peek: the player's options hold "Dans la mesure où le croupier n'a ni un black jack".
 * So the dealer checks for a natural before the player acts.
 * <li>Splitting: two cards "d'une même valeur (ex : roi et 10, paire de 9)", "jusqu'à un
 * maximum de quatre mains". So a king and a ten are a pair, and pairs split to four hands.
 * <li>Aces: "ne peut partager qu'une seule fois" and one card "pour chaque main". So aces split
 * once, to two hands, and a split ace takes one card and stands.
 * <li>"Il ne peut y avoir de black jack avec une main partagée": a split ace and a ten make an
 * ordinary 21.
 * <li>Doubling: "sur une main de deux cartes", and after a split "sauf dans le cas d'une paire
 * d'as". So any two cards double, and split hands double except split aces.
 * <li>Insurance: half the initial bet, "payée 2 à 1" when the dealer has a natural.
 * <li>A natural is "payée 3 à 2, sauf si le croupier a lui aussi un black jack" (then a push).
 * <li>The dealer: hits soft 17 or stands on all 17s, "selon ce qui est indiqué à la table". The PDF
 * leaves it to the sign; SHOE_SPEC.md A2 says the engine's Montreal table hits soft 17
 * ({@code rule.hits.soft.17 = true}), and this simulator does the same (sim/QUESTIONS.md, item 1).
 * <li>The PDF offers no surrender, so there is none ({@code rule.surrender = false}).
 * <li>"1 à 8 paquets": the shoe here has 8 (SHOE_SPEC.md A2, {@code decks = 8}).
 * </ul>
 */
public final class Rules {

    /** The dealer hits a soft 17. */
    final boolean hitsSoft17;
    /** A player's natural against no dealer natural is paid this, per unit. */
    final double blackjackPayout;
    /** Every hand of the round counts toward these limits, finished or not. */
    final int maxHandsAces;
    final int maxHandsOther;
    final boolean hitSplitAces;
    /** doubleAfterSplit[r] is true when a hand split from rank r may double. */
    final boolean[] doubleAfterSplit;
    /** The stake insurance takes, as a share of the bet: one half at this table. */
    final double insuranceStake;

    private Rules(boolean hitsSoft17, int maxHandsAces, boolean hitSplitAces, boolean[] das,
                  double insuranceStake) {
        this.hitsSoft17 = hitsSoft17;
        this.blackjackPayout = 1.5;
        this.maxHandsAces = maxHandsAces;
        this.maxHandsOther = 4;
        this.hitSplitAces = hitSplitAces;
        this.doubleAfterSplit = das;
        this.insuranceStake = insuranceStake;
    }

    /** Montreal's rules, with the planted bugs that change a rule applied (none in a main run). */
    public static Rules montreal(Plants plants) {
        boolean[] das = new boolean[11];
        if (!plants.on(Plants.NO_DAS)) {
            for (int r = 2; r <= 10; r++) {
                das[r] = true;
            }
        }
        boolean acesLikeOthers = plants.on(Plants.SPLIT_ACES_HIT);
        if (acesLikeOthers) {
            // "split aces are played like other split hands" (SIM_CHECK.md 10): they may hit,
            // double (unless no-das is also on) and resplit to four hands.
            das[1] = !plants.on(Plants.NO_DAS);
        }
        return new Rules(!plants.on(Plants.S17), acesLikeOthers ? 4 : 2, acesLikeOthers, das,
                plants.on(Plants.INSURE_WHOLE_BET) ? 1.0 : 0.5);
    }

    public static Rules montreal() {
        return montreal(Plants.NONE);
    }

    int maxHands(int rank) {
        return rank == 1 ? maxHandsAces : maxHandsOther;
    }
}
