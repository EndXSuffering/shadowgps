package dev.shadowgps.core.routing

/**
 * Minimal binary min-heap of `(int id, double key)` pairs.
 *
 * There is no decrease-key operation: improving a node's cost pushes a second entry and
 * the stale one is skipped when it pops, which is the standard trade for Dijkstra/A* on
 * road networks and avoids maintaining an index-into-heap table.
 */
internal class IntDoubleHeap(initialCapacity: Int = 1024) {

    private var ids = IntArray(initialCapacity.coerceAtLeast(1))
    private var keys = DoubleArray(initialCapacity.coerceAtLeast(1))

    var size: Int = 0
        private set

    /** Key of the most recent [pop]. */
    var lastKey: Double = 0.0
        private set

    fun isEmpty(): Boolean = size == 0

    fun peekKey(): Double = keys[0]

    fun clear() {
        size = 0
    }

    fun push(id: Int, key: Double) {
        if (size == ids.size) grow()
        ids[size] = id
        keys[size] = key
        siftUp(size)
        size++
    }

    /** Removes and returns the lowest-key id; the key itself lands in [lastKey]. */
    fun pop(): Int {
        check(size > 0) { "heap is empty" }
        val topId = ids[0]
        lastKey = keys[0]
        size--
        if (size > 0) {
            ids[0] = ids[size]
            keys[0] = keys[size]
            siftDown(0)
        }
        return topId
    }

    private fun grow() {
        val capacity = ids.size * 2
        ids = ids.copyOf(capacity)
        keys = keys.copyOf(capacity)
    }

    private fun siftUp(start: Int) {
        var child = start
        val id = ids[child]
        val key = keys[child]
        while (child > 0) {
            val parent = (child - 1) / 2
            if (keys[parent] <= key) break
            ids[child] = ids[parent]
            keys[child] = keys[parent]
            child = parent
        }
        ids[child] = id
        keys[child] = key
    }

    private fun siftDown(start: Int) {
        var parent = start
        val id = ids[parent]
        val key = keys[parent]
        while (true) {
            var child = parent * 2 + 1
            if (child >= size) break
            if (child + 1 < size && keys[child + 1] < keys[child]) child++
            if (keys[child] >= key) break
            ids[parent] = ids[child]
            keys[parent] = keys[child]
            parent = child
        }
        ids[parent] = id
        keys[parent] = key
    }
}
