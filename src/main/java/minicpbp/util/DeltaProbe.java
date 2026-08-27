/*
 * mini-cpbp: delta-sparsity probe (BP_PROBE_PROTOCOL.md, Probe A).
 *
 * Measures, per factor update, how much of the input that updateBelief()
 * consumes (the outsideBelief rows of the unbound scope positions) actually
 * changed since the same factor's previous update. This is the measured
 * ceiling on factor-internal incrementality (BP_SCHEDULING.md 2.5: SumDC
 * layer reuse, seeded inner BP in AllDifferentDC), gathered before building
 * any of it.
 *
 * Counters only: no engine behavior changes, no solver state is written.
 * ENABLED is a static final read once from a system property, so when the
 * probe is off the hook in AbstractConstraint is dead code.
 *
 * All accumulators are plain statics: one JVM runs one configuration on one
 * instance (exp.Bench), and BP is single-threaded (same convention as BPStats).
 */

package minicpbp.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

public final class DeltaProbe {

    /** compile the hook in; dead code everywhere when false */
    public static final boolean ENABLED = Boolean.getBoolean("minicpbp.probe.deltaSparsity");
    /** CSV destination; empty prints to stderr */
    private static final String FILE = System.getProperty("minicpbp.probe.deltaFile", "");
    /**
     * epsilon-reuse pilot (BP_PROBE_PROTOCOL.md Probe D): skip updateBelief()
     * when the input moved by at most this since the factor's LAST ACTUAL
     * COMPUTE, within one BP invocation only. Negative disables (default).
     */
    public static final double EPS_REUSE =
            Double.parseDouble(System.getProperty("minicpbp.probe.epsReuse", "-1"));
    /** the AbstractConstraint hook compiles to dead code unless one probe is on */
    public static final boolean HOOK = ENABLED || EPS_REUSE >= 0;

    /* Probe D counters */
    public static long epsChecked;  // factor updates that went through the hook
    public static long epsSkipped;  // ... of which skipped updateBelief() entirely

    /** maxDelta histogram bin upper bounds (standard representation); the
     *  last bin is everything above 1e-3, structural changes included */
    static final double[] BINS = {0.0, 1e-12, 1e-9, 1e-6, 1e-3};

    /** one accumulator per (constraint class, bucket) */
    public static final class Acc {
        public long updates;        // factor updates observed
        public long rows;           // unbound scope positions summed over updates
        public long rowsChanged;    // ... of which bit-changed (or domain-changed)
        public long allClean;       // updates where no row changed at all
        public double sumReusableFrac; // prefix+suffix clean fraction, summed
        public long[] hist = new long[BINS.length + 1]; // maxDelta per update
    }

    /** bucket 0: first update of this factor in a new BP invocation (the delta
     *  spans CP filtering + everything since the last invocation: the
     *  trigger-side quantity). bucket 1: within one invocation (sweep-to-sweep:
     *  the stop/schedule-side quantity). */
    public static final String[] BUCKET = {"cross", "within"};

    private static final Map<String, Acc[]> byClass = new TreeMap<>();

    private DeltaProbe() {
    }

    public static void record(String cls, int bucket, int rows, int changed,
                              int firstDirty, int lastDirty, double maxDelta) {
        Acc[] pair = byClass.get(cls);
        if (pair == null) {
            pair = new Acc[]{new Acc(), new Acc()};
            byClass.put(cls, pair);
        }
        Acc a = pair[bucket];
        a.updates++;
        a.rows += rows;
        a.rowsChanged += changed;
        if (changed == 0) {
            a.allClean++;
            a.sumReusableFrac += 1.0;
        } else {
            a.sumReusableFrac += (double) (firstDirty + (rows - 1 - lastDirty)) / rows;
        }
        int b = BINS.length;
        for (int i = 0; i < BINS.length; i++) {
            if (maxDelta <= BINS[i]) {
                b = i;
                break;
            }
        }
        a.hist[b]++;
    }

    private static boolean installed = false;

    public static synchronized void install() {
        if (installed || !HOOK) return;
        installed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(DeltaProbe::dump));
    }

    public static void dump() {
        if (EPS_REUSE >= 0) {
            // one machine-greppable line for the pilot's skip-rate collection
            System.err.println("epsReuse=" + EPS_REUSE
                    + " epsChecked=" + epsChecked
                    + " epsSkipped=" + epsSkipped
                    + " epsSkipFraction=" + (epsChecked == 0 ? 0.0 : (double) epsSkipped / epsChecked));
        }
        if (!ENABLED) return;
        StringBuilder sb = new StringBuilder();
        sb.append("class,bucket,updates,rows,rowsChanged,dirtyRowFrac,allCleanUpdates,")
                .append("allCleanFrac,meanReusableFrac,")
                .append("dEq0,dLe1e12,dLe1e9,dLe1e6,dLe1e3,dGt1e3\n");
        for (Map.Entry<String, Acc[]> e : byClass.entrySet()) {
            for (int bkt = 0; bkt < 2; bkt++) {
                Acc a = e.getValue()[bkt];
                if (a.updates == 0) continue;
                sb.append(e.getKey()).append(',').append(BUCKET[bkt]).append(',')
                        .append(a.updates).append(',')
                        .append(a.rows).append(',')
                        .append(a.rowsChanged).append(',')
                        .append(a.rows == 0 ? 0.0 : (double) a.rowsChanged / a.rows).append(',')
                        .append(a.allClean).append(',')
                        .append((double) a.allClean / a.updates).append(',')
                        .append(a.sumReusableFrac / a.updates);
                for (long h : a.hist) sb.append(',').append(h);
                sb.append('\n');
            }
        }
        if (FILE.isEmpty()) {
            System.err.print(sb);
        } else {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(FILE, true))) {
                w.write(sb.toString());
            } catch (IOException ex) {
                System.err.println("delta probe: " + ex.getMessage());
                System.err.print(sb);
            }
        }
    }
}
