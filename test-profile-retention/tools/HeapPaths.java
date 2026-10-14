import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shortest strong path from a GC root to every QuarkusClassLoader in a HotSpot HPROF dump.
 *
 * <p>Usage: {@code java tools/HeapPaths.java dump.hprof [--match <loader-name-substring>] [--cut-thread <prefix>]...
 * [--cut-field <declaring.Class#field>]... [--paths N]}
 *
 * <p>References from {@code java.lang.ref.Reference.referent} and {@code discovered} are not followed, so a loader
 * reachable only weakly or softly reports as unreachable, as Eclipse Memory Analyzer's "exclude weak/soft" does. An
 * instance holds its class and a class holds its loader. {@code --cut-thread} treats every Thread whose name starts
 * with the prefix as a dead end, roots included; {@code --cut-field} stops one field. A loader still reachable with a
 * pinner cut is retained by something else as well.
 */
public class HeapPaths {

    static java.nio.MappedByteBuffer[] chunks;
    static long length;
    static final int CHUNK = 1 << 30;

    static byte b(long p) { return chunks[(int) (p >>> 30)].get((int) (p & (CHUNK - 1))); }
    static int idSize;

    static final Map<Long, String> utf8 = new HashMap<>();
    static final Map<Long, String> classNames = new HashMap<>();
    static final Map<Long, ClassInfo> classes = new HashMap<>();

    static final class ClassInfo {
        long id, superId, loaderId;
        long[] fieldNameIds; byte[] fieldTypes;
        List<long[]> staticRefs = new ArrayList<>(); // {nameId, value}
        String name() { return classNames.getOrDefault(id, "0x" + Long.toHexString(id)); }
    }

    // object index
    static LongIntMap index = new LongIntMap(1 << 22);
    static long[] objIds = new long[1 << 20];
    static long[] objOffsets = new long[1 << 20]; // offset of the sub-record body (after tag)
    static byte[] objKinds = new byte[1 << 20]; // 1 instance, 2 objarray, 3 primarray, 4 class
    static int count;

    static final List<long[]> roots = new ArrayList<>(); // {objId, rootTag, threadSerial}
    static final Map<Long, Long> threadBySerial = new HashMap<>();

    public static void main(String[] args) throws IOException {
        String file = args[0];
        String match = null;
        List<String> cutThreads = new ArrayList<>();
        Set<String> cutFields = new HashSet<>();
        int maxPaths = Integer.MAX_VALUE;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--match" -> match = args[++i];
                case "--cut-thread" -> cutThreads.add(args[++i]);
                case "--cut-field" -> cutFields.add(args[++i]);
                case "--paths" -> maxPaths = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r"); FileChannel ch = raf.getChannel()) {
            length = ch.size();
            chunks = new java.nio.MappedByteBuffer[(int) ((length + CHUNK - 1) / CHUNK)];
            for (int i = 0; i < chunks.length; i++) {
                long start = (long) i * CHUNK;
                chunks[i] = ch.map(FileChannel.MapMode.READ_ONLY, start, Math.min(CHUNK, length - start));
            }
        }
        parse();
        System.out.printf("objects %,d, classes %,d, roots %,d%n", count, classes.size(), roots.size());

        Set<Integer> cut = new HashSet<>();
        if (!cutThreads.isEmpty()) {
            for (int i = 0; i < count; i++) {
                if (objKinds[i] != 1) continue;
                ClassInfo c = classes.get(classOf(i));
                if (c == null || !isSubclass(c, "java.lang.Thread")) continue;
                String n = string(fieldRef(i, "name"));
                if (n != null && cutThreads.stream().anyMatch(n::startsWith)) cut.add(i);
            }
            System.out.println("threads cut: " + cut.size());
        }

        int[] parent = new int[count];
        long[] parentLabel = new long[count];
        java.util.Arrays.fill(parent, -2); // -2 unvisited, -1 root
        int[] rootOf = new int[count];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int r = 0; r < roots.size(); r++) {
            int idx = index.get(roots.get(r)[0]);
            if (idx < 0 || parent[idx] != -2 || cut.contains(idx)) continue;
            Long owner = threadBySerial.get(roots.get(r)[2]);
            if (roots.get(r)[1] != 0x08 && owner != null && cut.contains(index.get(owner))) continue; // a frame of a cut thread
            parent[idx] = -1; rootOf[idx] = r; queue.add(idx);
        }
        while (!queue.isEmpty()) {
            int from = queue.poll();
            forEachRef(from, cutFields, (to, label) -> {
                int t = index.get(to);
                if (t < 0 || parent[t] != -2 || cut.contains(t)) return;
                parent[t] = from; parentLabel[t] = label; rootOf[t] = rootOf[from]; queue.add(t);
            });
        }

        TreeMap<String, Integer> summary = new TreeMap<>();
        int shown = 0, loaders = 0, reachable = 0;
        for (int i = 0; i < count; i++) {
            if (objKinds[i] != 1) continue;
            ClassInfo c = classes.get(classOf(i));
            if (c == null || !c.name().equals("io.quarkus.bootstrap.classloading.QuarkusClassLoader")) continue;
            String name = string(fieldRef(i, "name"));
            if (match != null && (name == null || !name.contains(match))) continue;
            loaders++;
            if (parent[i] == -2) continue;
            reachable++;
            List<String> hops = new ArrayList<>();
            for (int at = i; at >= 0; at = parent[at]) {
                String hop = describe(at);
                if (parent[at] >= 0) hop = "--" + label(parent[at], parentLabel[at]) + "--> " + hop;
                hops.add(0, hop);
                if (parent[at] == -1) hops.add(0, "ROOT " + rootDescription(rootOf[at]));
            }
            String key = String.join(" | ", hops.subList(0, Math.min(4, hops.size())));
            summary.merge(key, 1, Integer::sum);
            if (shown++ < maxPaths) {
                System.out.println("\n" + name);
                hops.forEach(h -> System.out.println("    " + h));
            }
        }
        System.out.printf("%nQuarkusClassLoaders matched %d, strongly reachable %d%n", loaders, reachable);
        System.out.println("paths grouped by root and first hops:");
        summary.forEach((k, v) -> System.out.println(String.format("%4d  ", v) + k));
    }

    // ---- parsing ----

    static void parse() {
        long p = 0;
        while (b(p) != 0) p++;
        p++;
        idSize = u4(p); p += 4 + 8;
        while (p < length) {
            int tag = b(p) & 0xff;
            long len = u4(p + 5) & 0xffffffffL;
            long body = p + 9;
            switch (tag) {
                case 0x01 -> utf8.put(id(body), new String(bytes(body + idSize, (int) (len - idSize)), StandardCharsets.UTF_8));
                case 0x02 -> classNames.put(id(body + 4), utf8.getOrDefault(id(body + 8 + idSize), "?").replace('/', '.'));
                case 0x0C, 0x1C -> heap(body, body + len);
                default -> { }
            }
            p = body + len;
        }
    }

    static void heap(long p, long end) {
        while (p < end) {
            int tag = b(p++) & 0xff;
            switch (tag) {
                case 0xFF -> { root(id(p), tag, -1); p += idSize; }
                case 0x01 -> { root(id(p), tag, -1); p += 2 * idSize; }
                case 0x02, 0x03 -> { root(id(p), tag, u4(p + idSize)); p += idSize + 8; }
                case 0x04, 0x06 -> { root(id(p), tag, u4(p + idSize)); p += idSize + 4; }
                case 0x05, 0x07 -> { root(id(p), tag, -1); p += idSize; }
                case 0x08 -> { threadBySerial.put((long) u4(p + idSize), id(p)); root(id(p), tag, u4(p + idSize)); p += idSize + 8; }
                case 0x20 -> p = classDump(p);
                case 0x21 -> { add(id(p), p, (byte) 1); p += 2 * idSize + 4 + 4 + (u4(p + 2 * idSize + 4) & 0xffffffffL); }
                case 0x22 -> { add(id(p), p, (byte) 2); p += 2 * idSize + 8 + (u4(p + idSize + 4) & 0xffffffffL) * idSize; }
                case 0x23 -> { add(id(p), p, (byte) 3); p += idSize + 9 + (u4(p + idSize + 4) & 0xffffffffL) * size(b(p + idSize + 8)); }
                default -> throw new IllegalStateException("heap sub-record 0x" + Integer.toHexString(tag) + " at " + (p - 1));
            }
        }
    }

    static long classDump(long p) {
        ClassInfo c = new ClassInfo();
        long start = p;
        c.id = id(p); p += idSize + 4;
        c.superId = id(p); p += idSize;
        c.loaderId = id(p); p += idSize;
        p += 4 * idSize + 4; // signers, protection domain, two reserved, instance size
        int cp = u2(p); p += 2;
        for (int i = 0; i < cp; i++) { int t = b(p + 2); p += 3 + size(t); }
        int statics = u2(p); p += 2;
        for (int i = 0; i < statics; i++) {
            long nameId = id(p); int t = b(p + idSize);
            if (t == 2) c.staticRefs.add(new long[] {nameId, id(p + idSize + 1)});
            p += idSize + 1 + size(t);
        }
        int fields = u2(p); p += 2;
        c.fieldNameIds = new long[fields]; c.fieldTypes = new byte[fields];
        for (int i = 0; i < fields; i++) { c.fieldNameIds[i] = id(p); c.fieldTypes[i] = b(p + idSize); p += idSize + 1; }
        classes.put(c.id, c);
        add(c.id, start, (byte) 4);
        return p;
    }

    static void root(long id, int tag, int serial) { roots.add(new long[] {id, tag, serial}); }

    static void add(long id, long offset, byte kind) {
        if (count == objIds.length) {
            int n = count * 2;
            objIds = java.util.Arrays.copyOf(objIds, n);
            objOffsets = java.util.Arrays.copyOf(objOffsets, n);
            objKinds = java.util.Arrays.copyOf(objKinds, n);
        }
        objIds[count] = id; objOffsets[count] = offset; objKinds[count] = kind;
        index.put(id, count++);
    }

    // ---- references ----

    interface RefSink { void ref(long to, long label); }

    static final long LABEL_CLASS = Long.MIN_VALUE, LABEL_LOADER = Long.MIN_VALUE + 1, LABEL_SUPER = Long.MIN_VALUE + 2;

    static void forEachRef(int idx, Set<String> cutFields, RefSink sink) {
        long p = objOffsets[idx];
        switch (objKinds[idx]) {
            case 1 -> {
                long classId = id(p + idSize + 4);
                sink.ref(classId, LABEL_CLASS);
                long q = p + 2 * idSize + 8;
                for (ClassInfo c = classes.get(classId); c != null; c = classes.get(c.superId)) {
                    boolean reference = c.name().equals("java.lang.ref.Reference");
                    for (int f = 0; f < c.fieldTypes.length; f++) {
                        if (c.fieldTypes[f] == 2) {
                            String fn = utf8.get(c.fieldNameIds[f]);
                            boolean weak = reference && ("referent".equals(fn) || "discovered".equals(fn));
                            if (!weak && !cutFields.contains(c.name() + "#" + fn)) sink.ref(id(q), c.fieldNameIds[f]);
                        }
                        q += size(c.fieldTypes[f]);
                    }
                }
            }
            case 2 -> {
                int n = u4(p + idSize + 4);
                long q = p + 2 * idSize + 8;
                for (int i = 0; i < n; i++) sink.ref(id(q + (long) i * idSize), -1 - i);
            }
            case 4 -> {
                ClassInfo c = classes.get(objIds[idx]);
                sink.ref(c.loaderId, LABEL_LOADER);
                sink.ref(c.superId, LABEL_SUPER);
                for (long[] s : c.staticRefs) {
                    if (!cutFields.contains(c.name() + "#" + utf8.get(s[0]))) sink.ref(s[1], s[0]);
                }
            }
            default -> { }
        }
    }

    static String label(int from, long label) {
        if (label == LABEL_CLASS) return "<class>";
        if (label == LABEL_LOADER) return "<classloader>";
        if (label == LABEL_SUPER) return "<super>";
        if (label < 0) return "[" + (-1 - label) + "]";
        String f = utf8.getOrDefault(label, "?");
        return objKinds[from] == 4 ? "static " + f : f;
    }

    static String describe(int idx) {
        switch (objKinds[idx]) {
            case 4: return "class " + classes.get(objIds[idx]).name();
            case 2: return classNames.getOrDefault(id(objOffsets[idx] + idSize + 8), "Object[]");
            case 3: return "primitive array";
            default:
        }
        ClassInfo c = classes.get(classOf(idx));
        String n = c == null ? "?" : c.name();
        if (c != null && isSubclass(c, "java.lang.Thread")) return n + " \"" + string(fieldRef(idx, "name")) + "\"";
        if (n.equals("io.quarkus.bootstrap.classloading.QuarkusClassLoader")) return n + " \"" + string(fieldRef(idx, "name")) + "\"";
        return n;
    }

    static String rootDescription(int r) {
        long[] root = roots.get(r);
        String kind = switch ((int) root[1]) {
            case 0xFF -> "unknown"; case 0x01 -> "JNI global"; case 0x02 -> "JNI local"; case 0x03 -> "Java frame";
            case 0x04 -> "native stack"; case 0x05 -> "system class"; case 0x06 -> "thread block";
            case 0x07 -> "monitor"; case 0x08 -> "thread object"; default -> "0x" + Long.toHexString(root[1]);
        };
        if ((root[1] == 0x02 || root[1] == 0x03 || root[1] == 0x06) && threadBySerial.containsKey(root[2])) {
            int t = index.get(threadBySerial.get(root[2]));
            if (t >= 0) kind += " of thread \"" + string(fieldRef(t, "name")) + "\"";
        }
        return kind;
    }

    static long classOf(int idx) { return id(objOffsets[idx] + idSize + 4); }

    static boolean isSubclass(ClassInfo c, String name) {
        for (ClassInfo k = c; k != null; k = classes.get(k.superId)) if (k.name().equals(name)) return true;
        return false;
    }

    /** The value of an object field of an instance, or 0. */
    static long fieldRef(int idx, String field) {
        if (idx < 0 || objKinds[idx] != 1) return 0;
        long q = objOffsets[idx] + 2 * idSize + 8;
        for (ClassInfo c = classes.get(classOf(idx)); c != null; c = classes.get(c.superId)) {
            for (int f = 0; f < c.fieldTypes.length; f++) {
                if (c.fieldTypes[f] == 2 && field.equals(utf8.get(c.fieldNameIds[f]))) return id(q);
                q += size(c.fieldTypes[f]);
            }
        }
        return 0;
    }

    static byte fieldByte(int idx, String field) {
        long q = objOffsets[idx] + 2 * idSize + 8;
        for (ClassInfo c = classes.get(classOf(idx)); c != null; c = classes.get(c.superId)) {
            for (int f = 0; f < c.fieldTypes.length; f++) {
                if (field.equals(utf8.get(c.fieldNameIds[f]))) return b(q);
                q += size(c.fieldTypes[f]);
            }
        }
        return 0;
    }

    static String string(long id) {
        int s = index.get(id);
        if (s < 0 || objKinds[s] != 1) return null;
        int v = index.get(fieldRef(s, "value"));
        if (v < 0 || objKinds[v] != 3) return null;
        long p = objOffsets[v];
        int n = u4(p + idSize + 4);
        long q = p + idSize + 9;
        return fieldByte(s, "coder") == 0
                ? new String(bytes(q, n), StandardCharsets.ISO_8859_1)
                : new String(bytes(q, n), StandardCharsets.UTF_16BE);
    }

    // ---- primitives ----

    static int size(int type) {
        return switch (type) { case 2 -> idSize; case 4, 8 -> 1; case 5, 9 -> 2; case 6, 10 -> 4; case 7, 11 -> 8;
            default -> throw new IllegalStateException("type " + type); };
    }

    static int u2(long p) { return ((b(p) & 0xff) << 8) | (b(p + 1) & 0xff); }

    static int u4(long p) { return ((b(p) & 0xff) << 24) | ((b(p + 1) & 0xff) << 16) | ((b(p + 2) & 0xff) << 8) | (b(p + 3) & 0xff); }

    static long id(long p) {
        if (idSize == 4) return u4(p) & 0xffffffffL;
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b(p + i) & 0xff);
        return v;
    }

    static byte[] bytes(long p, int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = b(p + i);
        return out;
    }

    /** Open-addressing long to int map; -1 for absent. */
    static final class LongIntMap {
        long[] keys; int[] values; int size;
        LongIntMap(int capacity) { keys = new long[capacity]; values = new int[capacity]; java.util.Arrays.fill(values, -1); }
        int get(long k) {
            if (k == 0) return -1;
            int m = keys.length - 1;
            for (int i = Long.hashCode(k * 0x9E3779B97F4A7C15L) & m; ; i = (i + 1) & m) {
                if (values[i] == -1) return -1;
                if (keys[i] == k) return values[i];
            }
        }
        void put(long k, int v) {
            if (size * 2 >= keys.length) grow();
            int m = keys.length - 1;
            for (int i = Long.hashCode(k * 0x9E3779B97F4A7C15L) & m; ; i = (i + 1) & m) {
                if (values[i] == -1 || keys[i] == k) { if (values[i] == -1) size++; keys[i] = k; values[i] = v; return; }
            }
        }
        void grow() {
            long[] ok = keys; int[] ov = values;
            keys = new long[ok.length * 2]; values = new int[ov.length * 2]; java.util.Arrays.fill(values, -1); size = 0;
            for (int i = 0; i < ok.length; i++) if (ov[i] != -1) put(ok[i], ov[i]);
        }
    }
}
