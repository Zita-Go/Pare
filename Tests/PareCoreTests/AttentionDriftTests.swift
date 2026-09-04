import XCTest
@testable import PareCore

final class AttentionDriftTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func events(_ contexts: [String], kind: AttentionContextKind = .application,
                        gap: TimeInterval = 12) -> [AttentionContextEvent] {
        let start = now.addingTimeInterval(-Double(contexts.count - 1) * gap)
        return contexts.enumerated().map {
            AttentionContextEvent(timestamp: start.addingTimeInterval(Double($0.offset) * gap),
                                  context: $0.element, kind: kind)
        }
    }

    func testDetectsRapidAppSwitchLoop() {
        let loop = events(["A", "B", "A", "B", "A", "B", "A", "B", "A", "B"])
        let decision = AttentionDriftPolicy.evaluate(events: loop, now: now,
                                                     windowMinutes: 4, switchThreshold: 8)
        XCTAssertNotNil(decision)
        XCTAssertEqual(decision?.switchCount, 9)
        XCTAssertEqual(decision?.uniqueContextCount, 2)
        XCTAssertGreaterThan(decision?.returnRatio ?? 0, 0.8)
    }

    func testDetectsBrowserPageChurnWithoutStoringTitles() {
        let pages = events((0..<10).map { "hash-\($0)" }, kind: .browserPage)
        let decision = AttentionDriftPolicy.evaluate(events: pages, now: now,
                                                     windowMinutes: 4, switchThreshold: 8)
        XCTAssertNotNil(decision)
        XCTAssertEqual(decision?.browserPageChanges, 10)
    }

    func testRejectsSlowOrBroadNormalResearch() {
        let slow = events(["A", "B", "A", "B", "A", "B", "A", "B", "A", "B"], gap: 40)
        XCTAssertNil(AttentionDriftPolicy.evaluate(events: slow, now: now,
                                                   windowMinutes: 10, switchThreshold: 8))

        let broad = events(["A", "B", "C", "D", "E", "F", "G", "H", "I", "J"])
        XCTAssertNil(AttentionDriftPolicy.evaluate(events: broad, now: now,
                                                   windowMinutes: 4, switchThreshold: 8))
    }

    func testAttentionSettingsPersistAndMigrationDefaultsOff() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-attention-\(UUID().uuidString).json")
        let store = Store(fileURL: url)
        XCTAssertFalse(store.settings.attentionDriftEnabled)
        var settings = store.settings
        settings.attentionDriftEnabled = true
        settings.attentionDriftBrowserEnabled = true
        settings.attentionDriftSwitchThreshold = 11
        store.updateSettings(settings)

        let loaded = Store(fileURL: url)
        XCTAssertTrue(loaded.settings.attentionDriftEnabled)
        XCTAssertTrue(loaded.settings.attentionDriftBrowserEnabled)
        XCTAssertEqual(loaded.settings.attentionDriftSwitchThreshold, 11)
    }
}
