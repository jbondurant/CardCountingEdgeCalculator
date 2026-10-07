import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The classes the whole-file full-index run c06c061 was built from, which wrote its results.ser
 * and part checkpoints, loaded beside this build's under a loader of their own, so a test can set
 * what they compute beside what this build computes, and read with this build what they wrote.
 * They live in runs/full-index/classes-c06c061 under the main checkout, which git ignores, so a
 * test run in a worktree finds them two levels up; -Dc06c061=PATH names another. Tests that need
 * them are skipped, and say so, where they are not there. They are only ever read.
 */
final class C06c061 {

    private C06c061() {
    }

    static Path dir() {
        List<Path> tried = new ArrayList<>();
        String named = System.getProperty("c06c061");
        if (named != null) {
            tried.add(Paths.get(named));
        }
        tried.add(Paths.get("runs", "full-index", "classes-c06c061"));
        tried.add(Paths.get("..", "..", "runs", "full-index", "classes-c06c061"));
        for (Path p : tried) {
            if (Files.isRegularFile(p.resolve("CountKeys$Count.class")) && Files.isRegularFile(p.resolve("commit.txt"))) {
                return p;
            }
        }
        assumeTrue(false, "no c06c061 classes at " + tried + "; these checks need them");
        return null;
    }

    /**
     * A loader of c06c061's classes alone, with the BSON classes they read the catalog with: its
     * parent is the platform loader, so it never finds this build's classes of the same names.
     */
    static ClassLoader loader() throws Exception {
        URL bson = org.bson.Document.class.getProtectionDomain().getCodeSource().getLocation();
        return new URLClassLoader(new URL[]{dir().toUri().toURL(), bson}, ClassLoader.getPlatformClassLoader());
    }

    static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
        Method m = c.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target instanceof Class ? null : target, args);
    }

    static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    static Object make(ClassLoader l, String cls, Class<?>[] types, Object... args) throws Exception {
        Constructor<?> c = l.loadClass(cls).getDeclaredConstructor(types);
        c.setAccessible(true);
        return c.newInstance(args);
    }
}
