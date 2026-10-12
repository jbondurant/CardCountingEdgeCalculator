import org.junit.jupiter.api.Test;

import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The classes c06c061's fit.ckpt is written in, as c06c061's own classes made them, against these:
 * the same serialVersionUID and the same fields, name for name and type for type, and an enum's
 * constants all still there. The replay (FullIndexReplay) puts c06c061's strategies back from that
 * file with these classes; a field added, dropped or retyped since would put them back changed, or
 * not at all. The replay's own comparison with every part would catch that, after a last round;
 * this catches it first, in a second, without opening the run's directory: it reads only the class
 * files kept in runs/full-index/classes-c06c061 (C06c061), and is skipped where they are not. A
 * field planted in FullIndexFit.State in a scratch copy (12 October 2026) made it fail.
 */
public class FitCheckpointClassesTest {

    /** Every class fit.ckpt's objects are made of, besides the JDK's: FullIndexCheckpoint.writeFit's graph. */
    static final String[] WRITTEN = {"FullIndexFit$State", "FullIndexFit$PolicyState", "FullIndexFit$PassRecord",
            "FullIndexFit$Phase", "FullIndexFit$RoundStats", "SystemScorer$Groups"};

    @Test
    public void c06c061sFitCheckpointIsWrittenInClassesTheseReadAsTheyWere() throws Exception {
        ClassLoader old = C06c061.loader();
        List<String> wrong = new ArrayList<>();
        for (String name : WRITTEN) {
            Class<?> was = Class.forName(name, false, old);
            Class<?> now = Class.forName(name);
            assertNotSame(was, now);
            if (was.isEnum()) {
                for (Object c : was.getEnumConstants()) {
                    boolean there = false;
                    for (Object d : now.getEnumConstants()) {
                        there |= ((Enum<?>) d).name().equals(((Enum<?>) c).name());
                    }
                    if (!there) {
                        wrong.add(name + " no longer has " + c);
                    }
                }
                continue;
            }
            ObjectStreamClass a = ObjectStreamClass.lookup(was);
            ObjectStreamClass b = ObjectStreamClass.lookup(now);
            if (a.getSerialVersionUID() != b.getSerialVersionUID()) {
                wrong.add(name + ": serialVersionUID " + a.getSerialVersionUID() + ", now " + b.getSerialVersionUID());
            }
            if (!fields(a).equals(fields(b))) {
                wrong.add(name + ": fields " + fields(a) + ", now " + fields(b));
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    /** A serialized class's fields, each as its name and its type's signature, in the stream's order. */
    static List<String> fields(ObjectStreamClass c) {
        List<String> out = new ArrayList<>();
        for (ObjectStreamField f : c.getFields()) {
            out.add(f.getName() + " " + (f.isPrimitive() ? String.valueOf(f.getTypeCode()) : f.getTypeString()));
        }
        return out;
    }
}
