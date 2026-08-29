/**
 * Thrown when the simulation asks for a number that was never measured.
 *
 * The ordering in HandEncoding.getOrderedEncodings exists so this cannot happen. A hand is
 * only played once every hand it can turn into has already been solved, so a lookup made
 * during play always lands on a filled cell. Hard 12 is played after hard 13 through 21
 * precisely because hitting it can produce any of them.
 *
 * The alternative is to return some neutral number, which is what these lookups used to
 * do -- one returned Integer.MIN_VALUE, the rest returned nothing at all and threw a
 * NullPointerException. A neutral number is worse than either. It does not stay local: it
 * becomes the payoff of the hand being evaluated, and insertEvent records it as an
 * observation like any other, so a broken ordering shows up as a quietly wrong average
 * rather than as a failure. That is the one outcome a program whose entire output is an
 * average cannot afford.
 *
 * A move whose value is fixed by the rules rather than measured does not count as missing:
 * surrender is worth -0.5 by definition, so a bucket with nothing in it can still price a
 * question that allows surrendering.
 */
public class UnsolvedCellException extends IllegalStateException {

    public UnsolvedCellException(String message) {
        super(message);
    }
}
