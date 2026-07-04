import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stress test for VersionedSet implementations.
 *
 * Four checks, in order of increasing subtlety:
 *
 *  PHASE 1 — RECONCILIATION: N writer threads hammer a NARROW key range
 *    (narrow = maximal contention = maximal chance of exposing races),
 *    each logging its SUCCESSFUL inserts/removes per key. At the end,
 *    for every key: (#successful inserts) - (#successful removes) must
 *    be 0 or 1, and must equal the key's final presence. Any other
 *    outcome is impossible under a correct linearizable set — a lost
 *    update, double insert, or phantom remove shows up here.
 *    Concurrent snapshot threads also run, sanity-bounded.
 *
 *  PHASE 2 — STRUCTURE: at quiescence, the list must be strictly
 *    sorted with intact sentinels (via Validatable).
 *
 *  PHASE 3 — FROZEN RANGE: a segment of keys is pre-filled and NEVER
 *    touched; churn threads mutate only keys BELOW it. A snapshot
 *    thread repeatedly computes sumRange over the frozen segment and
 *    asserts it equals the exact pre-computed constant. This catches
 *    snapshot traversals corrupted by concurrent structural changes
 *    along the path to the segment.
 *
 *  PHASE 4 — FLICKER (snapshot atomicity): with two adjacent keys A,B,
 *    a single mutator cycles the set through {A} -> {A,B} -> {B} ->
 *    {A,B} -> {A} ... so at EVERY instant at least one of A,B is
 *    present. sumRange(A,B) must therefore always be in {A, B, A+B}.
 *    Observing 0 (or anything else) means the query read a torn,
 *    non-atomic view — exactly the bug class versioning must prevent.
 *
 * Usage:
 *   java StressTest [implName] [writerThreads] [secondsPerPhase] [keyRange] [seed]
 * Defaults: coarse 8 15 128 42
 *
 * Acceptance bar for tonight: a clean run on 'coarse' with 8+ threads.
 * If this harness flags the coarse-lock baseline, the bug is in the
 * harness — fix it TONIGHT, before it is trusted on Day 2.
 */
public final class StressTest {

    // ---------------------------------------------------------------
    // Implementation registry — add MvRluList / VcasList here on Day 2.
    // ---------------------------------------------------------------
    static VersionedSet create(String name) {
        switch (name) {
            case "coarse":     return new CoarseLockList();
            case "mvrlu":      return new MvRluList();
            case "vcas":       return new VcasList();
            case "mvrlu-tree": return new MvRluTree();
            case "vcas-tree":  return new VcasTree();
            default: throw new IllegalArgumentException("unknown impl: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        String impl   = args.length > 0 ? args[0] : "coarse";
        int writers   = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int seconds   = args.length > 2 ? Integer.parseInt(args[2]) : 15;
        int keyRange  = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        long seed     = args.length > 4 ? Long.parseLong(args[4]) : 42L;
        System.exit(runAll(impl, writers, seconds, keyRange, seed) ? 0 : 1);
    }

    /**
     * Runs all stress phases for one configuration. Returns true iff all
     * passed. Batch-friendly (no System.exit) so MppRunner can chain
     * several configurations in one server job.
     */
    public static boolean runAll(String impl, int writers, int seconds,
                                 int keyRange, long seed) {
        System.out.printf("StressTest impl=%s writers=%d secondsPerPhase=%d keyRange=%d seed=%d cores=%d%n",
                impl, writers, seconds, keyRange, seed, Runtime.getRuntime().availableProcessors());

        boolean ok = true;
        ok &= runPhase("PHASE 1+2: reconciliation + structure",
                () -> phaseReconciliation(create(impl), writers, seconds, keyRange, seed));
        ok &= runPhase("PHASE 3: frozen-range snapshot",
                () -> phaseFrozenRange(create(impl), writers, seconds, keyRange, seed));
        ok &= runPhase("PHASE 4: flicker (snapshot atomicity)",
                () -> phaseFlicker(create(impl), seconds, seed));

        System.out.println(ok ? "== ALL PHASES PASSED ==" : "== FAILURES DETECTED ==");
        return ok;
    }

    private interface Phase { void run() throws Exception; }

    private static boolean runPhase(String name, Phase p) {
        System.out.println("---- " + name + " ----");
        long t0 = System.nanoTime();
        try {
            p.run();
            System.out.printf("PASS (%.1fs)%n", (System.nanoTime() - t0) / 1e9);
            return true;
        } catch (Throwable t) {
            System.out.printf("FAIL (%.1fs): %s%n", (System.nanoTime() - t0) / 1e9, t);
            t.printStackTrace(System.out);
            return false;
        }
    }

    // ---------------------------------------------------------------
    // PHASE 1+2
    // ---------------------------------------------------------------
    private static void phaseReconciliation(VersionedSet set, int writers,
                                            int seconds, int keyRange, long seed) throws Exception {
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> firstError = new AtomicReference<>();
        final CyclicBarrier start = new CyclicBarrier(writers + 1 /*snapshot*/ + 1 /*main*/);

        // Per-thread success logs (no sharing during the run -> no
        // synchronization artifacts in the measurement itself).
        final int[][] insOk = new int[writers][keyRange];
        final int[][] remOk = new int[writers][keyRange];
        final long[] opCounts = new long[writers];

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < writers; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                Random rnd = new Random(seed * 1_000_003L + id); // reproducible per thread
                try {
                    start.await();
                    long ops = 0;
                    while (!stop.get()) {
                        int key = rnd.nextInt(keyRange);
                        int op = rnd.nextInt(100);
                        if (op < 40) {                    // 40% insert
                            if (set.insert(key)) insOk[id][key]++;
                        } else if (op < 80) {             // 40% remove
                            if (set.remove(key)) remOk[id][key]++;
                        } else {                          // 20% contains
                            set.contains(key);
                        }
                        ops++;
                    }
                    opCounts[id] = ops;
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                    stop.set(true);
                }
            }, "writer-" + id);
            th.start();
            threads.add(th);
        }

        // One concurrent snapshot thread: results only sanity-bounded here;
        // the sharp atomicity checks are phases 3 and 4.
        Thread snap = new Thread(() -> {
            try {
                start.await();
                long maxSum = 0;
                for (int k = 0; k < keyRange; k++) maxSum += k;
                while (!stop.get()) {
                    int size = set.sizeSnapshot();
                    if (size < 0 || size > keyRange)
                        throw new IllegalStateException("sizeSnapshot out of bounds: " + size);
                    long sum = set.sumRange(0, keyRange - 1);
                    if (sum < 0 || sum > maxSum)
                        throw new IllegalStateException("sumRange out of bounds: " + sum);
                }
            } catch (Throwable e) {
                firstError.compareAndSet(null, e);
                stop.set(true);
            }
        }, "snapshot");
        snap.start();
        threads.add(snap);

        start.await();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread th : threads) th.join();
        if (firstError.get() != null) throw new AssertionError(firstError.get());

        // ---- Reconciliation (quiescent) ----
        long totalOps = 0;
        for (long c : opCounts) totalOps += c;
        System.out.printf("  writer ops total: %,d (%,.0f ops/s)%n", totalOps, totalOps / (double) seconds);

        long expectedSum = 0;
        int expectedCount = 0;
        for (int k = 0; k < keyRange; k++) {
            long net = 0;
            for (int t = 0; t < writers; t++) net += insOk[t][k] - remOk[t][k];
            if (net != 0 && net != 1)
                throw new AssertionError("key " + k + ": net successful inserts-removes = " + net
                        + " (impossible for a correct set: lost update / double insert / phantom remove)");
            boolean expected = (net == 1);
            boolean actual = set.contains(k);
            if (expected != actual)
                throw new AssertionError("key " + k + ": logs say present=" + expected
                        + " but contains() says " + actual);
            if (expected) { expectedSum += k; expectedCount++; }
        }
        int size = set.sizeSnapshot();
        if (size != expectedCount)
            throw new AssertionError("sizeSnapshot=" + size + " expected=" + expectedCount);
        long sum = set.sumRange(0, keyRange - 1);
        if (sum != expectedSum)
            throw new AssertionError("sumRange=" + sum + " expected=" + expectedSum);

        // ---- PHASE 2: structural invariants at quiescence ----
        if (set instanceof Validatable) ((Validatable) set).validateStructure();
        System.out.printf("  reconciliation clean: %d keys present, sum=%d%n", expectedCount, expectedSum);
    }

    // ---------------------------------------------------------------
    // PHASE 3
    // ---------------------------------------------------------------
    private static void phaseFrozenRange(VersionedSet set, int writers,
                                         int seconds, int keyRange, long seed) throws Exception {
        // Frozen segment ABOVE the churn window: churn on [0, keyRange),
        // frozen keys on [keyRange, keyRange + F).
        final int F = 64;
        final int fLo = keyRange, fHi = keyRange + F - 1;
        long frozenSumTmp = 0;
        for (int k = fLo; k <= fHi; k++) {
            if (!set.insert(k)) throw new AssertionError("prefill failed for " + k);
            frozenSumTmp += k;
        }
        final long frozenSum = frozenSumTmp;

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> firstError = new AtomicReference<>();
        final CyclicBarrier start = new CyclicBarrier(writers + 1 + 1);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < writers; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                Random rnd = new Random(seed * 7_000_003L + id);
                try {
                    start.await();
                    while (!stop.get()) {
                        int key = rnd.nextInt(keyRange); // strictly below frozen segment
                        if (rnd.nextBoolean()) set.insert(key); else set.remove(key);
                    }
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                    stop.set(true);
                }
            }, "churn-" + id);
            th.start();
            threads.add(th);
        }

        Thread snap = new Thread(() -> {
            try {
                start.await();
                long reads = 0;
                while (!stop.get()) {
                    long s = set.sumRange(fLo, fHi);
                    if (s != frozenSum)
                        throw new IllegalStateException("frozen-range sum drifted: got " + s
                                + " expected " + frozenSum
                                + " (snapshot corrupted by concurrent churn on the path)");
                    reads++;
                }
                System.out.printf("  frozen-range reads verified: %,d%n", reads);
            } catch (Throwable e) {
                firstError.compareAndSet(null, e);
                stop.set(true);
            }
        }, "frozen-snap");
        snap.start();
        threads.add(snap);

        start.await();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread th : threads) th.join();
        if (firstError.get() != null) throw new AssertionError(firstError.get());
    }

    // ---------------------------------------------------------------
    // PHASE 4
    // ---------------------------------------------------------------
    private static void phaseFlicker(VersionedSet set, int seconds, long seed) throws Exception {
        final int A = 1000, B = 1001; // adjacent -> maximal traversal races
        if (!set.insert(A)) throw new AssertionError("prefill A failed");

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> firstError = new AtomicReference<>();
        final CyclicBarrier start = new CyclicBarrier(3);

        Thread mutator = new Thread(() -> {
            try {
                start.await();
                boolean haveA = true; // invariant: insert the new key BEFORE removing the old
                while (!stop.get()) {
                    if (haveA) { set.insert(B); set.remove(A); haveA = false; }
                    else       { set.insert(A); set.remove(B); haveA = true; }
                }
            } catch (Throwable e) {
                firstError.compareAndSet(null, e);
                stop.set(true);
            }
        }, "flicker-mutator");

        Thread observer = new Thread(() -> {
            try {
                start.await();
                long reads = 0, seenA = 0, seenB = 0, seenBoth = 0;
                while (!stop.get()) {
                    long s = set.sumRange(A, B);
                    if (s == A) seenA++;
                    else if (s == B) seenB++;
                    else if (s == A + B) seenBoth++;
                    else throw new IllegalStateException(
                            "torn snapshot: sumRange(A,B)=" + s
                            + " but at every instant at least one of {A,B} is present"
                            + " (legal values: " + A + ", " + B + ", " + (A + B) + ")");
                    reads++;
                }
                System.out.printf("  flicker reads: %,d (A-only=%,d B-only=%,d both=%,d)%n",
                        reads, seenA, seenB, seenBoth);
                if (seenBoth == 0 && reads > 100_000)
                    System.out.println("  note: never observed {A,B} — mutator may be too fast; not a failure.");
            } catch (Throwable e) {
                firstError.compareAndSet(null, e);
                stop.set(true);
            }
        }, "flicker-observer");

        mutator.start();
        observer.start();
        start.await();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        mutator.join();
        observer.join();
        if (firstError.get() != null) throw new AssertionError(firstError.get());
    }
}