import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

public enum AIRequestOutcome: String, Codable, Sendable {
    case running, succeeded, failed, cancelled, interrupted
}

public struct AIRequestRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var startedAt: Date
    public var finishedAt: Date?
    public var kind: String
    public var model: String
    public var endpoint: String
    public var requestBody: String
    public var responseBody: String?
    public var httpStatus: Int?
    public var outcome: AIRequestOutcome
    public var errorMessage: String?
    public var requestRedacted: Bool
    public var responseRedacted: Bool
    public var responseTruncated: Bool

    public init(id: UUID = UUID(), startedAt: Date = Date(), finishedAt: Date? = nil,
                kind: String, model: String, endpoint: String, requestBody: String,
                responseBody: String? = nil, httpStatus: Int? = nil, outcome: AIRequestOutcome = .running,
                errorMessage: String? = nil, requestRedacted: Bool = false,
                responseRedacted: Bool = false, responseTruncated: Bool = false) {
        self.id = id; self.startedAt = startedAt; self.finishedAt = finishedAt
        self.kind = kind; self.model = model; self.endpoint = endpoint; self.requestBody = requestBody
        self.responseBody = responseBody; self.httpStatus = httpStatus; self.outcome = outcome
        self.errorMessage = errorMessage; self.requestRedacted = requestRedacted
        self.responseRedacted = responseRedacted; self.responseTruncated = responseTruncated
    }
}

public enum AITraceRedactor {
    private static let marker = "[REDACTED]"
    private static let fields = #"(?:authorization|proxy[-_]?authorization|cookie|set[-_]?cookie|api[-_]?key|access[-_]?token|refresh[-_]?token|id[-_]?token|client[-_]?secret|password|passwd|secret|token)"#
    // Captures preserve the prefix and optional closing quote; only the value is masked.
    private static let rules = [
        #"(?im)^(\s*(?:authorization|proxy-authorization|cookie|set-cookie)\s*:\s*)[^\r\n]+"#,
        #"(?i)((?<![\w-])["']?"# + fields + #"["']?\s*[:=]\s*")(?:\\.|[^"\\])*(")"#,
        #"(?i)((?<![\w-])["']?"# + fields + #"["']?\s*[:=]\s*')[^']*(')"#,
        #"(?i)((?<![\w-])["']?"# + fields + #"["']?\s*[:=]\s*)(?!["'\s\[{])[^\s,;&}\]]+"#,
        #"(?i)(\bBearer\s+)[A-Za-z0-9._~+\-/=]+"#,
        #"\bsk-[A-Za-z0-9_-]+"#
    ].map { try! NSRegularExpression(pattern: $0) }
    private static let truncatedRules = [
        #"(?i)((?<![\w-])["']?"# + fields + #"["']?\s*[:=]\s*")(?:\\.|[^"\\])*\\?\z"#,
        #"(?i)((?<![\w-])["']?"# + fields + #"["']?\s*[:=]\s*')[^']*\z"#
    ].map { try! NSRegularExpression(pattern: $0) }
    private static let quotedKey = try! NSRegularExpression(pattern: #"(?<!\\)("(?:\\.|[^"\\])*")\s*[:=]\s*"#)
    private static let quotedValue = try! NSRegularExpression(pattern: #""(?:\\.|[^"\\])*""#)
    private static let credentialName = try! NSRegularExpression(pattern: "(?i)^" + fields + "$")

    /// Redacts the original spelling, including credentials encoded across JSON string layers.
    /// Decoded views retain offsets into the original text, so safe whitespace, escapes and
    /// surrounding JSON remain unchanged. Parsing a complete document is not required.
    public static func redact(_ text: String, secrets: [String] = [], truncated: Bool = false) -> String {
        let secrets = Array(Set(secrets.filter { !$0.isEmpty }))
        let original = text as NSString
        var projection = Projection(units: Array(text.utf16), origins: (0..<original.length).map { NSRange(location: $0, length: 1) })
        var masks: [NSRange] = []
        for depth in 0...16 {
            for range in sensitiveRanges(projection, secrets: secrets, truncated: truncated) where range.length > 0 {
                let first = projection.origins[range.location]
                let last = projection.origins[NSMaxRange(range) - 1]
                masks.append(NSRange(location: first.location, length: NSMaxRange(last) - first.location))
            }
            guard let decoded = decodeEscapes(projection) else { break }
            // A bounded scan must fail closed rather than return uninspected encoded content.
            guard depth < 16 else { return marker }
            projection = decoded
        }
        var merged: [NSRange] = []
        for range in masks.sorted(by: { $0.location < $1.location }) {
            if let last = merged.last, range.location <= NSMaxRange(last) {
                merged[merged.count - 1].length = max(NSMaxRange(last), NSMaxRange(range)) - last.location
            } else { merged.append(range) }
        }
        guard !merged.isEmpty else { return text }
        let result = NSMutableString(string: text)
        for range in merged.reversed() { result.replaceCharacters(in: range, with: marker) }
        return result as String
    }

    private struct Projection {
        var units: [UInt16]
        var origins: [NSRange]
    }

    private static func sensitiveRanges(_ projection: Projection, secrets: [String], truncated: Bool) -> [NSRange] {
        let text = String(decoding: projection.units, as: UTF16.self)
        let source = text as NSString
        var ranges: [NSRange] = []
        for secret in secrets {
            var offset = 0
            while offset < source.length {
                let range = source.range(of: secret, range: NSRange(location: offset, length: source.length - offset))
                guard range.location != NSNotFound else { break }
                ranges.append(range)
                offset = NSMaxRange(range)
            }
        }
        for rule in rules + (truncated ? truncatedRules : []) {
            for match in rule.matches(in: text, range: NSRange(location: 0, length: source.length)) {
                let start = match.numberOfRanges > 1 ? NSMaxRange(match.range(at: 1)) : match.range.location
                let end = match.numberOfRanges > 2 ? match.range(at: 2).location : NSMaxRange(match.range)
                if end > start { ranges.append(NSRange(location: start, length: end - start)) }
            }
        }
        // Decode field names separately while retaining the value's original JSON
        // quoting. Otherwise a Unicode-escaped quote inside the value could look
        // like its closing delimiter after decoding an encoded field name.
        for match in quotedKey.matches(in: text, range: NSRange(location: 0, length: source.length)) {
            guard let key = try? JSONDecoder().decode(String.self, from: Data(source.substring(with: match.range(at: 1)).utf8)),
                  credentialName.firstMatch(in: key, range: NSRange(key.startIndex..., in: key)) != nil else { continue }
            let valueStart = NSMaxRange(match.range)
            guard valueStart < source.length, source.character(at: valueStart) == 34 else { continue }
            if let value = quotedValue.firstMatch(in: text, options: .anchored, range: NSRange(location: valueStart, length: source.length - valueStart)) {
                if value.range.length > 2 { ranges.append(NSRange(location: valueStart + 1, length: value.range.length - 2)) }
            } else if truncated, source.length > valueStart + 1 {
                ranges.append(NSRange(location: valueStart + 1, length: source.length - valueStart - 1))
            }
        }
        if truncated {
            // Ignore an incomplete final escape when checking known-secret prefixes, but
            // include it in the masked original range (e.g. "dummy-\\u00" at the cutoff).
            let cutoff = incompleteEscapeStart(projection.units) ?? projection.units.count
            var longest = 0
            for secret in secrets {
                let key = Array(secret.utf16)
                let limit = min(key.count, cutoff)
                guard limit > longest else { continue }
                for length in stride(from: limit, through: longest + 1, by: -1) {
                    if projection.units[(cutoff - length)..<cutoff].elementsEqual(key.prefix(length)) {
                        longest = length
                        break
                    }
                }
            }
            if longest > 0 { ranges.append(NSRange(location: cutoff - longest, length: projection.units.count - cutoff + longest)) }
        }
        return ranges
    }

    private static func incompleteEscapeStart(_ units: [UInt16]) -> Int? {
        if let last = units.last, (0xD800...0xDBFF).contains(last) { return units.count - 1 }
        guard let slash = units.lastIndex(of: 92) else { return nil }
        if slash == units.count - 1 { return slash }
        if units[slash + 1] == 117, units.count - slash <= 5,
           units.dropFirst(slash + 2).allSatisfy({ hexValue($0) != nil }) { return slash }
        return nil
    }

    private static func hexValue(_ unit: UInt16) -> UInt16? {
        switch unit {
        case 48...57: return unit - 48
        case 65...70: return unit - 55
        case 97...102: return unit - 87
        default: return nil
        }
    }

    private static func decodeEscapes(_ input: Projection) -> Projection? {
        var output = Projection(units: [], origins: [])
        output.units.reserveCapacity(input.units.count)
        output.origins.reserveCapacity(input.origins.count)
        var index = 0
        var changed = false
        while index < input.units.count {
            var replacement: UInt16?
            var consumed = 1
            if input.units[index] == 92, index + 1 < input.units.count {
                switch input.units[index + 1] {
                case 34, 47, 92: replacement = input.units[index + 1]; consumed = 2
                case 98: replacement = 8; consumed = 2
                case 102: replacement = 12; consumed = 2
                case 110: replacement = 10; consumed = 2
                case 114: replacement = 13; consumed = 2
                case 116: replacement = 9; consumed = 2
                case 117 where index + 5 < input.units.count:
                    let digits = input.units[(index + 2)...(index + 5)].compactMap(hexValue)
                    if digits.count == 4 {
                        replacement = digits.reduce(UInt16(0)) { ($0 << 4) | $1 }
                        consumed = 6
                    }
                default: break
                }
            }
            output.units.append(replacement ?? input.units[index])
            let first = input.origins[index]
            let last = input.origins[index + consumed - 1]
            output.origins.append(NSRange(location: first.location, length: NSMaxRange(last) - first.location))
            changed = changed || replacement != nil
            index += consumed
        }
        return changed ? output : nil
    }
}

public actor AIRequestHistoryArchive {
    private struct Envelope: Codable {
        var schemaVersion = 1
        var record: AIRequestRecord
    }
    private struct Version: Decodable { var schemaVersion: Int }
    private struct Entry {
        var record: AIRequestRecord
        var data: Data
        var needsWrite: Bool
    }
    private enum ArchiveError: LocalizedError {
        case invalidDirectory, corruptRecord, unsupportedVersion, oversizedRecord, invalidLimits
        var errorDescription: String? {
            switch self {
            case .invalidDirectory: return "AI 请求历史目录不可用。"
            case .corruptRecord: return "AI 请求历史中有损坏或无法识别的记录，未覆盖原文件。"
            case .unsupportedVersion: return "AI 请求历史来自不同版本，未覆盖原文件。"
            case .oversizedRecord: return "AI 请求历史记录超过本地存储大小限制。"
            case .invalidLimits: return "AI 请求历史的存储限制必须大于零。"
            }
        }
    }

    private let directory: URL
    private let maxRecords: Int
    private let maxBytes: Int
    // Prevent a response arriving after a user deletion from recreating that record.
    private var removedIDs = Set<UUID>()
    private let manager = FileManager.default

    public init(directory: URL, maxRecords: Int = 100, maxBytes: Int = 20 * 1024 * 1024) {
        self.directory = directory
        self.maxRecords = min(100, maxRecords)
        self.maxBytes = min(20 * 1024 * 1024, maxBytes)
    }

    public func load(recoverInterrupted: Bool = true) throws -> [AIRequestRecord] {
        var entries = try readEntries()
        if recoverInterrupted {
            for index in entries.indices where entries[index].record.outcome == .running {
                entries[index].record.outcome = .interrupted
                entries[index].record.finishedAt = Date()
                entries[index].data = try encode(entries[index].record)
                entries[index].needsWrite = true
            }
        }
        entries = try prune(entries)
        for entry in entries where entry.needsWrite { try atomicWrite(entry.data, id: entry.record.id) }
        return entries.map(\.record)
    }

    public func upsert(_ record: AIRequestRecord) throws {
        guard !removedIDs.contains(record.id) else { return }
        var entries = try readEntries()
        let sanitized = normalized(record)
        let data = try encode(sanitized)
        guard data.count <= maxBytes else { throw ArchiveError.oversizedRecord }
        // No mutation occurs until every existing record has been validated.
        try atomicWrite(data, id: sanitized.id)
        entries.removeAll { $0.record.id == sanitized.id }
        entries.append(Entry(record: sanitized, data: data, needsWrite: false))
        entries = try prune(entries)
        for entry in entries where entry.needsWrite { try atomicWrite(entry.data, id: entry.record.id) }
    }

    public func remove(_ id: UUID) throws {
        try prepareDirectory()
        let url = fileURL(id)
        if manager.fileExists(atPath: url.path) { try manager.removeItem(at: url) }
        removedIDs.insert(id)
    }

    public func removeAll() throws {
        try prepareDirectory()
        for url in try recordFiles() {
            try manager.removeItem(at: url)
            if let id = UUID(uuidString: url.deletingPathExtension().lastPathComponent) { removedIDs.insert(id) }
        }
    }

    private func fileURL(_ id: UUID) -> URL { directory.appendingPathComponent("\(id.uuidString).json") }

    private func prepareDirectory() throws {
        guard maxRecords > 0, maxBytes > 0 else { throw ArchiveError.invalidLimits }
        if !manager.fileExists(atPath: directory.path) {
            try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        let attributes = try manager.attributesOfItem(atPath: directory.path)
        guard attributes[.type] as? FileAttributeType == .typeDirectory else { throw ArchiveError.invalidDirectory }
        try manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        // Interrupted atomic writes are never backups and must not retain deleted content.
        for url in try manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            where url.lastPathComponent.hasPrefix(".request-write-") && url.pathExtension == "tmp" {
            try manager.removeItem(at: url)
        }
    }

    private func recordFiles() throws -> [URL] {
        try manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" }
    }

    private func readEntries() throws -> [Entry] {
        try prepareDirectory()
        let files = try recordFiles()
        guard files.count <= 1000 else { throw ArchiveError.oversizedRecord }
        var totalBytes = 0
        // Bound input before allocating/decoding; do not partially repair a corrupt archive.
        for url in files {
            let attributes = try manager.attributesOfItem(atPath: url.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  UUID(uuidString: url.deletingPathExtension().lastPathComponent) != nil else { throw ArchiveError.corruptRecord }
            let size = (attributes[.size] as? NSNumber)?.intValue ?? Int.max
            guard size <= 2 * 1024 * 1024 else { throw ArchiveError.oversizedRecord }
            totalBytes += size
            guard totalBytes <= 40 * 1024 * 1024 else { throw ArchiveError.oversizedRecord }
        }
        return try files.map { url in
            let data = try Data(contentsOf: url)
            let version: Version
            do { version = try JSONDecoder().decode(Version.self, from: data) }
            catch { throw ArchiveError.corruptRecord }
            guard version.schemaVersion == 1 else { throw ArchiveError.unsupportedVersion }
            let decoded: AIRequestRecord
            do { decoded = try JSONDecoder().decode(Envelope.self, from: data).record }
            catch { throw ArchiveError.corruptRecord }
            guard url.lastPathComponent == "\(decoded.id.uuidString).json",
                  decoded.startedAt.timeIntervalSince1970.isFinite,
                  decoded.finishedAt?.timeIntervalSince1970.isFinite != false else { throw ArchiveError.corruptRecord }
            try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            let record = normalized(decoded)
            let canonicalData = try encode(record)
            return Entry(record: record, data: canonicalData, needsWrite: canonicalData != data)
        }
    }

    private func encode(_ record: AIRequestRecord) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(Envelope(record: record))
    }

    private func prune(_ entries: [Entry]) throws -> [Entry] {
        let sorted = entries.sorted {
            $0.record.startedAt == $1.record.startedAt ? $0.record.id.uuidString > $1.record.id.uuidString : $0.record.startedAt > $1.record.startedAt
        }
        var kept = sorted
        var bytes = kept.reduce(0) { $0 + $1.data.count }
        while kept.count > maxRecords || bytes > maxBytes {
            let oldest = kept.removeLast()
            try manager.removeItem(at: fileURL(oldest.record.id))
            removedIDs.insert(oldest.record.id)
            bytes -= oldest.data.count
        }
        return kept
    }

    private func normalized(_ input: AIRequestRecord) -> AIRequestRecord {
        var record = input
        let request = AITraceRedactor.redact(input.requestBody)
        let response = input.responseBody.map { AITraceRedactor.redact($0, truncated: input.responseTruncated) }
        record.requestRedacted = input.requestRedacted || request != input.requestBody
        record.responseRedacted = input.responseRedacted || response != input.responseBody
        record.responseTruncated = input.responseTruncated || (response?.utf8.count ?? 0) > 128 * 1024
        record.requestBody = clipped(request, limit: 64 * 1024)
        record.responseBody = response.map { clipped($0, limit: 128 * 1024) }
        record.kind = clipped(AITraceRedactor.redact(input.kind), limit: 256)
        record.model = clipped(AITraceRedactor.redact(input.model), limit: 512)
        record.endpoint = clipped(AITraceRedactor.redact(input.endpoint), limit: 4096)
        record.errorMessage = input.errorMessage.map { clipped(AITraceRedactor.redact($0), limit: 4096) }
        return record
    }

    private func clipped(_ text: String, limit: Int) -> String {
        guard text.utf8.count > limit else { return text }
        let marker = "\n[TRUNCATED]"
        var prefix = Data(text.utf8.prefix(limit - marker.utf8.count))
        while String(data: prefix, encoding: .utf8) == nil { prefix.removeLast() }
        return String(decoding: prefix, as: UTF8.self) + marker
    }

    private func atomicWrite(_ data: Data, id: UUID) throws {
        let temporary = directory.appendingPathComponent(".request-write-\(UUID().uuidString).tmp")
        let descriptor = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        guard descriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer {
            close(descriptor)
            try? manager.removeItem(at: temporary)
        }
        try data.withUnsafeBytes { buffer in
            var offset = 0
            while offset < buffer.count {
                let written = write(descriptor, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
                if written < 0 && errno == EINTR { continue }
                guard written > 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
                offset += written
            }
        }
        guard fsync(descriptor) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        guard rename(temporary.path, fileURL(id).path) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }
}
