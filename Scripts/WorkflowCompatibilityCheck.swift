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
                    guard !idea.text.isEmpty, key == "idea:\(idea.id.uuidString.lowercased())" else { fatalError("Invalid idea identity") }
                    counts["ideas", default: 0] += 1
                    counts["entries", default: 0] += idea.entries.count
                    counts["links", default: 0] += idea.linkedTaskIDs.count
                    if idea.reviewAt != nil { counts["ideaReviews", default: 0] += 1 }
                case "task":
                    let (task, archived) = try SyncPayload.decodeTask(payload)
                    guard !task.text.isEmpty, key == "task:\(task.id.uuidString.lowercased())" else { fatalError("Invalid task identity") }
                    counts["tasks", default: 0] += 1
                    counts["blockers", default: 0] += task.blockerEntries?.count ?? 0
                    counts["focusSessions", default: 0] += task.progressSessions?.count ?? 0
                    counts["parallelSessions", default: 0] += task.parallelRunSessions?.count ?? 0
                    if task.remindAt != nil { counts["reminders", default: 0] += 1 }
                    if task.dueAt != nil { counts["deadlines", default: 0] += 1 }
                    if task.actualMinutes != nil { counts["actualMinutesRecorded", default: 0] += 1 }
                    if archived { counts["archivedTasks", default: 0] += 1 }
                    if let implementation = task.implementationPlan {
                        guard !implementation.action.isEmpty else { fatalError("Empty implementation action") }
                        counts["implementationPlans", default: 0] += 1
                        if implementation.kind == .time {
                            guard implementation.hour.map({ (0...23).contains($0) }) ?? true,
                                  implementation.minute.map({ (0...59).contains($0) }) ?? true else { fatalError("Invalid implementation clock") }
                            counts["timeCues", default: 0] += 1
                        }
                    }
                case "goal":
                    let goal = try SyncPayload.content(LongTermGoal.self, payload)
                    guard !goal.title.isEmpty, key == "goal:\(goal.id.uuidString.lowercased())" else { fatalError("Invalid goal identity") }
                    counts["goals", default: 0] += 1
                    counts["goalTaskLinks", default: 0] += goal.taskIDs.count
                    if goal.targetDate != nil { counts["goalTargets", default: 0] += 1 }
                case "profile":
                    let profile = try SyncPayload.content(SyncProfile.self, payload)
                    guard key == "profile:profile", TimeZone(identifier: profile.planningTimeZone) != nil else { fatalError("Invalid profile identity or zone") }
                    counts["profiles", default: 0] += 1
                    counts["confirmedPreferences", default: 0] += profile.confirmedPreferences.count
                    counts["scopedPreferences", default: 0] += profile.confirmedPreferences.filter { $0.ideaID != nil }.count
                    counts["expiringPreferences", default: 0] += profile.confirmedPreferences.filter { $0.expiresAt != nil }.count
                case "inbox":
                    let inbox = try SyncPayload.content(InboxItem.self, payload)
                    guard !inbox.text.isEmpty, key == "inbox:\(inbox.id.uuidString.lowercased())" else { fatalError("Invalid inbox identity") }
                    counts["inbox", default: 0] += 1
                case "day_plan":
                    let plan = try SyncPayload.content(SyncDayPlan.self, payload)
                    try plan.validate()
                    guard key == "day_plan:\(plan.dayKey)@\(plan.timeZone)" else { fatalError("Invalid day plan identity") }
                    counts["dayPlans", default: 0] += 1
                    counts["allocations", default: 0] += plan.taskPlans.filter { $0.allocation != nil }.count
                    counts["scheduledBlocks", default: 0] += plan.taskPlans.filter { $0.scheduledAt != nil }.count
                default: fatalError("Unsupported fixture entity")
                }
            }
            if URL(fileURLWithPath: path).lastPathComponent == "pare-09-workflow-wire.json" {
                let expected = ["tasks": 2, "ideas": 1, "entries": 1, "links": 1, "ideaReviews": 1,
                                "goals": 1, "goalTaskLinks": 2, "goalTargets": 1, "profiles": 1,
                                "confirmedPreferences": 2, "scopedPreferences": 1, "expiringPreferences": 1,
                                "inbox": 1, "implementationPlans": 1, "timeCues": 1, "deadlines": 1,
                                "actualMinutesRecorded": 1, "reminders": 1, "dayPlans": 2, "allocations": 2, "scheduledBlocks": 1]
                for (name, count) in expected { guard counts[name] == count else { fatalError("0.9 fixture lost \(name): \(counts[name] ?? 0), expected \(count)") } }
                let taskID = UUID(uuidString: "95000000-0000-0000-0000-000000000001")!
                let source = try SyncPayload.content(SyncDayPlan.self, payloads["day_plan:2026-10-31@UTC"]!)
                let target = try SyncPayload.content(SyncDayPlan.self, payloads["day_plan:2026-11-01@UTC"]!)
                let moved = target.taskPlans.first { $0.taskID == taskID }
                guard !source.orderedTaskIDs.contains(taskID), target.orderedTaskIDs.contains(taskID),
                      moved?.scheduledAt == ISO8601DateFormatter().date(from: "2026-11-01T10:15:00Z"),
                      moved?.scheduledMinutes == 45, moved?.scheduleReminderLeadMinutes == 10,
                      moved?.allocation?.kind == "personal", moved?.allocation?.plannedMinutes == 45 else { fatalError("Cross-date move did not survive Mac decoding") }
                let task = try SyncPayload.decodeTask(payloads["task:\(taskID.uuidString.lowercased())"]!).0
                guard task.implementationPlan?.hour == 9, task.implementationPlan?.minute == 15,
                      task.dueAt != nil, task.remindAt != nil, task.dueAt != task.remindAt, task.actualMinutes == 8 else { fatalError("Task metadata did not survive Mac decoding") }
                let profile = try SyncPayload.content(SyncProfile.self, payloads["profile:profile"]!)
                guard profile.preferences.planningMinutes == 45, profile.preferences.startingMinutes == 5 else { fatalError("Planning preferences did not survive Mac decoding") }
            }
            print("PASS Mac decode: \(URL(fileURLWithPath:path).lastPathComponent) \(counts)")
        }
    }
}
