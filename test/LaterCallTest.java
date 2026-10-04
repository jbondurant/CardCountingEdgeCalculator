import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What keeping a later cell's fitted answer can cost per round (FULL_INDEX_PLAYS.md, section 3):
 * nothing once its one-flip gain is past two standard errors, its frequency times
 * (2 SE - gain) when it is a close call, and the same when the gain is negative. A negative
 * gain is a cell the clean-up passes left playing the losing answer, and the whole of that
 * known loss is charged, not only its distance from zero.
 */
public class LaterCallTest {

    private static SystemScorer.LaterCall call(double gain, double se, double frequency) {
        return new SystemScorer.LaterCall(0, 0, 0, 0, true, gain, se, frequency);
    }

    @Test
    public void aSettledCellCostsNothing() {
        assertEquals(0, call(0.5, 0.1, 0.02).bound(), 0);
        assertEquals(0, call(0.2, 0.1, 0.02).bound(), 0);
    }

    @Test
    public void aCloseCallCostsItsFrequencyTimesTwoStandardErrorsLessTheGain() {
        assertEquals(0.02 * 0.1, call(0.1, 0.1, 0.02).bound(), 1e-18);
        assertEquals(0.02 * 0.2, call(0, 0.1, 0.02).bound(), 1e-18);
    }

    @Test
    public void aLosingAnswerIsChargedItsWholeLoss() {
        // Gain -0.3 with SE 0.1: the answer kept loses 0.3 each time, and could lose up to
        // 0.3 + 0.2. Charging only how far the gain is from zero would give max(0, 0.2 - 0.3) = 0.
        assertEquals(0.02 * 0.5, call(-0.3, 0.1, 0.02).bound(), 1e-18);
        assertTrue(call(-0.3, 0.1, 0.02).bound() > call(0.3, 0.1, 0.02).bound());
    }
}
