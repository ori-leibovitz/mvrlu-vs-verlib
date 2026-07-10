
public class MppRunner {

    // ---- MODE: "stress" (correctness acceptance) or "bench" 
    
    private static final String MODE = "stress";

    // ---- stress knobs ----
    private static final String[] IMPLS = { "coarse", "mvrlu", "vcas", "mvrlu-tree", "vcas-tree" };
    private static final int SECONDS_PER_PHASE = 20;
    private static final int KEY_RANGE = 128;   // narrow on purpose: contention
    private static final long SEED = 42L;

    public static void main(String[] args) throws Exception {
        if (MODE.equals("bench")) {
            Benchmark.runAll(false);
            return;
        }
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