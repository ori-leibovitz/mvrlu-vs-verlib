 Multi-Versioning in Concurrent Data Structures: MV-RLU vs. vCAS/Verlib

A direct, controlled comparison of two multi-versioning mechanisms for
concurrent data structures — **MV-RLU** (Kim et al., ASPLOS 2019) and
**vCAS/Verlib** (Wei et al., PPoPP 2021; Blelloch & Wei, PPoPP 2024) —
implemented in Java on an identical shared skeleton, so that the
versioning mechanism is the only experimental variable.

Final project for the Multicore Programming course. The accompanying
report (PDF) contains the paper summaries, design decisions, correctness
methodology, and full results.

 What's here:

| File | Role |
|---|---|
| `VersionedSet.java` | The shared contract: `insert/remove/contains` + atomic snapshot queries `sumRange/sizeSnapshot` |
| `Validatable.java` | Quiescent structural self-check hook |
| `CoarseLockList.java` | Baseline + oracle: sorted list under one global lock |
| `MvRluList.java` | MV-RLU-simplified sorted linked list (stamp-before-publish, writer-advanced clock, cooperative GC) |
| `VcasList.java` | vCAS-simplified sorted linked list (install-then-stamp with helping, snapshot-advanced camera) |
| `MvRluTree.java` | MV-RLU mechanism on an external BST (single-child-pointer updates) |
| `VcasTree.java` | vCAS mechanism on the same external BST |
| `StressTest.java` | 4-phase correctness harness: reconciliation, structure, frozen-range, flicker (torn-snapshot detection) |
| `Benchmark.java` | Experiment matrix driver: impls x threads x sizes x workload mixes, warmup + repetitions, CSV output |
| `MppRunner.java` | Server entry point; `MODE` switches between `"stress"` and `"bench"` |

Analysis artifacts: `results.csv` (raw benchmark summaries from the
96-core course server), `plots.py` (regenerates all figures), `fig*.png`.

Build

Requires any JDK 8+. The course server runs Java 17; compiling with
`--release 8` guarantees compatibility:

```
javac --release 8 -encoding UTF-8 -d out *.java
```

Run correctness (stress)

Locally, one implementation at a time
(`coarse | mvrlu | vcas | mvrlu-tree | vcas-tree`):

```
java -cp out StressTest mvrlu 8 20 128 42
#                       impl threads secondsPerPhase keyRange seed
```

A clean run prints `== ALL PHASES PASSED ==` and exits 0. The harness is
calibrated against the coarse-lock oracle: if it flags `coarse`, the bug
is in the harness.

On the course server: set `MODE = "stress"` in `MppRunner.java`,
recompile, upload **all** `.class` files from `out/` (including the
`$`-suffixed inner-class files); results appear in `mpp.out`
(~10 minutes for all five implementations at two thread counts).

Run benchmarks

Set `MODE = "bench"` in `MppRunner.java`, recompile, upload. The full
matrix (5 impls x {1,8,32,64} threads x {1K,16K} elements x 4 workload
mixes, 3 repetitions each) takes ~18 minutes. Output lines prefixed
`CSV,` / `SUMMARY,` are machine-readable. Note: prefill inserts keys in
a seeded-shuffled order — ascending insertion would degenerate the
external BSTs into linear chains.

Reproduce the figures

```
python3 plots.py   # reads results.csv, writes fig1..fig5
```

References

1. Kim, Mathew, Kashyap, Ramanathan, Min. *MV-RLU: Scaling Read-Log-Update with Multi-Versioning.* ASPLOS 2019.
2. Wei, Ben-David, Blelloch, Fatourou, Ruppert, Sun. *Constant-Time Snapshots with Applications to Concurrent Data Structures.* PPoPP 2021.
3. Blelloch, Wei. *VERLIB: Concurrent Versioned Pointers.* PPoPP 2024.
4. Matveev, Shavit, Felber, Marlier. *Read-Log-Update.* SOSP 2015.
