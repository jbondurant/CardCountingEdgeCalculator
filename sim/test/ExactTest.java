package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** exact.tsv (SHOE_SPEC.md B5): the pooled figure, its fixed-fit SE and the folds' weights, worked by hand. */
class ExactTest {

    static List<String> text(String... lines) {
        List<String> out = new java.util.ArrayList<>(List.of("# exact.tsv: SHOE_SPEC.md B5, format 1", Exact.HEADER));
        out.addAll(List.of(lines));
        return out;
    }

    @Test
    void pooledFigureAndItsStandardErrorByHand() throws IOException {
        // Fold 0's two shoes: a = 1 and 3, b = 2 and 2. Fold 1's: a = -1 and 1, b = 1 and 3.
        Exact e = Exact.parse(text(
                "Hi-Lo\t80\t0\tflat\t0\t2\t4\t4\t10\t8\t8",
                "Hi-Lo\t80\t1\tflat\t0\t2\t0\t4\t2\t2\t10"), "test");
        // R = 4 / 8. The residuals a - R b are 0, 2, -1.5 and -0.5, squares adding to 6.5;
        // SE = sqrt(4/3 * 6.5) / 8.
        assertEquals(0.5, e.estimate("Hi-Lo", 80, "flat"));
        assertEquals(Math.sqrt(4.0 / 3 * 6.5) / 8, e.standardError("Hi-Lo", 80, "flat"), 1e-15);
        assertEquals(0.5, e.foldWeight("Hi-Lo", 80, 0));
        assertTrue(Double.isNaN(e.estimate("Hi-Lo", 75, "flat")));
    }

    @Test
    void refusesWhatB5DoesNotAllow() {
        assertThrows(IOException.class, () -> Exact.parse(List.of("row\tpen"), "test"));
        assertThrows(IOException.class, () -> Exact.parse(text("Hi-Lo\t80\t0\tflat\t0\t2\t4"), "test"));
        assertThrows(IOException.class, () -> Exact.parse(text(
                "Hi-Lo\t80\t0\tflat\t0\t2\t4\t4\t10\t8\t8", "Hi-Lo\t80\t0\tflat\t0\t2\t4\t4\t10\t8\t8"), "test"));
    }
}
