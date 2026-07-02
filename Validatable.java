/**
 * Optional structural self-check hook. Implementations that can walk
 * their own internals (all of ours can) should implement this so the
 * stress test can verify structural invariants at quiescence:
 * strictly sorted, no duplicates, sentinels intact.
 *
 * Called ONLY when no other thread is operating on the structure.
 */
public interface Validatable {
    /** @throws IllegalStateException describing the first violation found. */
    void validateStructure();
}