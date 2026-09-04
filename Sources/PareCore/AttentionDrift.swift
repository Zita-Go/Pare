import Foundation

public enum AttentionContextKind: String, Equatable {
    case application
    case browserPage
    case space
}

/// Privacy-safe observation used by the policy. `context` is a bundle ID in
/// base mode or a process-local hash in browser mode; never a title or URL.
public struct AttentionContextEvent: Equatable {
    public let timestamp: Date
    public let context: String
    public let kind: AttentionContextKind

    public init(timestamp: Date, context: String, kind: AttentionContextKind) {
        self.timestamp = timestamp
        self.context = context
        self.kind = kind
    }
}

public struct AttentionDriftDecision: Equatable {
    public let switchCount: Int
    public let uniqueContextCount: Int
    public let medianDwellSeconds: TimeInterval
    public let returnRatio: Double
    public let browserPageChanges: Int

    public init(switchCount: Int, uniqueContextCount: Int,
                medianDwellSeconds: TimeInterval, returnRatio: Double,
                browserPageChanges: Int) {
        self.switchCount = switchCount
        self.uniqueContextCount = uniqueContextCount
        self.medianDwellSeconds = medianDwellSeconds
        self.returnRatio = returnRatio
        self.browserPageChanges = browserPageChanges
    }
}

public enum AttentionDriftPolicy {
    /// Detects short-dwell loops rather than merely “many events”. App mode
    /// requires repeated returns among 2–4 contexts; browser mode can trigger on
    /// rapid page churn because page identity is already hashed and ephemeral.
    public static func evaluate(events: [AttentionContextEvent], now: Date,
                                windowMinutes: Int, switchThreshold: Int) -> AttentionDriftDecision? {
        let cutoff = now.addingTimeInterval(-Double(max(1, windowMinutes)) * 60)
        let recent = events.filter { $0.timestamp >= cutoff && $0.timestamp <= now }
            .sorted { $0.timestamp < $1.timestamp }

        var collapsed: [AttentionContextEvent] = []
        for event in recent where collapsed.last?.context != event.context {
            collapsed.append(event)
        }
        guard collapsed.count >= max(2, switchThreshold + 1) else { return nil }

        let gaps = zip(collapsed, collapsed.dropFirst()).map {
            max(0, $1.timestamp.timeIntervalSince($0.timestamp))
        }.sorted()
        let median: TimeInterval
        if gaps.isEmpty { median = .infinity }
        else if gaps.count % 2 == 1 { median = gaps[gaps.count / 2] }
        else { median = (gaps[gaps.count / 2 - 1] + gaps[gaps.count / 2]) / 2 }
        guard median <= 25 else { return nil }

        var returns = 0
        if collapsed.count >= 3 {
            for i in 2..<collapsed.count {
                let lower = max(0, i - 3)
                if collapsed[lower..<(i - 1)].contains(where: { $0.context == collapsed[i].context }) {
                    returns += 1
                }
            }
        }
        let returnRatio = Double(returns) / Double(max(1, collapsed.count - 2))
        let unique = Set(collapsed.map(\.context)).count
        let browserChanges = collapsed.filter { $0.kind == .browserPage }.count
        let appLoop = (2...4).contains(unique) && returnRatio >= 0.45
        let browserChurn = browserChanges >= switchThreshold
        guard appLoop || browserChurn else { return nil }

        return AttentionDriftDecision(switchCount: collapsed.count - 1,
                                      uniqueContextCount: unique,
                                      medianDwellSeconds: median,
                                      returnRatio: returnRatio,
                                      browserPageChanges: browserChanges)
    }
}
