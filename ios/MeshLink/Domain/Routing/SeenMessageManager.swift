import Foundation

public final class SeenMessageManager: @unchecked Sendable {
    private let maxCapacity: Int
    private let lock = NSLock()
    private var order: [String] = []
    private var set: Set<String> = []

    public init(maxCapacity: Int = 500) {
        self.maxCapacity = maxCapacity
    }

    public func hasSeen(_ messageId: String) -> Bool {
        let trimmed = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }

        lock.lock()
        defer { lock.unlock() }
        return set.contains(trimmed)
    }

    @discardableResult
    public func markSeen(_ messageId: String) -> Bool {
        let trimmed = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }

        lock.lock()
        defer { lock.unlock() }

        if set.contains(trimmed) {
            if let index = order.firstIndex(of: trimmed) {
                order.remove(at: index)
                order.append(trimmed)
            }
            return false
        }

        if set.count >= maxCapacity, !order.isEmpty {
            let oldest = order.removeFirst()
            set.remove(oldest)
        }

        order.append(trimmed)
        set.insert(trimmed)
        return true
    }

    public func size() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return set.count
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        order.removeAll()
        set.removeAll()
    }
}
