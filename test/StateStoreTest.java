import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The state file: records come back as written, and a stopped run resumes cleanly. */
public class StateStoreTest {

    private static StateStore.Record record(Random rnd, int shoe, int round) {
        int[] dealt = new int[ShoeRun.KINDS];
        for (int k = 0; k < dealt.length; k++) {
            dealt[k] = rnd.nextInt(17);
        }
        double[] values = new double[StateStore.VALUES];
        for (int v = 0; v < values.length; v++) {
            values[v] = rnd.nextInt(5) == 0 ? Double.NaN : rnd.nextGaussian();
        }
        return new StateStore.Record(shoe, round, rnd.nextInt(332), rnd.nextDouble(), dealt, values);
    }

    private static void same(StateStore.Record a, StateStore.Record b) {
        assertEquals(a.shoe, b.shoe);
        assertEquals(a.round, b.round);
        assertEquals(a.depth, b.depth);
        assertEquals(a.q, b.q);
        assertArrayEquals(a.dealt, b.dealt);
        assertArrayEquals(a.values, b.values);
    }

    @Test
    public void recordsComeBackAsWrittenAndARunResumes() throws Exception {
        Path dir = Files.createTempDirectory("states");
        Path data = dir.resolve("run.states");
        StateStore.Header header = new StateStore.Header(7, 0.25, 0.05, 332, "rules");
        Random rnd = new Random(1);
        assertEquals(0, StateStore.open(data, header));
        List<StateStore.Record> written = new ArrayList<>();
        for (int shoe = 0; shoe < 3; shoe++) {
            List<StateStore.Record> shoeRecords = new ArrayList<>();
            for (int k = 0; k < shoe; k++) {
                shoeRecords.add(record(rnd, shoe, k));
            }
            StateStore.appendShoe(data, shoe, shoeRecords);
            written.addAll(shoeRecords);
        }
        List<StateStore.Record> read = StateStore.readAll(data);
        assertEquals(written.size(), read.size());
        for (int i = 0; i < read.size(); i++) {
            same(written.get(i), read.get(i));
        }
        assertEquals(header, StateStore.header(data));

        // A run killed while writing shoe 3 leaves bytes the progress file does not vouch
        // for. Opening again cuts them off and resumes at shoe 3.
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[StateStore.RECORD_BYTES / 2]));
        }
        assertEquals(3, StateStore.open(data, header));
        assertEquals(written.size(), StateStore.readAll(data).size());
        StateStore.Record more = record(rnd, 3, 0);
        StateStore.appendShoe(data, 3, List.of(more));
        read = StateStore.readAll(data);
        assertEquals(written.size() + 1, read.size());
        same(more, read.get(read.size() - 1));
    }

    @Test
    public void aFileMadeWithOtherSettingsIsRefused() throws Exception {
        Path data = Files.createTempDirectory("states").resolve("run.states");
        StateStore.open(data, new StateStore.Header(7, 0.25, 0.05, 332, "rules"));
        assertThrows(java.io.IOException.class, () -> StateStore.open(data, new StateStore.Header(8, 0.25, 0.05, 332, "rules")));
        assertThrows(java.io.IOException.class, () -> StateStore.open(data, new StateStore.Header(7, 0.5, 0.05, 332, "rules")));
        assertThrows(java.io.IOException.class, () -> StateStore.open(data, new StateStore.Header(7, 0.25, 0.1, 332, "rules")));
        assertThrows(java.io.IOException.class, () -> StateStore.open(data, new StateStore.Header(7, 0.25, 0.05, 332, "other")));
    }
}
