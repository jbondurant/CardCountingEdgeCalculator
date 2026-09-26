import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The table run deals a chosen hand, and the shoe it leaves behind has to be random.
 *
 * To study A,A against an ace, setCards picks those cards out of a shuffled shoe: it
 * walks down from the top and takes the first ace it meets, three times. Every card it
 * walked past is some other rank, so the top of the shoe is left short of aces and the
 * rest of it is left long. Then the run burns a random number of cards off the top until
 * the true count matches the one it wants. A burn that misses the count reshuffles and
 * tries again, but the first attempt burned straight from the picked-over order, and the
 * player's next card came from just below it.
 *
 * With A,A against an ace at true count 0 that made the next card an ace 0.066 of the
 * time instead of 0.071. The player's split-ace draws and the dealer's hits were being
 * measured against a shoe that no casino deals.
 */
public class ChosenHandDealingTest {

    /** A count that never moves, so every burn lands on true count 0 at its first try. */
    private static CountMethod countThatNeverMoves() {
        EnumMap<Rank, Integer> zero = new EnumMap<>(Rank.class);
        for (Rank r : Rank.values()) {
            zero.put(r, 0);
        }
        return new CountMethod(zero, 1);
    }

    /**
     * Deal A,A against an ace from an eight-deck shoe with every ace at the bottom, the
     * extreme of what picking the first match does: all the cards above the chosen ones
     * are some other rank. Then burn the way setCards does, with a count that accepts the
     * first attempt, which is the attempt that used to skip the shuffle. Whatever is left
     * must not be the dealt shoe with its top cut off, or the player's next card was
     * decided by where the aces happened to be.
     */
    @Test
    public void shoeLeftAfterDealingAChosenHandIsNotThePickedOverOrder() {
        Table table = new Table(8, countThatNeverMoves());
        ArrayList<Card> acesLast = new ArrayList<>();
        for (Card c : table.gameDeck.cards) {
            if (c.rank != Rank.ACE) {
                acesLast.add(c);
            }
        }
        for (Card c : table.gameDeck.cards) {
            if (c.rank == Rank.ACE) {
                acesLast.add(c);
            }
        }
        table.gameDeck.cards = acesLast;

        HandEncoding pairOfAces = new HandEncoding(true, true, 2);
        HashMap<HandEncoding, ArrayList<DoubleRanks>> ranksForHand = new HashMap<>();
        ArrayList<DoubleRanks> aceAce = new ArrayList<>();
        aceAce.add(new DoubleRanks(Rank.ACE, Rank.ACE));
        ranksForHand.put(pairOfAces, aceAce);

        table.givePlayer2CardsThatFitHandEncodingAndCountMaybe(pairOfAces, ranksForHand, false);
        table.giveDealerHandAndRunCountMaybe(Rank.ACE.getRankpoints(), false);
        assertEquals(Rank.ACE, table.randomishPlayer.playerHands.playerHand.handCards.get(0).rank);
        assertEquals(Rank.ACE, table.randomishPlayer.playerHands.playerHand.handCards.get(1).rank);
        assertEquals(Rank.ACE, table.dealer.revealedCards.get(0).rank);

        List<Card> dealtShoe = new ArrayList<>(table.gameDeck.cards);
        int deckSize = table.gameDeck.startingSize / 8;
        table.removeRandomAmountCardsAndRunCount(75, new GranularCount(0.0), deckSize, 1.0, -5, 5);

        List<Card> left = table.gameDeck.cards;
        assertTrue(left.size() >= 104,
                "a 75% penetration leaves at least a quarter of the 416-card shoe, but "
                        + left.size() + " cards are left");
        List<Card> topCutOff = dealtShoe.subList(dealtShoe.size() - left.size(), dealtShoe.size());
        int cardsAboveFirstAce = 0;
        while (cardsAboveFirstAce < left.size() && left.get(cardsAboveFirstAce).rank != Rank.ACE) {
            cardsAboveFirstAce++;
        }
        assertFalse(topCutOff.equals(left),
                "the burn cut " + (dealtShoe.size() - left.size()) + " cards off the top of the"
                        + " shoe the chosen aces were picked out of and never reshuffled it, so"
                        + " the player draws from a shoe with " + cardsAboveFirstAce
                        + " other cards above its first ace");
    }
}
