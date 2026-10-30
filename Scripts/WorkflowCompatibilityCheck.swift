import Foundation
import PareCore

/// Offline contract check of synthetic Kotlin-generated workflow payloads.
/// Does not access the default Pare database, keychain, or any network service.
@main struct WorkflowCompatibilityCheck {
    static func main() throws {
        guard CommandLine.arguments.count >= 2 else { fatalError("Pass explicit fixture paths") }
        for path in CommandLine.arguments.dropFirst() {
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            let payloads = try SyncWire.decoder().decode([String: SyncJSON].self, from: data)
            var counts: [String: Int] = [:]
            for (key, payload) in payloads {
                let kind = key.split(separator: ":", maxSplits: 1).first.map(String.init) ?? ""
                switch kind {
                case "idea":
                    let idea = try SyncPayload.content(ThoughtItem.self, payload)
                    guard !idea.text.isEmpty else { fatalError("Empty idea") }
                    counts["ideas", default: 0] += 1
                    counts["entries", default: 0] += idea.entries.count
                    counts["links", default: 0] += idea.linkedTaskIDs.count
                case "task":
                    let (task, _) = try SyncPayload.decodeTask(payload)
                    guard !task.text.isEmpty else { fatalError("Empty task") }
                    counts["tasks", default: 0] += 1
                    counts["blockers", default: 0] += task.blockerEntries?.count ?? 0
                    counts["focusSessions", default: 0] += task.progressSessions?.count ?? 0
                    counts["parallelSessions", default: 0] += task.parallelRunSessions?.count ?? 0
                    if task.remindAt != nil { counts["reminders", default: 0] += 1 }
                case "day_plan":
                    let plan = try SyncPayload.content(SyncDayPlan.self, payload)
                    try plan.validate()
                    counts["dayPlans", default: 0] += 1
                    counts["allocations", default: 0] += plan.taskPlans.filter { $0.allocation != nil }.count
                default: fatalError("Unsupported fixture entity")
                }
            }
            print("PASS Mac decode: \(URL(fileURLWithPath:path).lastPathComponent) \(counts)")
        }
    }
}
