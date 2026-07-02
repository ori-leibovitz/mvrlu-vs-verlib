/**
 * Entry point for the college multicore server (same batch flow as the
 * course homework: upload ALL .class files, the server invokes
 * MppRunner.main() with no args, output lands in mpp.out).
 *
 * TONIGHT (Day 1): stress-test acceptance run on the coarse-lock oracle,
 * at two thread counts. A clean run here officially closes Day 1.
 *
 * DAY 2: change IMPLS to {"coarse","mvrlu"} then {"coarse","mvrlu","vcas"}
 * and re-upload after every implementation milestone.
 *
 * DAY 3: this class also becomes the benchmark driver (add the benchmark
 * loop below the stress section, or gate with a MODE constant).
 *
 * Tune SECONDS_PER_PHASE to the server's job time limit — with the
 * defaults below: 2 configs x 3 phases x 20s = ~2 minutes total.
 */
public class MppRunner {

    // ---- knobs (edit here, recompile, re-upload) ----
    private static final String[] IMPLS = { "coarse", "mvrlu", "vcas" };
    private static final int SECONDS_PER_PHASE = 20;
    private static final int KEY_RANGE = 128;   // narrow on purpose: contention
    private static final long SEED = 42L;

    public static void main(String[] args) {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("=== Versioning project: stress acceptance ===");
        System.out.println("availableProcessors = " + cores);
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("os = " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        // Two thread counts: a fixed 8, and one that saturates the machine
        // (capped so a huge core count doesn't explode a narrow key range).
        int high = Math.max(8, Math.min(cores, 64));
        int[] threadCounts = (high == 8) ? new int[]{8} : new int[]{8, high};

        boolean allOk = true;
        for (String impl : IMPLS) {
            for (int t : threadCounts) {
                System.out.printf("%n##### impl=%s writers=%d #####%n", impl, t);
                allOk &= StressTest.runAll(impl, t, SECONDS_PER_PHASE, KEY_RANGE, SEED);
            }
        }

        System.out.println();
        System.out.println(allOk ? "##### STRESS ACCEPTANCE: PASSED #####"
                                 : "##### STRESS ACCEPTANCE: FAILED #####");
        System.out.println("=== RUN COMPLETE ===");
    }
}