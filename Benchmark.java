import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Day-3 benchmark driver. Measures throughput across the experiment
 * matrix from the project plan:
 *
 *   implementations x thread counts x workload mixes x structure sizes,
 *   each cell: JIT warmup, then REPS timed repetitions on a FRESH,
 *   deterministically prefilled instance (so version chains from one
 *   repetition never pollute the next).
 *
 * Output format (goes to mpp.out on the server):
 *   CSV,impl,threads,size,mix,rep,total_ops,snap_ops,ops_per_sec
 *   ...one line per repetition, then:
 *   SUMMARY,impl,threads,size,mix,mean_ops_per_sec,stddev,reps
 * Grep "^CSV" / "^SUMMARY" to extract; both are machine-readable.
 *
 * Workload mixes (contains/insert/remove/snapshot %):
 *   read_heavy       90/ 5/ 5/ 0            (H1)
 *   write_heavy      10/45/45/ 0            (H2/H5: clock asymmetry)
 *   read_snap_short  60/10/10/20, window=64 (H3: camera contention)
 *   write_snap_long  30/30/30/10, full scan (H4: long snapshots vs churn)
 * insert/remove are balanced and keys are drawn from [0, 2*size), so the
 * structure hovers around `size` elements (50% density) all run long.
 *
 * DCE protection: every operation's result is folded into a per-thread
 * accumulator that is published to a volatile sink after the run --
 * the JIT cannot prove results unused and delete the work.
 *
 * Estimated full-matrix runtime with the defaults below:
 *   3 impls x 4 threads x 4 mixes x 2 sizes = 96 cells x ~6s  =~ 10-11 min.
 * If the server's job limit is tighter, trim SIZES to {1024} or REPS to 2
 * (halves the time); the matrix is just constants below.
 */
public final class Benchmark {

    // ---- experiment matrix (edit + recompile to trim) ----
    static final String[] IMPLS   = { "coarse", "mvrlu", "vcas", "mvrlu-tree", "vcas-tree" };
    static final int[]    THREADS = { 1, 8, 32, 64 };
    static final int[]    SIZES   = { 1024, 16384 };
    static final int      REPS        = 3;
    static final int      WARMUP_MS   = 1500;
    static final int      MEASURE_MS  = 1500;
    static final int      SHORT_WINDOW = 64;
    static final long     SEED = 42L;

    static final class Mix {
        final String name;
        final int containsPct, insertPct, removePct, snapPct;
        final boolean fullSnap; // full-range scan vs SHORT_WINDOW
        Mix(String name, int c, int i, int r, int s, boolean fullSnap) {
            this.name = name; this.containsPct = c; this.insertPct = i;
            this.removePct = r; this.snapPct = s; this.fullSnap = fullSnap;
            if (c + i + r + s != 100) throw new IllegalArgumentException(name);
        }
    }

    static final Mix[] MIXES = {
        new Mix("read_heavy",      90,  5,  5,  0, false),
        new Mix("write_heavy",     10, 45, 45,  0, false),
        new Mix("read_snap_short", 60, 10, 10, 20, false),
        new Mix("write_snap_long", 30, 30, 30, 10, true),
    };

    // Volatile sink defeating dead-code elimination.
    static volatile long SINK;

    public static void main(String[] args) throws Exception {
        boolean quick = args.length > 0 && args[0].equals("quick");
        runAll(quick);
    }

    /** quick=true: tiny matrix for local plumbing checks only. */
    public static void runAll(boolean quick) throws Exception {
        String[] impls  = IMPLS;
        int[] threadsAx = quick ? new int[]{2} : THREADS;
        int[] sizesAx   = quick ? new int[]{256} : SIZES;
        int reps        = quick ? 1 : REPS;
        int warmupMs    = quick ? 300 : WARMUP_MS;
        int measureMs   = quick ? 300 : MEASURE_MS;

        System.out.println("=== Versioning project: benchmark ===");
        System.out.println("cores=" + Runtime.getRuntime().availableProcessors()
                + " java=" + System.getProperty("java.version")
                + " warmupMs=" + warmupMs + " measureMs=" + measureMs + " reps=" + reps);
        System.out.println("CSV,impl,threads,size,mix,rep,total_ops,snap_ops,ops_per_sec");

        for (String impl : impls) {
            for (int size : sizesAx) {
                for (Mix mix : MIXES) {
                    for (int threads : threadsAx) {
                        runCell(impl, threads, size, mix, reps, warmupMs, measureMs);
                    }
                }
            }
        }
        System.out.println("=== BENCHMARK COMPLETE ===");
    }

    private static void runCell(String impl, int threads, int size, Mix mix,
                                int reps, int warmupMs, int measureMs) throws Exception {
        // JIT warmup on a throwaway instance (not measured).
        runOnce(impl, threads, size, mix, warmupMs, /*rep*/ -1);

        long[] rates = new long[reps];
        for (int r = 0; r < reps; r++) {
            rates[r] = runOnce(impl, threads, size, mix, measureMs, r);
        }
        double mean = 0;
        for (long x : rates) mean += x;
        mean /= reps;
        double var = 0;
        for (long x : rates) var += (x - mean) * (x - mean);
        double std = reps > 1 ? Math.sqrt(var / (reps - 1)) : 0;
        System.out.printf("SUMMARY,%s,%d,%d,%s,%.0f,%.0f,%d%n",
                impl, threads, size, mix.name, mean, std, reps);
    }

    /** One timed run on a fresh prefilled instance; returns ops/sec. */
    private static long runOnce(String impl, int threads, int size, Mix mix,
                                int durationMs, int rep) throws Exception {
        final VersionedSet set = StressTest.create(impl);
        final int keyRange = 2 * size;

        // Deterministic 50%-density prefill, in SHUFFLED order: ascending
        // insertion would degenerate the external BSTs into linear chains
        // (depth ~size instead of ~2*log2), poisoning both performance and
        // comparability. Shuffle is seeded => still fully reproducible.
        Random pre = new Random(SEED);
        java.util.ArrayList<Integer> keys = new java.util.ArrayList<Integer>(keyRange);
        for (int k = 0; k < keyRange; k++) if (pre.nextBoolean()) keys.add(k);
        java.util.Collections.shuffle(keys, pre);
        for (int i = 0; i < keys.size(); i++) set.insert(keys.get(i));

        final AtomicBoolean stop = new AtomicBoolean(false);
        final CyclicBarrier start = new CyclicBarrier(threads + 1);
        final long[] ops = new long[threads];
        final long[] snapOps = new long[threads];

        ArrayList<Thread> pool = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(new Runnable() {
                @Override public void run() {
                    Random rnd = new Random(SEED * 31 + id);
                    long acc = 0, n = 0, sn = 0;
                    try {
                        start.await();
                        while (!stop.get()) {
                            int roll = rnd.nextInt(100);
                            int key = rnd.nextInt(keyRange);
                            if (roll < mix.containsPct) {
                                acc += set.contains(key) ? 1 : 0;
                            } else if (roll < mix.containsPct + mix.insertPct) {
                                acc += set.insert(key) ? 1 : 0;
                            } else if (roll < mix.containsPct + mix.insertPct + mix.removePct) {
                                acc += set.remove(key) ? 1 : 0;
                            } else if (mix.fullSnap) {
                                acc += set.sumRange(0, keyRange - 1);
                                sn++;
                            } else {
                                int lo = Math.min(key, keyRange - SHORT_WINDOW);
                                acc += set.sumRange(lo, lo + SHORT_WINDOW - 1);
                                sn++;
                            }
                            n++;
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    ops[id] = n;
                    snapOps[id] = sn;
                    SINK += acc; // publish accumulator: no dead-code elimination
                }
            });
            th.start();
            pool.add(th);
        }

        start.await();
        Thread.sleep(durationMs);
        stop.set(true);
        for (Thread th : pool) th.join();

        long total = 0, snaps = 0;
        for (int t = 0; t < threads; t++) { total += ops[t]; snaps += snapOps[t]; }
        long rate = total * 1000L / durationMs;
        if (rep >= 0) {
            System.out.printf("CSV,%s,%d,%d,%s,%d,%d,%d,%d%n",
                    impl, threads, size, mix.name, rep, total, snaps, rate);
        }
        return rate;
    }
}