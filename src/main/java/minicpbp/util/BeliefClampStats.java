/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Counters for the signed-circuit belief-contract violations discovered on
 * 2026-08-19 (TODO.md item 1, CarSequencing lobianco crash): constraints whose
 * belief circuits use subtraction/complement (NegTableCT, Maximum,
 * Element1DVar, IsEqual*, ...) can emit local beliefs of -epsilon where the
 * exact value is 0 (floating-point cancellation). Downstream consumers that
 * require genuine probabilities (Soules U^3 requires a NONNEGATIVE matrix)
 * then fail: gamma[-8] AIOOBE in SoulesUB3.ub3 via LoBiancoBound.
 *
 * Contract finding, not just a bug (research_plan section 3 / section 5.1):
 * composite beliefs are NOT guaranteed to be probability distributions when
 * any constraint in the network evaluates a signed circuit in floating point.
 * The clamp restores the contract at the framework boundary
 * (AbstractConstraint.setLocalBelief) and every clamp is counted here so the
 * violation rate is measured, never silent (suggestions_gcc_binpacking.md
 * contract 7).
 */

package minicpbp.util;

public final class BeliefClampStats {

    /** local beliefs clamped to zero at AbstractConstraint.setLocalBelief */
    public static long localBeliefClamps;
    /** largest magnitude clamped: epsilon-scale = benign cancellation;
     *  order-1 values would indicate a genuine circuit bug, not rounding */
    public static double localBeliefClampMaxMagnitude;
    /** non-positive/non-finite matrix entries skipped inside SoulesUB3 */
    public static long soulesEntrySkips;

    private BeliefClampStats() {
    }

    public static void recordClamp(double std) {
        localBeliefClamps++;
        double mag = -std;
        if (mag > localBeliefClampMaxMagnitude) localBeliefClampMaxMagnitude = mag;
    }

    public static String line() {
        return "localBeliefClamps=" + localBeliefClamps
                + " localBeliefClampMaxMagnitude=" + localBeliefClampMaxMagnitude
                + " soulesEntrySkips=" + soulesEntrySkips;
    }
}
