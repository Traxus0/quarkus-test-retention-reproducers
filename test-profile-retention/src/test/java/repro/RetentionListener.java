package repro;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * After every test class: two full GCs, then the live QuarkusClassLoaders (the same diagnostic command as
 * {@code jcmd <pid> VM.classloader_stats}), the live heap, the long-lived threads and the registered shutdown hooks,
 * each named with the classloader it keeps reachable.
 *
 * <p>Registered through {@code META-INF/services}, so JUnit loads it before any Quarkus classloader exists.
 */
public class RetentionListener implements TestExecutionListener {

    private static final Pattern NAMED_LOADER = Pattern.compile("\"([^\"]*)\",\\s*io\\.quarkus\\.bootstrap\\.classloading\\.QuarkusClassLoader");

    private int classes;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        append("retention.report", "variant,after_class,classes_run,quarkus_loaders,heap_used_kb,ryuk_threads,jgit_threads,shutdown_hooks\n", true);
        detail(true, "listener loaded by " + RetentionListener.class.getClassLoader()
                + "\nvariant " + System.getProperty("retention.variant") + "\n");
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!(id.getSource().orElse(null) instanceof ClassSource source)) return;
        classes++;
        try {
            dcmd("gcRun");
            Thread.sleep(500);
            dcmd("gcRun");
            String stats = dcmd("vmClassloaderStats");
            String hierarchy = dcmd("vmClassloaders");
            long loaders = stats.lines().filter(l -> !l.trim().startsWith("+")
                    && l.trim().endsWith("io.quarkus.bootstrap.classloading.QuarkusClassLoader")).count();
            long heapKb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024;

            Map<String, Integer> byKind = new TreeMap<>();
            Matcher m = NAMED_LOADER.matcher(hierarchy);
            while (m.find()) byKind.merge(kind(m.group(1)), 1, Integer::sum);

            List<String> pinners = new ArrayList<>();
            int ryuk = 0, jgit = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String n = t.getName();
                boolean isRyuk = n.startsWith("testcontainers-ryuk");
                boolean isJgit = n.startsWith("JGit-WorkQueue");
                if (isRyuk) ryuk++;
                if (isJgit) jgit++;
                if (isRyuk || isJgit || n.startsWith("ducttape") || n.startsWith("testcontainers")) {
                    pinners.add("thread " + n + " ctx=" + name(t.getContextClassLoader()));
                }
            }
            List<String> hooks = shutdownHooks();
            String variant = System.getProperty("retention.variant");
            String simple = source.getClassName().substring(source.getClassName().lastIndexOf('.') + 1);
            String row = String.join(",", '"' + variant + '"', simple, String.valueOf(classes), String.valueOf(loaders),
                    String.valueOf(heapKb), String.valueOf(ryuk), String.valueOf(jgit), String.valueOf(hooks.size())) + "\n";
            append("retention.report", row, false);
            System.out.printf("[retention] after %s (%d classes): %d QuarkusClassLoaders %s, heap %,d KB, ryuk threads %d, JGit threads %d, shutdown hooks %d%n",
                    simple, classes, loaders, byKind, heapKb, ryuk, jgit, hooks.size());
            StringBuilder d = new StringBuilder("\n== after " + simple + " (" + classes + " classes) ==\n");
            d.append("loaders by kind ").append(byKind).append('\n');
            if (Boolean.getBoolean("retention.hierarchy")) d.append(hierarchy).append('\n');
            pinners.stream().sorted().forEach(p -> d.append("  ").append(p).append('\n'));
            hooks.stream().sorted().forEach(h -> d.append("  ").append(h).append('\n'));
            detail(false, d.toString());
            if (simple.equals(System.getProperty("retention.dumpAfter"))) {
                Path dump = Path.of(System.getProperty("retention.report")).resolveSibling("after-" + simple + ".hprof");
                Files.deleteIfExists(dump);
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
                System.out.println("[retention] live heap dump " + dump);
            }
        } catch (Exception e) {
            System.out.println("[retention] measurement failed: " + e);
            java.io.StringWriter w = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(w));
            detail(false, "measurement failed: " + w);
        }
    }

    private static String kind(String loaderName) {
        int colon = loaderName.indexOf(':');
        return colon < 0 ? loaderName : loaderName.substring(0, colon);
    }

    private static String name(ClassLoader cl) {
        if (cl == null) return "bootstrap";
        String n = cl.getName();
        return (n == null ? cl.getClass().getName() : n) + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    /** Every registered, unstarted shutdown hook: its class, its runnable's class and the loaders each keeps. */
    @SuppressWarnings("unchecked")
    private static List<String> shutdownHooks() throws ReflectiveOperationException {
        Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
        Field field = hooksClass.getDeclaredField("hooks");
        field.setAccessible(true);
        List<String> out = new ArrayList<>();
        Map<Thread, Thread> hooks;
        synchronized (hooksClass) {
            hooks = new java.util.IdentityHashMap<>((Map<Thread, Thread>) field.get(null));
        }
        for (Thread hook : hooks.keySet()) {
            Object task = runnableOf(hook);
            String taskDesc = task == null ? "-" : task.getClass().getName() + " loader=" + name(task.getClass().getClassLoader());
            out.add("hook " + hook.getName() + " class=" + hook.getClass().getName() + " loader="
                    + name(hook.getClass().getClassLoader()) + " ctx=" + name(hook.getContextClassLoader()) + " task=" + taskDesc);
        }
        return out;
    }

    private static Object runnableOf(Thread t) {
        try {
            Field holder = Thread.class.getDeclaredField("holder");
            holder.setAccessible(true);
            Object h = holder.get(t);
            Field task = h.getClass().getDeclaredField("task");
            task.setAccessible(true);
            return task.get(h);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String dcmd(String operation, String... options) throws Exception {
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        javax.management.MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        boolean takesOptions = java.util.Arrays.stream(server.getMBeanInfo(name).getOperations())
                .anyMatch(o -> o.getName().equals(operation) && o.getSignature().length == 1);
        Object out = takesOptions
                ? server.invoke(name, operation, new Object[] {options}, new String[] {String[].class.getName()})
                : server.invoke(name, operation, null, null);
        return out == null ? "" : out.toString();
    }

    private static void detail(boolean truncate, String text) {
        String report = System.getProperty("retention.report");
        if (report == null) return;
        write(Path.of(report.replace(".csv", "-details.txt")), text, truncate);
    }

    private static void append(String property, String text, boolean truncate) {
        String report = System.getProperty(property);
        if (report != null) write(Path.of(report), text, truncate);
    }

    private static void write(Path path, String text, boolean truncate) {
        try {
            Files.createDirectories(path.getParent());
            if (truncate) Files.writeString(path, text);
            else Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[retention] could not write " + path + ": " + e);
        }
    }
}
