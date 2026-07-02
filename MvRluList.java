import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MV-RLU-simplified sorted linked list (Kim et al., ASPLOS 2019).
 *
 * MAPPING TO THE PAPER (and to design-decisions.md):
 *  - The only mutable field of a list node is its next pointer, so a
 *    node's "version chain" is a chain of Version records, each holding
 *    a next value and a final commitTs, newest first.
 *  - The paper's master/copy split is FOLDED into the chain head: in C
 *    it is a memory-locality optimization; in Java, `volatile head` +
 *    final Version fields give safe publication, so the newest committed
 *    version IS the master. M4's "write latest copy back to master"
 *    therefore collapses into chain pruning.
 *  - M1: one global AtomicLong clock. Writers getAndIncrement at commit;
 *    snapshot readers get at read_lock. (Deliberately reintroduces the
 *    global-clock bottleneck that RDTSC+ORDO removed -- documented.)
 *  - M7: writers use hand-over-hand locking (lazy-list style: lock pred
 *    [+ curr for remove], validate, publish, unlock). This serializes
 *    writers per node, so version publication needs NO helping: a writer
 *    assigns the final commitTs BEFORE the volatile head store.
 *    Plain lock() in list order replaces the paper's try_lock+abort;
 *    same conflict-serialization effect, simpler to reason about.
 *  - M2/M3: cooperative GC, capacity watermark only. Each writer logs
 *    the nodes it created versions for; when its log fills, IT runs a
 *    pruning pass (no background gp-detector thread).
 *  - M5: "reclamation" = cutting the .older link; the JVM collects.
 *
 * THE GC / READER RACE AND ITS FIX (the subtle part -- whiteboard-ready):
 *  A snapshot reader must publish its timestamp so GC keeps the versions
 *  it needs. Naive order [ts = clock.get(); slot.set(ts)] is broken: the
 *  thread can stall between the two, GC misses it, and prunes versions
 *  the reader still needs. Protocol used here:
 *    reader:  slot.set(0)  -> "active, timestamp pending, prune nothing"
 *             ts = clock.get(); slot.set(ts)   -> refine
 *             ... traverse ...   slot.set(QUIESCENT)
 *    gc:      ceil = clock.get()   BEFORE scanning slots
 *             B = min(ceil, every non-quiescent slot value)
 *             prune each logged node's chain at boundary B
 *  Why safe: every reader's ts is >= B. If GC saw the reader's slot
 *  (0 or ts), B <= that value <= ts. If GC missed it, the reader's
 *  clock read happened after GC's clock read, so ts >= ceil >= B.
 *  Pruning keeps every version with commitTs > B plus the newest one
 *  with commitTs <= B -- exactly what any reader with ts >= B can need.
 *
 * CONSISTENCY (design doc E2): snapshot queries are consistent
 * (they observe the committed state at their timestamp) but the whole
 * structure provides snapshot-isolation-style guarantees, not strict
 * linearizability of snapshots vs. the fast path. insert/remove/contains
 * are linearizable (lazy-list argument; remove's LP is setting the
 * removed flag under both locks).
 */
public final class MvRluList implements VersionedSet, Validatable {

    // ---- versioning core ----

    /** One committed value of a node's next pointer. */
    static final class Version {
        final Node next;        // the versioned field's value
        final long commitTs;    // final => safe publication via volatile head
        volatile Version older; // volatile so GC can cut the chain

        Version(Node next, long commitTs, Version older) {
            this.next = next;
            this.commitTs = commitTs;
            this.older = older;
        }
    }

    static final class Node {
        final int key;
        volatile Version head;      // newest committed version (never null)
        volatile boolean removed;   // logical deletion flag (lazy-list style)
        final ReentrantLock lock = new ReentrantLock();

        Node(int key, Node next, long ts) {
            this.key = key;
            this.head = new Version(next, ts, null);
        }
    }

    private static final long QUIESCENT = Long.MAX_VALUE;
    private static final long TS_PENDING = 0L;
    static final int LOG_CAPACITY = 1024; // M6: fixed per-thread log size

    private final AtomicLong clock = new AtomicLong(1); // M1
    private final Node head; // sentinel MIN -> sentinel MAX

    // Per-thread published reader state (M2). Slot value: QUIESCENT,
    // TS_PENDING (0), or the reader's active timestamp. Dead threads
    // leave QUIESCENT slots behind -- harmless for project scope.
    private final ConcurrentHashMap<Long, AtomicLong> readerSlots =
            new ConcurrentHashMap<Long, AtomicLong>();
    private final ThreadLocal<AtomicLong> mySlot = new ThreadLocal<AtomicLong>() {
        @Override protected AtomicLong initialValue() {
            AtomicLong slot = new AtomicLong(QUIESCENT);
            readerSlots.put(Thread.currentThread().getId(), slot);
            return slot;
        }
    };

    // Per-thread write log (M6): nodes whose chains this thread extended.
    private final ThreadLocal<ArrayList<Node>> myLog = new ThreadLocal<ArrayList<Node>>() {
        @Override protected ArrayList<Node> initialValue() {
            return new ArrayList<Node>(LOG_CAPACITY);
        }
    };

    public MvRluList() {
        Node tail = new Node(Integer.MAX_VALUE, null, 0L);
        head = new Node(Integer.MIN_VALUE, tail, 0L);
    }

    private static void checkKey(int key) {
        if (key == Integer.MIN_VALUE || key == Integer.MAX_VALUE)
            throw new IllegalArgumentException("sentinel key: " + key);
    }

    /** Newest committed next (the "master" view) -- writers & fast path. */
    private static Node latestNext(Node n) {
        return n.head.next;
    }

    /** Versioned dereference: next as of timestamp ts. */
    private static Node nextAt(Node n, long ts) {
        Version v = n.head;
        while (v != null && v.commitTs > ts) v = v.older;
        if (v == null)
            throw new IllegalStateException(
                "version chain underflow at key=" + n.key + " ts=" + ts
                + " (a version this reader needed was pruned -- GC bug)");
        return v.next;
    }

    // ---- writers (M7: hand-over-hand, lazy-list validation) ----

    @Override
    public boolean insert(int key) {
        checkKey(key);
        while (true) {
            Node pred = head;
            Node curr = latestNext(pred);
            while (curr.key < key) { pred = curr; curr = latestNext(curr); }

            pred.lock.lock();
            try {
                if (pred.removed || latestNext(pred) != curr) continue; // retry
                if (curr.key == key) return false; // present (curr can't be
                    // mid-removal: removing curr requires pred's lock)

                long ts = clock.getAndIncrement(); // M1: commit-ts
                // MV-RLU write-set semantics: the new node and pred's new
                // version share ONE commit-ts, so they become visible
                // atomically to versioned readers.
                Node fresh = new Node(key, curr, ts);
                pred.head = new Version(fresh, ts, pred.head); // volatile publish = commit
                logWrite(pred);
                return true;
            } finally {
                pred.lock.unlock();
            }
        }
    }

    @Override
    public boolean remove(int key) {
        checkKey(key);
        while (true) {
            Node pred = head;
            Node curr = latestNext(pred);
            while (curr.key < key) { pred = curr; curr = latestNext(curr); }
            if (curr.key != key) return false;

            pred.lock.lock();
            try {
                curr.lock.lock();
                try {
                    if (pred.removed || curr.removed || latestNext(pred) != curr)
                        continue; // retry
                    curr.removed = true;                    // LP of remove
                    long ts = clock.getAndIncrement();      // M1: commit-ts
                    pred.head = new Version(latestNext(curr), ts, pred.head);
                    logWrite(pred);
                    return true;
                } finally {
                    curr.lock.unlock();
                }
            } finally {
                pred.lock.unlock();
            }
        }
    }

    // ---- fast-path current read ----

    @Override
    public boolean contains(int key) {
        checkKey(key);
        Node curr = latestNext(head);
        while (curr.key < key) curr = latestNext(curr);
        return curr.key == key && !curr.removed;
    }

    // ---- snapshot queries (versioned read at a fixed timestamp) ----

    @Override
    public long sumRange(int lo, int hi) {
        AtomicLong slot = mySlot.get();
        slot.set(TS_PENDING);              // "active, prune nothing" (see header)
        long ts = clock.get();             // read_lock: take local-ts
        slot.set(ts);                      // refine
        try {
            long sum = 0;
            Node curr = nextAt(head, ts);
            while (curr.key < lo) curr = nextAt(curr, ts);
            while (curr.key <= hi && curr.key != Integer.MAX_VALUE) {
                sum += curr.key;
                curr = nextAt(curr, ts);
            }
            return sum;
        } finally {
            slot.set(QUIESCENT);           // read_unlock
        }
    }

    @Override
    public int sizeSnapshot() {
        AtomicLong slot = mySlot.get();
        slot.set(TS_PENDING);
        long ts = clock.get();
        slot.set(ts);
        try {
            int n = 0;
            for (Node curr = nextAt(head, ts);
                 curr.key != Integer.MAX_VALUE;
                 curr = nextAt(curr, ts)) n++;
            return n;
        } finally {
            slot.set(QUIESCENT);
        }
    }

    // ---- cooperative GC (M2/M3/M4/M5) ----

    private void logWrite(Node n) {
        ArrayList<Node> log = myLog.get();
        log.add(n);
        if (log.size() >= LOG_CAPACITY) {
            gcPass(log);
            log.clear();
        }
    }

    private void gcPass(ArrayList<Node> log) {
        // ORDER MATTERS: read the clock BEFORE scanning slots (see header).
        long boundary = clock.get();
        for (AtomicLong slot : readerSlots.values()) {
            long v = slot.get();
            if (v != QUIESCENT && v < boundary) boundary = v; // TS_PENDING=0 => prune nothing
        }
        for (int i = 0; i < log.size(); i++) prune(log.get(i), boundary);
    }

    /**
     * Keep every version with commitTs > boundary, plus the NEWEST version
     * with commitTs <= boundary (visible to the oldest possible reader);
     * cut the chain after it. Racing pruners are safe: a concurrent cut
     * only makes this walk stop early, which leaves extra versions (never
     * removes needed ones). This is also M4: the newest committed version
     * (the head) always survives -- it is the "master".
     */
    private static void prune(Node n, long boundary) {
        Version v = n.head;
        while (v.commitTs > boundary) {
            Version older = v.older;
            if (older == null) return; // chain already ends inside kept region
            v = older;
        }
        v.older = null; // v = newest version <= boundary; drop everything older
    }

    // ---- quiescent structural check ----

    @Override
    public void validateStructure() {
        if (head.key != Integer.MIN_VALUE)
            throw new IllegalStateException("head sentinel corrupted");
        Node cur = head;
        while (true) {
            // per-node chain: commit timestamps strictly decreasing
            Version v = cur.head;
            while (v.older != null) {
                if (v.older.commitTs >= v.commitTs)
                    throw new IllegalStateException("non-monotonic chain at key=" + cur.key);
                v = v.older;
            }
            Node nxt = latestNext(cur);
            if (nxt == null) break; // tail
            if (nxt.key <= cur.key)
                throw new IllegalStateException("order violation: " + cur.key + " -> " + nxt.key);
            cur = nxt;
        }
        if (cur.key != Integer.MAX_VALUE)
            throw new IllegalStateException("tail sentinel missing; last key=" + cur.key);
    }
}