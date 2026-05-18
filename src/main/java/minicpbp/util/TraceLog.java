/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory trace recorder for search events. Companion to {@link Log}: same
 * static-facade shape, but instead of writing lines to a {@link java.io.PrintStream}
 * each call appends a frame to an in-memory buffer that the visualization
 * prototype reads back through {@link #snapshot()}.
 *
 * Frame shape follows the wire convention from plan-observability.md §4.2:
 * map keys are strings, the event-type ({@code "t"}) value is a string
 * (e.g. {@code "node-enter"}). Decoders apply keywordize-keys at the
 * boundary.
 *
 * Disabled by default; emitters are no-ops until {@link #enable()} is called.
 * MiniCPBP search is single-threaded so the backing list is unsynchronized —
 * do not call emitters from multiple threads concurrently.
 */
public final class TraceLog {

    private static final List<Map<String, Object>> frames = new ArrayList<>();
    private static long seq = 0L;
    private static long t0  = System.nanoTime();
    private static boolean enabled = false;

    private TraceLog() {} // utility class, no instantiation

    // ===== Lifecycle =====

    /** Begin recording. Frames already in the buffer are preserved (use {@link #clear()} first if undesired). */
    public static void enable() {
        enabled = true;
    }

    /** Stop recording. The buffer is left intact and can still be read via {@link #snapshot()}. */
    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Drop all frames, reset the sequence counter, and reset the timestamp baseline. */
    public static void clear() {
        frames.clear();
        seq = 0L;
        t0  = System.nanoTime();
    }

    /**
     * @return an unmodifiable shallow copy of the current frame buffer.
     *         The frames themselves are the live maps; callers should treat
     *         them as read-only.
     */
    public static List<Map<String, Object>> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(frames));
    }

    // ===== Emitters =====

    public static void nodeEnter(long node, long parent, int depth) {
        if (!enabled) return;
        Map<String, Object> f = baseFrame("node-enter");
        f.put("node",   node);
        f.put("parent", parent);
        f.put("depth",  depth);
        frames.add(f);
    }

    public static void nodeExit(long node, long parent, int depth) {
        if (!enabled) return;
        Map<String, Object> f = baseFrame("node-exit");
        f.put("node",   node);
        f.put("parent", parent);
        f.put("depth",  depth);
        frames.add(f);
    }

    public static void branch(long node, int index, int total) {
        if (!enabled) return;
        Map<String, Object> f = baseFrame("branch-taken");
        f.put("node",  node);
        f.put("index", index);
        f.put("total", total);
        frames.add(f);
    }

    public static void branchReturn(long node, int index, int total) {
        if (!enabled) return;
        Map<String, Object> f = baseFrame("branch-return");
        f.put("node",  node);
        f.put("index", index);
        f.put("total", total);
        frames.add(f);
    }

    public static void solution(long node) {
        if (!enabled) return;
        Map<String, Object> f = baseFrame("solution");
        f.put("node", node);
        frames.add(f);
    }

    public static void failure(long node) {
        if (!enabled) return;
        Map<String, Object> f = baseFrame("failure");
        f.put("node", node);
        frames.add(f);
    }

    // ===== Internals =====

    private static Map<String, Object> baseFrame(String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t",     type);
        f.put("seq",   seq++);
        f.put("ts-ns", System.nanoTime() - t0);
        return f;
    }
}
