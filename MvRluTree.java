import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MV-RLU-simplified EXTERNAL binary search tree.
 *
 * Same versioning mechanism as MvRluList (stamp-BEFORE-publish under
 * per-node locks, global AtomicLong clock advanced by writers, the same
 * cooperative GC with the same reader-slot protocol and boundary proof)
 * -- only the skeleton changes from a sorted list to an external BST:
 *
 *  - EXTERNAL tree: keys live ONLY in leaves; internal nodes are pure
 *    routers with the invariant  left-subtree keys < node.key <= right.
 *    This is what keeps updates single-pointer: insert replaces a leaf
 *    with a fresh (internal + two leaves) triangle; remove splices the
 *    leaf's PARENT out by pointing the grandparent at the sibling. In
 *    both cases exactly ONE existing child pointer changes -- the exact
 *    analogue of pred.next in the list, so the whole list-side proof
 *    story (single-write commits, one shared commit-ts for the freshly
 *    created nodes, lock-then-validate) carries over unchanged.
 *  - Sentinels: root = internal(MAX_VALUE) whose left child starts as a
 *    dummy leaf(MAX_VALUE); every real key is < MAX so searches always
 *    descend left of the root. The dummy leaf is never removed, which
 *    guarantees every real leaf has a grandparent (no root special case
 *    in remove -- argued in remove()).
 *  - Versioned pointer = Ref: one per child slot (two per internal
 *    node). The GC log stores Refs, since pruning operates per chain.
 *  - Locking: remove locks grandparent THEN parent (ancestor-first).
 *    Nodes never move (no rotations), so ancestor order between live
 *    nodes never inverts and ancestor-first ordering is deadlock-free
 *    -- same argument as pred-then-curr in the list.
 *  - Snapshot traversals are ITERATIVE (explicit stack): a degenerate
 *    subtree must not overflow the call stack.
 */
public final class MvRluTree implements VersionedSet, Validatable {

    static final class Version {
        final Node child;
        final long commitTs;    // final => safe publication via volatile head
        volatile Version older; // volatile so GC can cut the chain

        Version(Node child, long commitTs, Version older) {
            this.child = child;
            this.commitTs = commitTs;
            this.older = older;
        }
    }

    /** A versioned child pointer (one per child slot of an internal node). */
    static final class Ref {
        volatile Version head;
        Ref(Node child, long ts) { head = new Version(child, ts, null); }
    }

    static final class Node {
        final int key;
        final Ref left, right;      // null iff leaf
        volatile boolean removed;   // logical deletion flag
        final ReentrantLock lock = new ReentrantLock();

        Node(int key) { this.key = key; this.left = null; this.right = null; } // leaf
        Node(int key, Node l, Node r, long ts) {                               // internal
            this.key = key;
            this.left = new Ref(l, ts);
            this.right = new Ref(r, ts);
        }
        boolean isLeaf() { return left == null; }
    }

    private static final long QUIESCENT = Long.MAX_VALUE;
    private static final long TS_PENDING = 0L;
    static final int LOG_CAPACITY = 1024;

    private final AtomicLong clock = new AtomicLong(1);
    private final Node root; // internal(MAX); root.left = dummy leaf(MAX)

    private final ConcurrentHashMap<Long, AtomicLong> readerSlots =
            new ConcurrentHashMap<Long, AtomicLong>();
    private final ThreadLocal<AtomicLong> mySlot = new ThreadLocal<AtomicLong>() {
        @Override protected AtomicLong initialValue() {
            AtomicLong slot = new AtomicLong(QUIESCENT);
            readerSlots.put(Thread.currentThread().getId(), slot);
            return slot;
        }
    };
    private final ThreadLocal<ArrayList<Ref>> myLog = new ThreadLocal<ArrayList<Ref>>() {
        @Override protected ArrayList<Ref> initialValue() {
            return new ArrayList<Ref>(LOG_CAPACITY);
        }
    };

    public MvRluTree() {
        Node dummy = new Node(Integer.MAX_VALUE);
        root = new Node(Integer.MAX_VALUE, dummy, new Node(Integer.MAX_VALUE), 0L);
    }

    private static void checkKey(int key) {
        if (key == Integer.MIN_VALUE || key == Integer.MAX_VALUE)
            throw new IllegalArgumentException("sentinel key: " + key);
    }

    /** The child slot of internal n on the search path of key k. */
    private static Ref refFor(Node n, int k) { return k < n.key ? n.left : n.right; }

    private static Node latestChild(Ref r) { return r.head.child; }

    /** Versioned dereference: the slot's child as of timestamp ts. */
    private static Node childAt(Ref r, long ts) {
        Version v = r.head;
        while (v != null && v.commitTs > ts) v = v.older;
        if (v == null)
            throw new IllegalStateException(
                "version chain underflow (tree) ts=" + ts + " -- GC bug");
        return v.child;
    }

    // ---- search: (grandparent, parent, leaf) on the path of k ----
    // Thread-confined result holder (instance fields would race).
    private static final class Path { Node g, p, l; }
    private final ThreadLocal<Path> myPath = new ThreadLocal<Path>() {
        @Override protected Path initialValue() { return new Path(); }
    };

    private Path find(int k) {
        Path path = myPath.get();
        Node g = null, p = root;
        Node cur = latestChild(refFor(root, k));
        while (!cur.isLeaf()) {
            g = p; p = cur;
            cur = latestChild(refFor(cur, k));
        }
        path.g = g; path.p = p; path.l = cur;
        return path;
    }

    // ---- writers ----

    @Override
    public boolean insert(int key) {
        checkKey(key);
        while (true) {
            Path path = find(key);
            Node p = path.p, l = path.l;
            Ref slot = refFor(p, key);

            p.lock.lock();
            try {
                if (p.removed || latestChild(slot) != l) continue; // retry
                if (l.key == key) return false; // present (l can't be
                    // mid-removal: removing l requires p's lock)

                long ts = clock.getAndIncrement();
                // One commit-ts for the whole "write-set": the new leaf,
                // the new internal router, and p's new child version
                // become visible atomically to versioned readers.
                Node fresh = new Node(key);
                Node router = (key < l.key)
                        ? new Node(l.key, fresh, l, ts)
                        : new Node(key, l, fresh, ts);
                slot.head = new Version(router, ts, slot.head); // commit
                logWrite(slot);
                return true;
            } finally {
                p.lock.unlock();
            }
        }
    }

    @Override
    public boolean remove(int key) {
        checkKey(key);
        while (true) {
            Path path = find(key);
            Node g = path.g, p = path.p, l = path.l;
            if (l.key != key) return false; // covers the dummy leaf too
            if (g == null)
                // A real leaf always has a grandparent: the only leaf whose
                // parent is the root is the MAX dummy (see class comment).
                throw new IllegalStateException("real leaf directly under root");

            Ref gSlot = refFor(g, key);
            Ref pSlot = refFor(p, key);

            g.lock.lock();                       // ancestor first --
            try {                                // deadlock-free (no rotations)
                p.lock.lock();
                try {
                    if (g.removed || p.removed
                            || latestChild(gSlot) != p || latestChild(pSlot) != l)
                        continue; // retry
                    p.removed = true;            // LP of remove
                    l.removed = true;
                    Node sibling = latestChild(key < p.key ? p.right : p.left);
                    long ts = clock.getAndIncrement();
                    gSlot.head = new Version(sibling, ts, gSlot.head);
                    logWrite(gSlot);
                    return true;
                } finally {
                    p.lock.unlock();
                }
            } finally {
                g.lock.unlock();
            }
        }
    }

    // ---- fast-path current read ----

    @Override
    public boolean contains(int key) {
        checkKey(key);
        Node cur = latestChild(refFor(root, key));
        while (!cur.isLeaf()) cur = latestChild(refFor(cur, key));
        return cur.key == key && !cur.removed;
    }

    // ---- snapshot queries (iterative: no call-stack recursion) ----

    @Override
    public long sumRange(int lo, int hi) {
        AtomicLong slot = mySlot.get();
        slot.set(TS_PENDING);
        long ts = clock.get();
        slot.set(ts);
        try {
            long sum = 0;
            ArrayDeque<Node> stack = new ArrayDeque<Node>();
            stack.push(childAt(refFor(root, 0), ts)); // 0 < MAX -> left slot
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                if (n.isLeaf()) {
                    if (n.key >= lo && n.key <= hi && n.key != Integer.MAX_VALUE)
                        sum += n.key;
                } else {
                    // routing: left < n.key <= right
                    if (lo < n.key) stack.push(childAt(n.left, ts));
                    if (hi >= n.key) stack.push(childAt(n.right, ts));
                }
            }
            return sum;
        } finally {
            slot.set(QUIESCENT);
        }
    }

    @Override
    public int sizeSnapshot() {
        AtomicLong slot = mySlot.get();
        slot.set(TS_PENDING);
        long ts = clock.get();
        slot.set(ts);
        try {
            int count = 0;
            ArrayDeque<Node> stack = new ArrayDeque<Node>();
            stack.push(childAt(refFor(root, 0), ts));
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                if (n.isLeaf()) {
                    if (n.key != Integer.MAX_VALUE) count++;
                } else {
                    stack.push(childAt(n.left, ts));
                    stack.push(childAt(n.right, ts));
                }
            }
            return count;
        } finally {
            slot.set(QUIESCENT);
        }
    }

    // ---- cooperative GC (identical protocol to MvRluList) ----

    private void logWrite(Ref r) {
        ArrayList<Ref> log = myLog.get();
        log.add(r);
        if (log.size() >= LOG_CAPACITY) {
            gcPass(log);
            log.clear();
        }
    }

    private void gcPass(ArrayList<Ref> log) {
        long boundary = clock.get(); // clock BEFORE slots -- see MvRluList proof
        for (AtomicLong slot : readerSlots.values()) {
            long v = slot.get();
            if (v != QUIESCENT && v < boundary) boundary = v;
        }
        for (int i = 0; i < log.size(); i++) prune(log.get(i), boundary);
    }

    private static void prune(Ref r, long boundary) {
        Version v = r.head;
        while (v.commitTs > boundary) {
            Version older = v.older;
            if (older == null) return;
            v = older;
        }
        v.older = null;
    }

    // ---- quiescent structural check ----

    @Override
    public void validateStructure() {
        // Iterative walk carrying (node, minExclusive, maxInclusive) bounds.
        ArrayDeque<Object[]> stack = new ArrayDeque<Object[]>();
        stack.push(new Object[]{ latestChild(root.left), Long.MIN_VALUE, (long) Integer.MAX_VALUE });
        while (!stack.isEmpty()) {
            Object[] f = stack.pop();
            Node n = (Node) f[0];
            long min = (Long) f[1], max = (Long) f[2];
            if (n.key <= min || n.key > max)
                throw new IllegalStateException("routing violation at key=" + n.key
                        + " bounds=(" + min + "," + max + "]");
            if (!n.isLeaf()) {
                for (Ref r : new Ref[]{ n.left, n.right }) {
                    Version v = r.head;
                    while (v.older != null) {
                        if (v.older.commitTs >= v.commitTs)
                            throw new IllegalStateException("non-monotonic chain at key=" + n.key);
                        v = v.older;
                    }
                }
                // routing: left subtree keys < n.key  -> (min, n.key-1]
                //          right subtree keys >= n.key -> (n.key-1, max]
                stack.push(new Object[]{ latestChild(n.left), min, (long) n.key - 1 });
                stack.push(new Object[]{ latestChild(n.right), (long) n.key - 1, max });
            }
        }
    }
}