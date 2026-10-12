import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResultDiff, which sets a replayed item beside the run's part (FullIndexReplay): every number to
 * the bit, else within 1e-12 relative; only the timing, a count's fields but its name, and arrays
 * added since the run are set apart; everything else that differs, a count, a length, a missing
 * array, a NaN, is beyond, with its place named. And a real part of c06c061 against itself read
 * again, which walks every class a Result holds.
 */
public class ResultDiffTest {

    static SystemScorer.Result result() {
        SystemScorer.Result r = new SystemScorer.Result(CountKeys.nullCount());
        SystemScorer.Entry e = new SystemScorer.Entry("Null count", CountKeys.nullCount(), false);
        e.estimate[2] = new double[]{0.25, -0.5, 0.0, 1.0};
        e.boot[2] = new double[][]{{0.1, 0.2, 0.3}};
        e.shoeSums[2] = new double[]{1, 2};
        r.entries.add(e);
        r.resolution[2] = new SystemScorer.Resolution();
        r.resolution[2].decisions = 7;
        r.halfFitFlat[2] = -0.3;
        r.seconds = 1.5;
        return r;
    }

    @SuppressWarnings("unchecked")
    static <T> T copy(T x) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(b)) {
            o.writeObject(x);
        }
        try (ObjectInputStream i = new ObjectInputStream(new ByteArrayInputStream(b.toByteArray()))) {
            return (T) i.readObject();
        }
    }

    @Test
    public void theSameResultIsTheSameToTheBitButForItsTiming() throws Exception {
        SystemScorer.Result y = copy(result());
        y.seconds = 99;
        ResultDiff d = ResultDiff.of(result(), y);
        assertTrue(d.identical(), d.describe());
        assertEquals(4 + 3 + 2 + 2 * SystemScorer.CUTS.length + 3, d.numbers, "estimate, resamples, shoe sums, the "
                + "two half fits' flat EVs and the resolution's three bounds; not the timing");
        assertEquals(d.numbers, d.sameBits);
    }

    @Test
    public void lastBitsAreWithinAndFurtherIsBeyond() throws Exception {
        SystemScorer.Result y = copy(result());
        double[] x = y.entries.get(0).estimate[2];
        x[0] = Math.nextUp(x[0]);
        x[2] = -0.0;
        ResultDiff d = ResultDiff.of(result(), y);
        assertTrue(d.passes() && !d.identical(), d.describe());
        assertEquals(2, d.within);
        assertEquals("result.entries[0].estimate[2][0]", d.largestAt);
        assertEquals(Math.ulp(0.25) / 0.25, d.largest, 1e-30);

        x[0] = 0.25 * (1 + 1e-11);
        x[3] = Double.NaN;
        d = ResultDiff.of(result(), y);
        assertEquals(2, d.beyond, d.describe());
        assertTrue(d.beyondAt.get(0).startsWith("result.entries[0].estimate[2][0]: 0.25 in the source, "), d.describe());
        assertTrue(d.beyondAt.get(1).startsWith("result.entries[0].estimate[2][3]: 1.0 in the source, NaN replayed"),
                d.describe());
    }

    @Test
    public void aCountIsComparedByItsNameAndEveryOtherValueExactly() throws Exception {
        SystemScorer.Result y = copy(result());
        y.resolution[2].decisions = 8;
        y.entries.get(0).shoeSums[2] = new double[]{1, 2, 3};
        ResultDiff d = ResultDiff.of(result(), y);
        assertEquals(2, d.beyond, d.describe());
        assertTrue(d.describe().contains("result.entries[0].shoeSums[2]: length 2 in the source, 3 replayed"), d.describe());
        assertTrue(d.describe().contains("result.resolution[2].decisions: 7 in the source, 8 replayed"), d.describe());

        SystemScorer.Result other = new SystemScorer.Result(CountKeys.catalog().get(0));
        other.entries.addAll(copy(result()).entries);
        d = ResultDiff.of(result(), other);
        assertTrue(d.describe().contains("result.count.name: Null count (no count) in the source, "), d.describe());
    }

    @Test
    public void anArrayAddedSinceIsListedAndOneMissingIsBeyond() throws Exception {
        SystemScorer.Result y = copy(result());
        y.entries.get(0).halfFitSame = new double[3];
        ResultDiff d = ResultDiff.of(result(), y);
        assertTrue(d.identical(), d.describe());
        assertEquals(1, d.added);
        assertEquals("result.entries[0].halfFitSame", d.addedAt.get(0));

        y.entries.get(0).boot[2] = null;
        d = ResultDiff.of(result(), y);
        assertEquals(1, d.beyond, d.describe());
        assertEquals("result.entries[0].boot[2]: set in the source, missing in the replay", d.beyondAt.get(0));
    }

    /** A half fits' part of c06c061, kept beside the tests, against itself read again: every class a Result holds. */
    @Test
    public void aPartOfC06c061IsTheSameAsItselfReadAgain() throws Exception {
        SystemScorer.Result[] two = new SystemScorer.Result[2];
        for (int k = 0; k < 2; k++) {
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(StoredResultsTest.PART))) {
                in.readUTF();
                two[k] = (SystemScorer.Result) in.readObject();
            }
        }
        ResultDiff d = ResultDiff.of(two[0], two[1]);
        assertTrue(d.identical(), d.describe());
        assertTrue(d.numbers > 1000 && d.others > 100, d.describe());
        double[] gain = two[1].fullHalfFit[0][1].gain;
        int at = 0;
        while (Double.isNaN(gain[at])) {
            at++;
        }
        gain[at] += 1;
        d = ResultDiff.of(two[0], two[1]);
        assertEquals(1, d.beyond, d.describe());
        assertEquals("result.fullHalfFit[0][1].gain[" + at + "]", d.beyondAt.get(0).substring(0,
                d.beyondAt.get(0).indexOf(':')));
    }

    /**
     * The count a part of c06c061 holds, written before the divisor and the betting vectors (its
     * divisor field 0), against this build's count of that name: the same, since a count is
     * compared by its name.
     */
    @Test
    public void aCountReadFromC06c061IsThisBuildsCountOfItsName() throws Exception {
        SystemScorer.Result part;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(StoredResultsTest.PART))) {
            in.readUTF();
            part = (SystemScorer.Result) in.readObject();
        }
        ResultDiff d = ResultDiff.of(part.count, CountKeys.nullCount());
        assertTrue(d.identical(), d.describe());
        assertEquals(1, d.others, "the name alone");
    }
}
