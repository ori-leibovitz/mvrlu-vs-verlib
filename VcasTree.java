import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

/**
 * vCAS/Verlib-simplified EXTERNAL binary search tree.
 *
 * Same skeleton as MvRluTree (external BST, single-pointer updates,
 * ancestor-first locking, iterative snapshots -- see that class's header
 * for the structural arguments) and the same vCAS timestamp semantics as
 * VcasList: versions are INSTALLED with ts=TBD and stamped AFTERWARDS
 * with the current camera value, everyone who meets a TBD head HELPS
 * stamp it first (initTS), the write's linearization point is the
 * stamping, and the camera is advanced ONLY by takeSnapshot -- writers
 * merely read it. GC is the shared cooperative scheme (reader slots,
 * camera-before-slots boundary, prune per chain).
 */
public final class VcasTree implements VersionedSet, Validatable {

    static final long TBD = -1L;

    static final class VNode {
        final Node child;
        volatile long ts;       // TBD until stamped
        volatile VNode older;   // volatile so GC can cut the chain

        VNode(Node child, long ts, VNode older) {
            this.child = child;
            this.ts = ts;
            this.older = older;
        }
    }

    private static final AtomicLongFieldUpdater<VNode> TS =
            AtomicLongFieldUpdater.newUpdater(VNode.class, "ts");

    /** A versioned child pointer (one per child slot of an internal node). */
    static final class Ref {
        volatile VNode head;
        Ref(Node child, long ts) { head = new VNode(child, ts, null); }
    }

    static final class Node {
        final int key;
        final Ref left, right;      // null iff leaf
        volatile boolean removed;
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
    private static final long SLOT_PENDING = 0L;
    static final int LOG_CAPACITY = 1024;

    private final AtomicLong camera = new AtomicLong(1);
    private final Node root; // internal(MAX); root.left starts as dummy leaf(MAX)

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

    public VcasTree() {
        Node dummy = new Node(Integer.MAX_VALUE);
        root = new Node(Integer.MAX_VALUE, dummy, new Node(Integer.MAX_VALUE), 0L);
    }

    private static void checkKey(int key) {
        if (key == Integer.MIN_VALUE || key == Integer.MAX_VALUE)
            throw new IllegalArgumentException("sentinel key: " + key);
    }

    // ---- the vCAS core (identical to VcasList) ----

    private void initTS(VNode v) {
        if (v.ts == TBD) {
            long cur = camera.get();
            TS.compareAndSet(v, TBD, cur); // helping; losers no-op
        }
    }

    private long takeSnapshot() {
        long ts = camera.get();
        camera.compareAndSet(ts, ts + 1); // one attempt; failure is fine
        return ts;
    }

    private static Ref refFor(Node n, int k) { return k < n.key ? n.left : n.right; }

    private Node latestChild(Ref r) {
        VNode h = r.head;
        initTS(h);
        return h.child;
    }

    private Node childAt(Ref r, long ts) {
        VNode v = r.head;
        initTS(v); // only the head may be TBD
        while (v != null && v.ts > ts) v = v.older;
        if (v == null)
            throw new IllegalStateException(
                "version chain underflow (tree) ts=" + ts + " -- GC bug");
        return v.child;
    }

    /** Install a new child version under the owner's lock: stamp old head,
     *  install TBD, help-stamp (the write's LP). */
    private void installChild(Ref slot, Node newChild) {
        VNode h = slot.head;
        initTS(h);
        VNode fresh = new VNode(newChild, TBD, h);
        slot.head = fresh;
        initTS(fresh);
        logWrite(slot);
    }

    // ---- search ----
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
                if (l.key == key) return false;

                // New nodes' initial versions are stamped NOW (invisible
                // yet), so their ts <= the ts of p's new version: any
                // snapshot that sees the router can read its children.
                long birth = camera.get();
                Node fresh = new Node(key);
                Node router = (key < l.key)
                        ? new Node(l.key, fresh, l, birth)
                        : new Node(key, l, fresh, birth);
                installChild(slot, router);
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
            if (l.key != key) return false;
            if (g == null)
                throw new IllegalStateException("real leaf directly under root");

            Ref gSlot = refFor(g, key);
            Ref pSlot = refFor(p, key);

            g.lock.lock();
            try {
                p.lock.lock();
                try {
                    if (g.removed || p.removed
                            || latestChild(gSlot) != p || latestChild(pSlot) != l)
                        continue; // retry
                    p.removed = true;
                    l.removed = true;
                    Node sibling = latestChild(key < p.key ? p.right : p.left);
                    installChild(gSlot, sibling);
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

    // ---- snapshot queries (iterative) ----

    @Override
    public long sumRange(int lo, int hi) {
        AtomicLong slot = mySlot.get();
        slot.set(SLOT_PENDING);
        long ts = takeSnapshot();   // camera advanced by SNAPSHOTS
        slot.set(ts);
        try {
            long sum = 0;
            ArrayDeque<Node> stack = new ArrayDeque<Node>();
            stack.push(childAt(refFor(root, 0), ts));
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                if (n.isLeaf()) {
                    if (n.key >= lo && n.key <= hi && n.key != Integer.MAX_VALUE)
                        sum += n.key;
                } else {
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
        slot.set(SLOT_PENDING);
        long ts = takeSnapshot();
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

    // ---- cooperative GC ----

    private void logWrite(Ref r) {
        ArrayList<Ref> log = myLog.get();
        log.add(r);
        if (log.size() >= LOG_CAPACITY) {
            gcPass(log);
            log.clear();
        }
    }

    private void gcPass(ArrayList<Ref> log) {
        long boundary = camera.get(); // camera BEFORE slots
        for (AtomicLong slot : readerSlots.values()) {
            long v = slot.get();
            if (v != QUIESCENT && v < boundary) boundary = v;
        }
        for (int i = 0; i < log.size(); i++) prune(log.get(i), boundary);
    }

    private void prune(Ref r, long boundary) {
        VNode v = r.head;
        initTS(v);
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
                    VNode v = r.head;
                    initTS(v);
                    while (v.older != null) {
                        if (v.older.ts == TBD)
                            throw new IllegalStateException("non-head TBD at key=" + n.key);
                        if (v.older.ts > v.ts)
                            throw new IllegalStateException("non-monotonic chain at key=" + n.key);
                        v = v.older;
                    }
                }
                stack.push(new Object[]{ latestChild(n.left), min, (long) n.key - 1 });
                stack.push(new Object[]{ latestChild(n.right), (long) n.key - 1, max });
            }
        }
    }
}