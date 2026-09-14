import Foundation

/**
 * Thread-safe LRU-backed cache of processed message IDs.
 *
 * Prevents message looping and duplicate delivery in ad-hoc multi-hop meshes.
 * Stores up to `maxCapacity` message IDs with automatic eviction of the oldest entries.
 */
public final class SeenMessageManager: @unchecked Sendable {
    private let maxCapacity: Int
    private let lock = NSLock()
    private var order: [String] = []
    private var set: Set<String> = []

    public init(maxCapacity: Int = 500) {
        self.maxCapacity = maxCapacity
    }

    /**
     * Checks whether the message ID has already been recorded in the cache.
     *
     * @return true if the message was already seen, false otherwise.
     */
    public func hasSeen(_ messageId: String) -> Bool {
        let trimmed = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }

        lock.lock()
        defer { lock.unlock() }
        return set.contains(trimmed)
    }

    /**
     * Records a message ID into the seen cache.
     *
     * @return true if it was newly added, false if it was already present.
     */
    @discardableResult
    public func markSeen(_ messageId: String) -> Bool {
        let trimmed = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }

        lock.lock()
        defer { lock.unlock() }

        if set.contains(trimmed) {
            // Move to end (most recently used)
            if let index = order.firstIndex(of: trimmed) {
                order.remove(at: index)
                order.append(trimmed)
            }
            return false
        }

        // Evict oldest if capacity reached
        if set.count >= maxCapacity, !order.isEmpty {
            let oldest = order.removeFirst()
            set.remove(oldest)
        }

        order.append(trimmed)
        set.insert(trimmed)
        return true
    }

    /**
     * Returns current count of tracked message IDs.
     */
    public func size() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return set.count
    }

    /**
     * Clears all cached message IDs (e.g. for testing or reset).
     */
    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        order.removeAll()
        set.removeAll()
    }
}
