import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Verlib/vCAS-simplified sorted linked list, built on the versioned-CAS
 * mechanism of Wei et al. (PPoPP 2021), the foundation Verlib (PPoPP 2024)
 * extends.
 *
 * WHAT IS FAITHFULLY vCAS HERE (the identity of the approach -- V1):
 *  - Every node's next pointer is a VERSIONED POINTER: volatile vhead ->
 *    VNode { value, ts, older }, one VNode per successful write.
 *  - TIMESTAMPS ARE SET AFTER INSTALLATION, WITH HELPING (initTS): a new
 *    version is installed with ts = TBD; anyone who encounters a TBD head
 *    -- reader, snapshot, other writer, GC -- first stamps it with the
 *    CURRENT camera value (CAS, losers no-op), then proceeds. A write's
 *    linearization point is the fixing of its timestamp. This is the
 *    paper's central trick and the reason snapshots are consistent.
 *  - THE CAMERA IS ADVANCED BY SNAPSHOTS, NOT BY WRITERS: takeSnapshot
 *    reads the counter and tries ONE CAS increment (failure is fine --
 *    someone else advanced it). Writers only READ the camera when
 *    stamping. This is the exact opposite of (our) MV-RLU, where every
 *    commit increments the global clock -- 
 *    the benchmarks are designed to expose.
 *    Why it works: a snapshot that obtained ts=T bumped the camera to
 *    T+1, so any version stamped after the bump gets >= T+1 and is
 *    excluded; a version stamped T (from a pre-bump camera read)
 *    linearizes before the snapshot and is included. readVersion(ts)
 *    returns the newest version with vts <= ts.
 *
 * WHAT IS SIMPLIFIED (documented deviations):
 *  - LIST SKELETON: lazy-list with per-node locks (hand-over-hand
 *    validation), NOT Harris's lock-free list. We give up writer
 *    lock-freedom -- part of vCAS's identity -- in exchange for
 *    structural symmetry with MvRluList: same locks, same traversal,
 *    same cooperative GC. That isolates the versioning mechanism as the
 *    ONLY experimental variable (the fair-comparison principle). Note verlib itself
 *    supports lock-based structures, so this stays inside its scope.
 *    The lock also means a pointer's install cannot race another install
 *    on the SAME pointer, but stamping still races readers/snapshots --
 *    so initTS helping remains fully load-bearing.
 *  - V2 (recorded-once, drop indirection): NOT implemented (extension).
 *    We measure full-indirection vCAS = upper bound on its read cost.
 *  - V3: version-chain GC is the same cooperative scheme as MvRluList
 *    (per-thread write log, capacity trigger, prune to boundary B), with
 *    the same stall-race fix: reader publishes slot=0 BEFORE taking its
 *    snapshot, then refines; GC reads the camera BEFORE scanning slots,
 *    guaranteeing B <= every reader's ts.
 *  - V4: one global camera; only next pointers are versioned; the
 *    immutable key is a plain final field.
 *
 * CONSISTENCY: snapshot queries here are linearizable (the vCAS
 * guarantee), unlike MvRluList's snapshot-isolation-style behavior --
 * a deliberate, documented asymmetry between the two approaches.
 */
public final class VcasList implements VersionedSet, Validatable {

    static final long TBD = -1L;

    /** One version of a node's next pointer. */
    static final class VNode {
        final Node next;
        volatile long ts;       // TBD until stamped; CAS'd via TS updater
        volatile VNode older;   // volatile so GC can cut the chain

        VNode(Node next, long ts, VNode older) {
            this.next = next;
            this.ts = ts;
            this.older = older;
        }
    }

    private static final AtomicLongFieldUpdater<VNode> TS =
            AtomicLongFieldUpdater.newUpdater(VNode.class, "ts");

    static final class Node {
        final int key;
        volatile VNode vhead;       // newest version; ONLY the head may be TBD
        volatile boolean removed;   // logical deletion (lazy-list style)
        final ReentrantLock lock = new ReentrantLock();

        Node(int key, Node next, long ts) {
            this.key = key;
            this.vhead = new VNode(next, ts, null);
        }
    }

    private static final long QUIESCENT = Long.MAX_VALUE;
    private static final long SLOT_PENDING = 0L;
    static final int LOG_CAPACITY = 1024;

    // Camera (V4): starts at 1 so pre-stamped initial versions (ts=0)
    // precede every possible snapshot.
    private final AtomicLong camera = new AtomicLong(1);
    private final Node head;

    private final ConcurrentHashMap<Long, AtomicLong> readerSlots =
            new ConcurrentHashMap<Long, AtomicLong>();
    private final ThreadLocal<AtomicLong> mySlot = new ThreadLocal<AtomicLong>() {
        @Override protected AtomicLong initialValue() {
            AtomicLong slot = new AtomicLong(QUIESCENT);
            readerSlots.put(Thread.currentThread().getId(), slot);
            return slot;
        }
    };
    private final ThreadLocal<ArrayList<Node>> myLog = new ThreadLocal<ArrayList<Node>>() {
        @Override protected ArrayList<Node> initialValue() {
            return new ArrayList<Node>(LOG_CAPACITY);
        }
    };

    public VcasList() {
        Node tail = new Node(Integer.MAX_VALUE, null, 0L);
        head = new Node(Integer.MIN_VALUE, tail, 0L);
    }

    private static void checkKey(int key) {
        if (key == Integer.MIN_VALUE || key == Integer.MAX_VALUE)
            throw new IllegalArgumentException("sentinel key: " + key);
    }

    // ---- the vCAS core ----

    /** HELPING: stamp a TBD version with the current camera value. */
    private void initTS(VNode v) {
        if (v.ts == TBD) {
            long cur = camera.get();
            TS.compareAndSet(v, TBD, cur); // losers no-op -- idempotent
        }
    }

    /** vRead: current value (stamp the head first, per the paper). */
    private Node latestNext(Node n) {
        VNode h = n.vhead;
        initTS(h);
        return h.next;
    }

    /** readVersion(ts): newest version with vts <= ts. */
    private Node nextAt(Node n, long ts) {
        VNode v = n.vhead;
        initTS(v); // only the head may be TBD; older ones are always stamped
        while (v != null && v.ts > ts) v = v.older;
        if (v == null)
            throw new IllegalStateException(
                "version chain underflow at key=" + n.key + " ts=" + ts
                + " (a version this reader needed was pruned -- GC bug)");
        return v.next;
    }

    /** takeSnapshot: read camera, ONE CAS attempt to advance (V1). */
    private long takeSnapshot() {
        long ts = camera.get();
        camera.compareAndSet(ts, ts + 1); // failure fine: someone advanced it
        return ts;
    }

    /**
     * Install a new version of pred.next under pred's lock:
     * stamp the old head, install the new one as TBD, then help-stamp it.
     * (Install-then-stamp is the vCAS order; the per-node lock removes
     * install/install races but NOT stamp races with readers.)
     */
    private void installNext(Node pred, Node newNext) {
        VNode h = pred.vhead;
        initTS(h);                              // invariant: only head is TBD
        VNode fresh = new VNode(newNext, TBD, h);
        pred.vhead = fresh;                     // volatile publish (install)
        initTS(fresh);                          // stamp (LP of this write)
        logWrite(pred);
    }

    // ---- writers (lazy-list skeleton, symmetric to MvRluList) ----

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
                if (curr.key == key) return false;

                // New node's initial version is stamped NOW (no one can see
                // it yet), guaranteeing its ts <= the ts of pred's new
                // version -- so any snapshot that sees pred -> fresh can
                // also read fresh.next.
                Node fresh = new Node(key, curr, camera.get());
                installNext(pred, fresh);
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
                    curr.removed = true;
                    installNext(pred, latestNext(curr));
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

    // ---- snapshot queries ----

    @Override
    public long sumRange(int lo, int hi) {
        AtomicLong slot = mySlot.get();
        slot.set(SLOT_PENDING);            // "active, prune nothing" (V3)
        long ts = takeSnapshot();          // camera advanced by SNAPSHOTS
        slot.set(ts);
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
            slot.set(QUIESCENT);
        }
    }

    @Override
    public int sizeSnapshot() {
        AtomicLong slot = mySlot.get();
        slot.set(SLOT_PENDING);
        long ts = takeSnapshot();
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

    // ---- cooperative version GC (V3, symmetric to MvRluList M2) ----

    private void logWrite(Node n) {
        ArrayList<Node> log = myLog.get();
        log.add(n);
        if (log.size() >= LOG_CAPACITY) {
            gcPass(log);
            log.clear();
        }
    }

    private void gcPass(ArrayList<Node> log) {
        long boundary = camera.get(); // read camera BEFORE scanning slots
        for (AtomicLong slot : readerSlots.values()) {
            long v = slot.get();
            if (v != QUIESCENT && v < boundary) boundary = v;
        }
        for (int i = 0; i < log.size(); i++) prune(log.get(i), boundary);
    }

    /** Keep versions with ts > B plus the newest with ts <= B; cut after. */
    private void prune(Node n, long boundary) {
        VNode v = n.vhead;
        initTS(v); // never compare against TBD
        while (v.ts > boundary) {
            VNode older = v.older;
            if (older == null) return;
            v = older;
        }
        v.older = null;
    }

    // ---- quiescent structural check ----

    @Override
    public void validateStructure() {
        if (head.key != Integer.MIN_VALUE)
            throw new IllegalStateException("head sentinel corrupted");
        Node cur = head;
        while (true) {
            VNode v = cur.vhead;
            initTS(v);
            while (v.older != null) {
                // camera advances only on snapshots, so several versions may
                // legitimately SHARE a timestamp: non-increasing, not strict.
                if (v.older.ts == TBD)
                    throw new IllegalStateException("non-head TBD at key=" + cur.key);
                if (v.older.ts > v.ts)
                    throw new IllegalStateException("non-monotonic chain at key=" + cur.key);
                v = v.older;
            }
            Node nxt = latestNext(cur);
            if (nxt == null) break;
            if (nxt.key <= cur.key)
                throw new IllegalStateException("order violation: " + cur.key + " -> " + nxt.key);
            cur = nxt;
        }
        if (cur.key != Integer.MAX_VALUE)
            throw new IllegalStateException("tail sentinel missing; last key=" + cur.key);
    }
}