package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * states.bin's reader and check D1 on a fixture made from the simulator's own walk of the first
 * 300 main shoes: the fixture as written passes card for card, and each planted fault (a bit of
 * q, a record dropped or added, a card moved between kinds) is seen.
 */
class StatesTest {

    static final int SHOES = 300;

    static States.D1 d1(StatesFixture f) throws IOException {
        return f.read().d1(new Round(Rules.montreal()), Chart.textbook());
    }

    @Test
    void theWalksOwnStatesPassCardForCard() throws IOException {
        StatesFixture f = new StatesFixture(SHOES);
        States s = f.read();
        assertFalse(s.isMainFile(), "300 shoes are not the main file");
        assertEquals(4, s.sections.size());
        assertTrue(s.n > 200, s.n + " valued states in 300 shoes");
        States.D1 d = s.d1(new Round(Rules.montreal()), Chart.textbook());
        assertTrue(d.cardForCard(), String.valueOf(d.first));
        assertEquals(s.n, d.matched);
        assertEquals(s.n, d.valued);
        for (int k = 0; k < Shoe.KINDS; k++) {
            assertEquals(f.records.get(5).dealt[k], s.dealt[5 * Shoe.KINDS + k]);
        }
    }

    static void seen(Consumer<StatesFixture> plant) throws IOException {
        StatesFixture f = new StatesFixture(SHOES);
        plant.accept(f);
        States.D1 d = d1(f);
        assertFalse(d.cardForCard());
    }

    @Test
    void d1SeesEachPlantedFault() throws IOException {
        seen(f -> f.records.get(17).q = Math.nextUp(f.records.get(17).q));
        seen(f -> f.records.remove(40));
        seen(f -> f.records.remove(f.records.size() - 1));
        seen(f -> {
            StatesFixture.Rec x = f.records.get(f.records.size() - 1);
            StatesFixture.Rec extra = new StatesFixture.Rec();
            extra.shoe = x.shoe;
            extra.round = x.round + 1;
            extra.depth = x.depth;
            extra.q = x.q;
            extra.fold = x.fold;
            extra.dealt = x.dealt.clone();
            f.records.add(extra);
        });
        seen(f -> {
            // A black ten becomes a black jack: same ranks, same q, different tallies.
            for (StatesFixture.Rec x : f.records) {
                if (x.dealt[18] > 0 && x.dealt[20] < Shoe.PER_KIND) {
                    x.dealt[18]--;
                    x.dealt[20]++;
                    return;
                }
            }
            throw new AssertionError("no record to change");
        });
    }

    static void refused(Consumer<StatesFixture> change) {
        StatesFixture f = new StatesFixture(40);
        change.accept(f);
        assertThrows(IOException.class, f::read);
    }

    @Test
    void theReaderRefusesWhatB3DoesNotAllow() {
        refused(f -> f.magic = "SIMSTATX");
        refused(f -> f.version = 2);
        refused(f -> f.records.get(3).fold ^= 1);
        refused(f -> f.records.get(3).dealt[5]++);
        refused(f -> f.trailingByte = true);
        refused(f -> f.rows.add(0, "Zen Count"));
        refused(f -> java.util.Collections.swap(f.records, 1, 2));
    }
}
