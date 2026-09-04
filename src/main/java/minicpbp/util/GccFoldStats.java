/*
 * mini-cpbp: census counters for the WP1 closed-case fold
 * (AMONG_GCC_OPTIMIZATION_PLAN.md WP1). Plain static longs, no
 * synchronisation: one solve runs in one thread.
 */
package minicpbp.util;

public final class GccFoldStats {
    /** CardinalityDC.updateBelief calls that reached the dispatch. */
    public static long calls = 0;
    /** of those, the ones where the T2 condition held and a class was folded. */
    public static long folded = 0;

    static {
        // -Dminicpbp.gcc.foldStats=true prints the census line on JVM exit, on
        // stderr (harnesses redirect stdout while solving).
        if (Boolean.getBoolean("minicpbp.gcc.foldStats")) {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    System.err.println("c " + describe());
                }
            }));
        }
    }

    private GccFoldStats() {
    }

    public static String describe() {
        return "gccFold calls=" + calls + " folded=" + folded
                + (calls > 0 ? String.format(java.util.Locale.ROOT, " (%.1f%%)", 100.0 * folded / calls) : "");
    }
}
