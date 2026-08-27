# State of the gcc belief kernel

2026-08-25. Audit of `CardinalityDC.updateBelief()` and `GccBP` against a set
of proposed optimizations (complement, reverse-mode adjoint DP, structural
caching, FFT product tree, Poisson-binomial approximation). No code was
changed; this records what is in the tree and what the remaining items would
actually buy.

Files audited:

- `src/main/java/minicpbp/util/GccBP.java` (576 lines)
- `src/main/java/minicpbp/engine/constraints/CardinalityDC.java` (497 lines)
- `src/main/java/minicpbp/util/GccConfig.java`
- `src/main/java/minicpbp/util/GccBeliefHarvester.java`

## Summary table

| Item | Status | Where |
| --- | --- | --- |
| Complement (c ↔ D−c) | implemented | `GccBP.java:413-433`, `:461-464`, `:500-520` |
| `c ∈ {0, 1, D−1, D}` fast paths | redundant — width already collapses | see below |
| Sparse edge lists, shared outsider ratio | implemented | `GccBP.java:324-358`, `r0[j]` |
| Forward DP + reverse-mode adjoint | **not implemented** | `GccBP.java:154-155`, `:479-532` |
| Kill the per-call `O(nk)` structural scan | **not implemented** | `GccBP.java:325-358`, `CardinalityDC.java:385-405` |
| Harvest the (D_j, q_j) distribution | derivable, not tabulated | `GccBeliefHarvester` |
| FFT / product tree | **not implemented** | absent from tree |
| Poisson-binomial approximate cavity | **not implemented** | absent from tree |
| Unconstrained-class short circuit | **not implemented** | — |

Grep for `Poisson`, `saddlepoint`, `adjoint`, `reverse-mode`, `FFT` over
`src/main/java` and `src/test/java` returns nothing.

## What is implemented

### Complement side

The newest change in the kernel. Per class, with `cmin` the smallest count
carrying nonzero potential weight (capped at `low[j]`, lowered only when a
general caller places `wOcc` weight below `low`):

```
uC   = D - cmin + 1
comp = uC >= 1 && uC < uP
u    = comp ? uC : uP
```

Selecting `c` successes among `D` edges equals selecting `D − c` failures, so
a class whose feasible counts sit near `D` runs the DP on the failure side.
Cost becomes `O(D · min(up, D − cmin + 1))`, the `O(D · min(c, D − c))` dual
for exact cardinalities.

The `comp` branch is threaded through all four stages: the prefix convolution
(`q0`/`q1` swapped), the weighted backward seed (reversed weights `w'(d) =
w(D − d)`, defined against the full degree `D` so the leave-one-out shift needs
no re-indexing), the leave-one-out (the two sums swap roles), and the `msgOcc`
write (count reversed back to primal). The phantom outsider at `q == D` is
computed directly in complement mode, since it shifts the primal count up.

Below `cmin − 1` the complement's `msgOcc` entries are zeroed — unrepresentable
overflow mass. No consumer reads that range: `CardinalityDC` masks to
`[low, up]`, `GccPhase1` iterates the same range.

### Fast paths are already redundant

Explicit closed forms for `c ∈ {0, 1, D−1, D}` are not present, but the DP
width already collapses for each of them:

| case | resulting `u` | cost |
| --- | --- | --- |
| `c = 0` | 0 | `O(D)` |
| `c = 1` | 1 | `O(D)` |
| `c = D − 1` | 2 (via complement) | `O(2D)` |
| `c = D` | 1 (via complement) | `O(D)` |

Adding hand-written formulas would remove constants, not a factor. Low
priority.

### Sparse edge lists

A variable with `a[i][j] == 0` has `mu = 0`, an exact identity step in the
convolutions, and its leave-one-out set is the full edge set of the class —
the same for every such variable. So the sweeps visit only the `E` real edges
and emit one shared outsider ratio `r0[j]` per class. Edge messages are
bit-identical to the frozen dense reference; non-edge messages agree up to
summation order.

Per-sweep cost: `O(E + Σ_j D_j (u_j + 2))`.

## What is not implemented

### 1. Reverse-mode adjoint DP (highest-value remaining item)

`ensure()` still allocates both tables:

```java
pre = new double[n + 1][maxU + 2];
suf = new double[n + 1][maxU + 2];
```

The backward pass writes the full weighted suffix table for every `q`
(`:479-488`), and a third loop then scans `pre[q] × suf[q+1]` per edge
(`:500-532`).

The suffix seed at `suf[D]` is already the weight vector φ — that is, λ_D — so
the code is one step away from the adjoint form. Only `λ_{q+1}` is ever needed
at step `q`, so the entire `O(Du)` suffix table is storage the computation does
not require. Replacing it with a single rolling adjoint vector removes:

- half the large scratch allocation,
- all suffix-table writes and their later reads,
- one traversal (suffix-build followed by a separate edge scan becomes one
  reverse traversal).

Caveat on the expected gain: `pre[q]` is still needed for every `q` (it feeds
the per-edge derivative), and the per-edge `O(u)` inner product remains. This
is a constant-factor memory-traffic win, not an asymptotic one. It is still
the cheapest correctness-preserving speedup available, and it returns exactly
the same messages, so the frozen-reference equivalence tests remain valid as
the acceptance criterion.

### 2. Per-call `O(nk)` structural work, paid twice

`GccBP.run()` rescans the dense `a` matrix on every call to build the edge
lists and the FNV structure signature (`:325-358`). `CardinalityDC` has
already traversed every domain immediately beforehand to fill `a`/`b`
(`:385-396`).

Additionally, `CardinalityDC.java:400` allocates a fresh `double[up[j] + 1]`
per class per call for `wOcc`. That is a per-call allocation on the hot path,
not on the original recommendation list, and in the same category of waste.

With `BP_ITERS = 5` (and belief-stability early exit often stopping sooner),
the `O(nk)` setup is not amortized over the sweeps. Deep in search, where
`E ≪ nk`, it can dominate. Options: pass sparse class lists directly from
`CardinalityDC`, or cache the edge lists behind domain modification stamps.
Worth profiling separately from the factor kernel — the fix is mechanical, and
the current data does not tell us which of the two costs is the real one.

### 3. FFT / product tree

Absent. Cannot be judged without the actual `(D_j, q_j)` distribution.
`GccBeliefHarvester` already dumps whole collapsed systems (`n, k, low, up,
wOcc, a, b`), so the distribution is derivable from existing dumps with a
small offline script — no instrumentation change needed. Published crossovers
put direct convolution ahead of FFT below roughly `D ≈ 200`–750, so if
car-sequencing factors are small or narrow this is wasted effort. Gate the
decision on the harvest.

### 4. Approximate Poisson-binomial cavity

Absent. `GccConfig.BeliefRoutine` offers `EXACT`, `BP`, `AUTO`, `UNIFORM`,
`LOBIANCO` — no approximate-cavity arm. This is the direction with the largest
possible speedup (`O(Dq) → O(D)`) and the most research value, since nested BP
is already an approximation used only to rank branching choices. Adding it
means a new enum arm plus a message-quality comparison against the exact
kernel; it does not touch the existing DP.

### 5. Unconstrained-class short circuit

A class with `[low, up] = [0, D]` and uniform `wOcc` has all factor messages
equal to 1, but currently runs the full DP. Cheap to add, frequency unknown —
another question the harvest answers.

## Recommended order

1. Reverse-mode adjoint replacing `pre + suf + cavity scan`. Same messages,
   verifiable against the frozen reference, no behavioural risk.
2. Profile and remove the duplicated `O(nk)` scan and the per-call `wOcc`
   allocation.
3. Tabulate `(D_j, q_j)` from existing harvester dumps. That single number set
   decides items 3 and 5 above.
4. Only then consider FFT (if large balanced factors are common) or the
   approximate cavity (if the kernel still costs wall time after 1 and 2).
