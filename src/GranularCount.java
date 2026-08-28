import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;

/**
 * A true count, held as a whole number of hundredths.
 *
 * This is a hash key. The decision table is keyed on it, and its string form is the key a
 * cell is stored under in the database, so two counts that are numerically equal have to
 * be the same key every time, however they were arrived at.
 *
 * It used to hold three separate ints -- units, tenths, hundredths -- and that is what
 * made the guarantee hard to keep. Nothing tied the three together, so they could disagree
 * with the value they were meant to encode: new GranularCount(0, 47, 3) was accepted and
 * meant nothing coherent. Deriving them one digit at a time from a double made it worse,
 * because 0.3 arrives as 0.2999999999999998 and truncated to 0 units, 2 tenths, 9
 * hundredths, so one count bucket quietly became two that each saw half the evidence.
 *
 * One int cannot disagree with itself, so equals, hashCode and compareTo are now
 * straightforward rather than delicate, and the sign lives in one place instead of being
 * repeated across three fields.
 *
 * The string form still spells out the three digits in the old layout, sign included, so
 * every key written by the previous code reads back to the same value.
 *
 * A count is held to two decimal places. Grains of 1, 0.5, 0.25 and 0.1 are exact;
 * anything finer than 0.01, or not a whole number of hundredths, is rounded to fit.
 */
public class GranularCount implements Comparable<GranularCount> {

    private static final int HUNDREDTHS_PER_UNIT = 100;

    /** The count in hundredths: 2.50 is 250, -2.50 is -250. */
    private int hundredths;

    public GranularCount(int units, int firstDecimal, int secondDecimal){
        this.hundredths = units * HUNDREDTHS_PER_UNIT + firstDecimal * 10 + secondDecimal;
    }

    public GranularCount(double count){
        long magnitude = Math.round(Math.abs(count) * HUNDREDTHS_PER_UNIT);
        this.hundredths = (int) (count < 0 ? -magnitude : magnitude);
    }

    // ------------------------------------------------------------------- the value

    public int getHundredths(){
        return hundredths;
    }

    public double getDoubleFromCount(){
        return ((double) hundredths) / HUNDREDTHS_PER_UNIT;
    }

    private int sign(){
        return hundredths < 0 ? -1 : 1;
    }

    /** Whole units, carrying the sign: -2.50 gives -2. */
    public int getUnits(){
        return sign() * (Math.abs(hundredths) / HUNDREDTHS_PER_UNIT);
    }

    /** Tenths digit, carrying the sign, as the stored layout has always done. */
    public int getFirstDecimal(){
        return sign() * ((Math.abs(hundredths) / 10) % 10);
    }

    /** Hundredths digit, carrying the sign. */
    public int getSecondDecimal(){
        return sign() * (Math.abs(hundredths) % 10);
    }

    // -------------------------------------------------------------------- the keys

    /**
     * The database key. Three signed digits joined by ampersands, unchanged from the
     * layout the previous code wrote, so stored tables still load.
     */
    public String getStringFromCount(){
        return getUnits() + "&" + getFirstDecimal() + "&" + getSecondDecimal();
    }

    public static GranularCount getCountFromString(String s){
        String[] parts = s.split("&");
        int units = Integer.parseInt(parts[0]);
        int firstDecimal = Integer.parseInt(parts[1]);
        int secondDecimal = Integer.parseInt(parts[2]);
        return new GranularCount(units, firstDecimal, secondDecimal);
    }

    /**
     * How the count is printed in a generated strategy table.
     *
     * The old version pasted the three digits together around a decimal point, which
     * rendered a negative fractional count as "-2.-50". Only whole counts were ever
     * produced in practice, so the break never showed.
     */
    public String countToCellString(){
        int magnitude = Math.abs(hundredths);
        return (hundredths < 0 ? "-" : "")
                + (magnitude / HUNDREDTHS_PER_UNIT)
                + "."
                + String.format("%02d", magnitude % HUNDREDTHS_PER_UNIT);
    }

    // ------------------------------------------------------------------- the range

    public boolean isCountInBoundaries(int minC, int maxC){
        return hundredths >= minC * HUNDREDTHS_PER_UNIT
                && hundredths <= maxC * HUNDREDTHS_PER_UNIT;
    }

    public boolean forceCountIntoBoundaries(int minC, int maxC){
        int low = minC * HUNDREDTHS_PER_UNIT;
        int high = maxC * HUNDREDTHS_PER_UNIT;
        if(hundredths < low){
            hundredths = low;
        }
        else if(hundredths > high){
            hundredths = high;
        }
        return true;
    }

    // -------------------------------------------------------------------- the grid

    public static int numGranCount(double countPrecision, int minC, int maxC){
        double interval = (double) (maxC - minC);
        return (int) (interval / countPrecision + 1.0);
    }

    /**
     * A uniformly chosen count from the grid covering [minC, maxC].
     *
     * The step is taken in hundredths rather than by repeatedly adding a double, which
     * drifts off the grid for a grain such as 0.1 and yields counts that then match no
     * cell at all.
     */
    public static GranularCount getRandomCount(int minC, int maxC, double countPrecision){
        int step = (int) Math.round(countPrecision * HUNDREDTHS_PER_UNIT);
        if(step <= 0){
            step = 1;
        }
        ArrayList<GranularCount> allCounts = new ArrayList<>();
        int low = minC * HUNDREDTHS_PER_UNIT;
        int high = maxC * HUNDREDTHS_PER_UNIT;
        for(int h = low; h <= high; h += step){
            allCounts.add(new GranularCount(0, 0, h));
        }
        Random rand = new Random();
        return allCounts.get(rand.nextInt(allCounts.size()));
    }

    /**
     * Snap a raw true count onto the count grid, breaking ties toward zero.
     *
     * Two things are going on here.
     *
     * First, Math.round breaks ties toward positive infinity, so Math.round(1.5) is 2
     * while Math.round(-1.5) is -1. Hi-Lo is a balanced count, so that hands every
     * half-way count to the positive bucket and skews the histogram. The true count is
     * one integer over another and most deck-counts are even, so exact .5 ties are about
     * 9% of hands rather than a rare edge case.
     *
     * Second, of the tie rules that are symmetric about zero, which one to use is not
     * arbitrary. The true count is densest near zero, so a bucket [k-0.5, k+0.5] holds
     * more mass at its low edge than its high edge. Breaking ties away from zero pulls
     * the dense low tie in and pushes the sparse high tie out, dragging each bucket's
     * mean below its own label by about 0.11 and compressing the whole edge-vs-count
     * curve. Breaking ties toward zero does the reverse and very nearly cancels that
     * density asymmetry, leaving a residual of about 0.02.
     *
     * CountBiasReport prints the comparison, including half-to-even, which is symmetric
     * but makes bucket populations alternate.
     */
    public static double roundToGrain(double d, double grain) {
        double invG = 1.0 / grain;
        double scaled = d * invG;
        double rounded = Math.signum(scaled) * Math.ceil(Math.abs(scaled) - 0.5);
        return rounded / invG;
    }

    // ---------------------------------------------------------------- the identity

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GranularCount that = (GranularCount) o;
        return hundredths == that.hundredths;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hundredths);
    }

    @Override
    public int compareTo(GranularCount other) {
        return Integer.compare(this.hundredths, other.hundredths);
    }

    @Override
    public String toString() {
        return countToCellString();
    }
}
