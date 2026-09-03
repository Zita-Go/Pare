import Foundation

/// Result of Pare's intentionally small, local reminder parser. This is not an
/// NLP service: it recognises a few high-value Chinese phrases, stays offline,
/// and leaves unrecognised text untouched.
public struct ParsedCapture: Equatable {
    public let text: String
    public let remindAt: Date?

    public init(text: String, remindAt: Date?) {
        self.text = text
        self.remindAt = remindAt
    }
}

public enum CaptureParser {
    public static func parse(_ source: String, now: Date = Date(),
                             calendar: Calendar = .current) -> ParsedCapture {
        var text = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.contains("提醒") else { return ParsedCapture(text: text, remindAt: nil) }

        if let relative = firstMatch(#"(\d+)\s*(分钟|小时)后"#, in: text),
           let amount = Int(relative.groups[0]) {
            let seconds = Double(amount) * (relative.groups[1] == "小时" ? 3600 : 60)
            text = removing(relative.full, from: text)
            text = cleanReminderWords(text)
            return ParsedCapture(text: text.isEmpty ? source : text,
                                 remindAt: now.addingTimeInterval(seconds))
        }

        // “明天下午3点提醒我写报告” / “今天 18:30 提醒喝水”. Requiring a
        // day or period word keeps ordinary task numbers from being mistaken.
        let pattern = #"(今天|明天)?\s*(上午|中午|下午|晚上)?\s*(\d{1,2})(?:[:：点时](\d{1,2})?)"#
        if let clock = firstMatch(pattern, in: text),
           (clock.groups[0].isEmpty == false || clock.groups[1].isEmpty == false),
           let rawHour = Int(clock.groups[2]) {
            let minute = Int(clock.groups[3]) ?? 0
            var hour = rawHour
            let period = clock.groups[1]
            if (period == "下午" || period == "晚上") && hour < 12 { hour += 12 }
            if period == "中午" && hour < 11 { hour += 12 }
            if period == "上午" && hour == 12 { hour = 0 }
            let offset = clock.groups[0] == "明天" ? 1 : 0
            let base = calendar.date(byAdding: .day, value: offset, to: now) ?? now
            if let date = calendar.date(bySettingHour: min(23, hour), minute: min(59, minute),
                                        second: 0, of: base) {
                let adjusted = (offset == 0 && date <= now)
                    ? (calendar.date(byAdding: .day, value: 1, to: date) ?? date)
                    : date
                text = removing(clock.full, from: text)
                text = cleanReminderWords(text)
                return ParsedCapture(text: text.isEmpty ? source : text, remindAt: adjusted)
            }
        }

        return ParsedCapture(text: text, remindAt: nil)
    }

    private struct Match {
        let full: String
        let groups: [String]
    }

    private static func firstMatch(_ pattern: String, in text: String) -> Match? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let m = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let fullRange = Range(m.range(at: 0), in: text) else { return nil }
        let groups = (1..<m.numberOfRanges).map { index -> String in
            let range = m.range(at: index)
            guard range.location != NSNotFound, let r = Range(range, in: text) else { return "" }
            return String(text[r])
        }
        return Match(full: String(text[fullRange]), groups: groups)
    }

    private static func removing(_ fragment: String, from text: String) -> String {
        text.replacingOccurrences(of: fragment, with: " ")
    }

    private static func cleanReminderWords(_ text: String) -> String {
        text.replacingOccurrences(of: "提醒我", with: " ")
            .replacingOccurrences(of: "提醒", with: " ")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
            .trimmingCharacters(in: CharacterSet(charactersIn: "，,。 "))
    }
}
