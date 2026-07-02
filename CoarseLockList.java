import java.util.concurrent.locks.ReentrantLock;

/**
 * Baseline: a sorted singly-linked list protected by ONE global lock.
 *
 * Deliberately boring. It plays two roles in the project:
 *   1. ORACLE — trivially correct, so the stress test is calibrated
 *      against it before being trusted on the versioned implementations.
 *   2. ANCHOR — the benchmark baseline every versioning approach must
 *      beat (or fail to beat, which is also a result).
 *
 * Snapshot queries are trivially atomic here: they hold the lock for
 * the whole scan. That is exactly the cost the versioned designs try
 * to avoid.
 */
public final class CoarseLockList implements VersionedSet, Validatable {

    private static final class Node {
        final int key;
        Node next;
        Node(int key, Node next) { this.key = key; this.next = next; }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Node head; // sentinel MIN_VALUE -> ... -> sentinel MAX_VALUE

    public CoarseLockList() {
        Node tail = new Node(Integer.MAX_VALUE, null);
        head = new Node(Integer.MIN_VALUE, tail);
    }

    private static void checkKey(int key) {
        if (key == Integer.MIN_VALUE || key == Integer.MAX_VALUE)
            throw new IllegalArgumentException("sentinel key: " + key);
    }

    @Override
    public boolean insert(int key) {
        checkKey(key);
        lock.lock();
        try {
            Node pred = head;
            while (pred.next.key < key) pred = pred.next;
            if (pred.next.key == key) return false;
            pred.next = new Node(key, pred.next);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(int key) {
        checkKey(key);
        lock.lock();
        try {
            Node pred = head;
            while (pred.next.key < key) pred = pred.next;
            if (pred.next.key != key) return false;
            pred.next = pred.next.next;
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean contains(int key) {
        checkKey(key);
        lock.lock();
        try {
            Node cur = head.next;
            while (cur.key < key) cur = cur.next;
            return cur.key == key;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long sumRange(int lo, int hi) {
        lock.lock();
        try {
            long sum = 0;
            Node cur = head.next;
            while (cur.key < lo) cur = cur.next;
            while (cur.key <= hi && cur.key != Integer.MAX_VALUE) {
                sum += cur.key;
                cur = cur.next;
            }
            return sum;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int sizeSnapshot() {
        lock.lock();
        try {
            int n = 0;
            for (Node cur = head.next; cur.key != Integer.MAX_VALUE; cur = cur.next) n++;
            return n;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void validateStructure() {
        // Quiescent-only check: strictly increasing keys, intact sentinels.
        if (head.key != Integer.MIN_VALUE)
            throw new IllegalStateException("head sentinel corrupted");
        Node cur = head;
        while (cur.next != null) {
            if (cur.next.key <= cur.key)
                throw new IllegalStateException(
                    "order violation: " + cur.key + " -> " + cur.next.key);
            cur = cur.next;
        }
        if (cur.key != Integer.MAX_VALUE)
            throw new IllegalStateException("tail sentinel missing; last key=" + cur.key);
    }
}