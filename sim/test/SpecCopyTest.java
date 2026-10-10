package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tests compare the simulator with SHOE_SPEC.md's own reference code (A3) and its port of
 * SplittableRandom (A9), copied into sim/test/. This holds each copy equal to the block in
 * SHOE_SPEC.md, line for line, so a copy cannot drift from the spec it stands for. Run from the
 * repository's root, where SHOE_SPEC.md is.
 */
class SpecCopyTest {

    /** The lines of the spec's fenced block that opens with this line. */
    static List<String> block(String opening) throws IOException {
        List<String> spec = Files.readAllLines(Path.of("SHOE_SPEC.md"), StandardCharsets.UTF_8);
        int start = spec.indexOf(opening);
        if (start < 0) {
            throw new AssertionError("SHOE_SPEC.md has no line " + opening);
        }
        int end = start + 1;
        while (!spec.get(end).equals("```")) {
            end++;
        }
        return spec.subList(start + 1, end);
    }

    /** The copy's lines after its five-line header (package, blank, two comment lines, blank). */
    static List<String> copy(String file) throws IOException {
        List<String> lines = Files.readAllLines(Path.of("sim/test", file), StandardCharsets.UTF_8);
        return lines.subList(5, lines.size());
    }

    @Test
    void shoeSpecIsTheSpecsBlock() throws IOException {
        assertEquals(block("```java ShoeSpec.java"), copy("ShoeSpec.java"));
    }

    @Test
    void portIsTheSpecsBlock() throws IOException {
        assertEquals(block("```java PortableSplittableRandom.java"), copy("PortableSplittableRandom.java"));
    }
}
