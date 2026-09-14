import XCTest
@testable import MeshLinkCore

final class SeenMessageManagerTests: XCTestCase {

    func testMarkSeenAndHasSeen() {
        let manager = SeenMessageManager(maxCapacity: 10)

        XCTAssertFalse(manager.hasSeen("MSG-1"))
        XCTAssertTrue(manager.markSeen("MSG-1"))
        XCTAssertTrue(manager.hasSeen("MSG-1"))
        XCTAssertFalse(manager.markSeen("MSG-1"), "Second mark should return false")
        XCTAssertEqual(manager.size(), 1)
    }

    func testLruCapacityEviction() {
        let manager = SeenMessageManager(maxCapacity: 3)

        manager.markSeen("MSG-1")
        manager.markSeen("MSG-2")
        manager.markSeen("MSG-3")

        XCTAssertEqual(manager.size(), 3)
        XCTAssertTrue(manager.hasSeen("MSG-1"))

        // Add 4th -> MSG-1 was least recently used before we accessed it? Note: hasSeen does not reorder, markSeen does.
        manager.markSeen("MSG-4")
        XCTAssertEqual(manager.size(), 3)
        XCTAssertFalse(manager.hasSeen("MSG-1"), "Oldest item should be evicted")
        XCTAssertTrue(manager.hasSeen("MSG-2"))
        XCTAssertTrue(manager.hasSeen("MSG-3"))
        XCTAssertTrue(manager.hasSeen("MSG-4"))
    }

    func testClear() {
        let manager = SeenMessageManager(maxCapacity: 10)
        manager.markSeen("MSG-A")
        manager.markSeen("MSG-B")
        XCTAssertEqual(manager.size(), 2)

        manager.clear()
        XCTAssertEqual(manager.size(), 0)
        XCTAssertFalse(manager.hasSeen("MSG-A"))
    }
}
