import XCTest
@testable import PareCore

final class AIRequestHistoryTests: XCTestCase {
    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pare-request-history-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private func record(_ time: TimeInterval = 100, outcome: AIRequestOutcome = .succeeded) -> AIRequestRecord {
        AIRequestRecord(startedAt: Date(timeIntervalSince1970: time), kind: "assistant", model: "test-model",
                        endpoint: "http://localhost/v1/chat/completions", requestBody: "{\"messages\":[]}",
                        responseBody: "{\"reply\":\"你好\"}", httpStatus: 200, outcome: outcome)
    }

    private func files(_ directory: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    }

    private func writeEnvelope(_ record: AIRequestRecord, in directory: URL) throws {
        struct Envelope: Encodable { let schemaVersion = 1; let record: AIRequestRecord }
        try JSONEncoder().encode(Envelope(record: record)).write(to: directory.appendingPathComponent("\(record.id.uuidString).json"))
    }

    func testRoundTripUpdatesOneRecordAndRestrictsPermissions() async throws {
        let dir = try directory()
        let archive = AIRequestHistoryArchive(directory: dir)
        var item = record(outcome: .running)
        try await archive.upsert(item)
        item.outcome = .succeeded
        item.finishedAt = item.startedAt.addingTimeInterval(3)
        try await archive.upsert(item)
        let loaded = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertEqual(loaded, [item])
        XCTAssertEqual(try files(dir).count, 1)
        let directoryMode = try FileManager.default.attributesOfItem(atPath: dir.path)[.posixPermissions] as? NSNumber
        let fileMode = try FileManager.default.attributesOfItem(atPath: XCTUnwrap(files(dir).first).path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(directoryMode?.intValue, 0o700)
        XCTAssertEqual(fileMode?.intValue, 0o600)
    }

    func testRecoveryMarksRunningInterruptedAndPersistsIt() async throws {
        let dir = try directory()
        let item = record(outcome: .running)
        try await AIRequestHistoryArchive(directory: dir).upsert(item)
        let untouched = try await AIRequestHistoryArchive(directory: dir).load(recoverInterrupted: false)
        XCTAssertEqual(untouched.first?.outcome, .running)
        let recovered = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertEqual(recovered.first?.outcome, .interrupted)
        XCTAssertNotNil(recovered.first?.finishedAt)
        let reloaded = try await AIRequestHistoryArchive(directory: dir).load(recoverInterrupted: false)
        XCTAssertEqual(reloaded, recovered)
    }

    func testDeleteAndClearSurviveReloadAndIgnoreLateUpdates() async throws {
        let dir = try directory()
        let archive = AIRequestHistoryArchive(directory: dir)
        let first = record(100), second = record(200)
        try await archive.upsert(first)
        try await archive.upsert(second)
        try await archive.remove(first.id)
        try await archive.upsert(first)
        let loaded = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertEqual(loaded.map(\.id), [second.id])
        try await archive.removeAll()
        try await archive.upsert(second)
        let cleared = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertTrue(cleared.isEmpty)
        XCTAssertTrue(try files(dir).isEmpty, "Deleted data must not remain in backup or temp files")
    }

    func testExplicitSecretsAndGenericCredentialsAreRedactedWithoutReformatting() throws {
        let secret = "dummy/quoted\"key\\value"
        let input = #"  {"content":"keep\nformat", "actual":"dummy\/quoted\"key\\value", "api_key":"dummy-field-secret","token":"dummy-token-secret"}  "#
        let output = AITraceRedactor.redact(input, secrets: [secret])
        XCTAssertEqual(output, #"  {"content":"keep\nformat", "actual":"[REDACTED]", "api_key":"[REDACTED]","token":"[REDACTED]"}  "#)
        XCTAssertEqual(AITraceRedactor.redact("raw \(secret) end", secrets: [secret]), "raw [REDACTED] end")
        XCTAssertEqual(AITraceRedactor.redact("Authorization: Bearer dummy-bearer-token\nplain sk-dummyToken12345"), "Authorization: [REDACTED]\nplain [REDACTED]")
        XCTAssertEqual(AITraceRedactor.redact("ordinary text with model and user messages"), "ordinary text with model and user messages")
    }

    func testArchiveBoundaryRedactsEveryStringBeforeDisk() async throws {
        let dir = try directory()
        var item = record()
        item.kind = "sk-dummyKind123"
        item.model = "sk-dummyModel123"
        item.endpoint = "http://localhost/v1?api_key=dummy-query-secret"
        item.requestBody = #"{"password":"dummy-password","messages":[{"content":"Bearer dummy-input-secret"}]}"#
        item.responseBody = #"{"access_token":"dummy-output-secret","text":"normal answer"}"#
        item.errorMessage = "Cookie: dummy-cookie-secret"
        try await AIRequestHistoryArchive(directory: dir).upsert(item)
        let loaded = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertTrue(try XCTUnwrap(loaded.first).requestRedacted)
        XCTAssertTrue(try XCTUnwrap(loaded.first).responseRedacted)
        let disk = String(decoding: try Data(contentsOf: XCTUnwrap(files(dir).first)), as: UTF8.self)
        for token in ["sk-dummyKind123", "sk-dummyModel123", "dummy-query-secret", "dummy-password", "dummy-input-secret", "dummy-output-secret", "dummy-cookie-secret"] {
            XCTAssertFalse(disk.contains(token), token)
        }
        XCTAssertTrue(disk.contains("normal answer"))
    }

    func testRedactsCredentialsInsideJSONEncodedMessageContent() throws {
        let input = #"{"messages":[{"content":"credentials: {\"api_key\":\"dummy-nested-key\",\"password\":\"dummy-nested-password\"}; preserve me"}]}"#
        XCTAssertEqual(AITraceRedactor.redact(input), #"{"messages":[{"content":"credentials: {\"api_key\":\"[REDACTED]\",\"password\":\"[REDACTED]\"}; preserve me"}]}"#)
    }

    func testTruncatedResponseMasksKnownSecretPrefixAtCutoff() {
        for prefix in ["s", "sk", "sk-", "sk-dum"] {
            XCTAssertEqual(AITraceRedactor.redact("response \(prefix)", secrets: ["sk-dummy-test-secret"], truncated: true), "response [REDACTED]")
        }
        XCTAssertEqual(AITraceRedactor.redact("response du", secrets: ["dummy-long-test-secret"], truncated: true), "response [REDACTED]")
        XCTAssertEqual(AITraceRedactor.redact(#"response dummy\/quo"#, secrets: ["dummy/quoted\"key"], truncated: true), "response [REDACTED]")
        XCTAssertEqual(AITraceRedactor.redact("response s", secrets: ["sk-dummy-test-secret"]), "response s")
        XCTAssertEqual(AITraceRedactor.redact("unrelated ending!", secrets: ["sk-dummy-test-secret"], truncated: true), "unrelated ending!")
    }

    func testKnownSecretsCannotReappearAfterJSONUnicodeOrNestedDecoding() throws {
        let secret = "dummy-special-secret"
        let samples = [
            #"{"text":"dummy-\u0073pecial-secret","keep":"\u4f60\u597d"}"#,
            #"{"text":"\u0064\u0075\u006d\u006d\u0079-special-secret"}"#,
            #"{"text":"{\"message\":\"dummy-\\u0073pecial-secret\"}"}"#,
            #"prefix dummy-\u005cu0073pecial-secret suffix"#
        ]
        for input in samples {
            let output = AITraceRedactor.redact(input, secrets: [secret])
            XCTAssertTrue(output.contains("[REDACTED]"), output)
            XCTAssertFalse(output.contains("special-secret"), output)
            if let object = try? JSONSerialization.jsonObject(with: Data(output.utf8)) {
                let dictionary = try XCTUnwrap(object as? [String: String])
                XCTAssertFalse(try XCTUnwrap(dictionary["text"]).contains(secret))
                if let nested = try? JSONSerialization.jsonObject(with: Data(try XCTUnwrap(dictionary["text"]).utf8)) as? [String: String] {
                    XCTAssertFalse(nested.values.contains { $0.contains(secret) })
                }
            }
        }
        let safe = #"  {"text":"unchanged \u4f60\u597d","content":"{\"safe\":\"fine\"}"}  "#
        XCTAssertEqual(AITraceRedactor.redact(safe, secrets: [secret]), safe)
    }

    func testTruncatedCredentialFieldsMaskThroughEndIncludingNestedUnicodeEscapes() {
        let samples = [
            #"{"password":"synthetic-credential-prefix"#,
            #"{"password":"synthetic-credential-prefix\"#,
            #"{"content":"{\"api_key\":\"synthetic-credential-prefix"#,
            #"{"content":"{\"api_\u006bey\":\"synthetic-credential-prefix"#,
            #"{"pa\u0073sword":"synthetic-credential-prefix"#
        ]
        for input in samples {
            let output = AITraceRedactor.redact(input, truncated: true)
            XCTAssertTrue(output.hasSuffix("[REDACTED]"), output)
            XCTAssertFalse(output.contains("synthetic-credential-prefix"), output)
        }
        XCTAssertEqual(AITraceRedactor.redact(#"response dummy-\u0073pe"#, secrets: ["dummy-special-secret"], truncated: true), "response [REDACTED]")
        XCTAssertEqual(AITraceRedactor.redact(#"response dummy-\u00"#, secrets: ["dummy-special-secret"], truncated: true), "response [REDACTED]")
    }

    func testDeeplyEncodedCredentialIsBoundedAndCannotBeDecodedBackFromDisk() async throws {
        let secret = "dummy-special-secret"
        var encoded = #"{"text":"dummy-\u0073pecial-secret"}"#
        for _ in 0..<8 { encoded = String(decoding: try JSONEncoder().encode(encoded), as: UTF8.self) }
        let redacted = AITraceRedactor.redact(encoded, secrets: [secret])
        XCTAssertFalse(redacted.contains("special-secret"))
        var decoded = redacted
        for _ in 0..<10 {
            if let next = try? JSONDecoder().decode(String.self, from: Data(decoded.utf8)) { decoded = next }
        }
        XCTAssertFalse(decoded.contains(secret))
        let dir = try directory()
        var item = record()
        item.responseBody = redacted
        try await AIRequestHistoryArchive(directory: dir).upsert(item)
        let loaded = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertEqual(loaded.first?.responseBody, redacted)
        let disk = try Data(contentsOf: XCTUnwrap(files(dir).first))
        XCTAssertFalse(String(decoding: disk, as: UTF8.self).contains("special-secret"))
    }

    func testEncodedCredentialFieldNameStillMasksCompleteEscapedValue() async throws {
        let input = #"{"pass\u0077ord":"synthetic\u0022tail-credential","keep":"safe"}"#
        let output = AITraceRedactor.redact(input)
        XCTAssertEqual(output, #"{"pass\u0077ord":"[REDACTED]","keep":"safe"}"#)
        let dir = try directory()
        var item = record()
        item.responseBody = #"{"pa\u0073sword":"synthetic-incomplete-credential"#
        item.responseTruncated = true
        try await AIRequestHistoryArchive(directory: dir).upsert(item)
        let loaded = try await AIRequestHistoryArchive(directory: dir).load()
        XCTAssertEqual(loaded.first?.responseBody, #"{"pa\u0073sword":"[REDACTED]"#)
    }

    func testExcessiveEscapeDepthFailsClosedWithoutUnboundedRecursion() {
        let hidden = "dummy-\\" + String(repeating: "u005c", count: 18) + "u0073pecial-secret"
        let output = AITraceRedactor.redact(hidden, secrets: ["dummy-special-secret"])
        XCTAssertEqual(output, "[REDACTED]")
    }

    func testLoadsCanonicalDataSoActualDiskBytesRespectLimit() async throws {
        let dir = try directory()
        let item = record()
        try writeEnvelope(item, in: dir)
        let file = try XCTUnwrap(files(dir).first)
        let padded = try Data(contentsOf: file) + Data(repeating: 32, count: 10_000)
        try padded.write(to: file)
        let loaded = try await AIRequestHistoryArchive(directory: dir, maxBytes: 2500).load()
        XCTAssertEqual(loaded, [item])
        XCTAssertLessThanOrEqual(try Data(contentsOf: file).count, 2500)
    }

    func testMismatchedIdentityIsRejectedWithoutOverwriting() async throws {
        let dir = try directory()
        let item = record()
        try writeEnvelope(item, in: dir)
        let original = try XCTUnwrap(files(dir).first)
        let renamed = dir.appendingPathComponent("\(UUID().uuidString).json")
        try FileManager.default.moveItem(at: original, to: renamed)
        let before = try Data(contentsOf: renamed)
        let archive = AIRequestHistoryArchive(directory: dir)
        do { _ = try await archive.load(); XCTFail("Identity mismatch must be reported") } catch {}
        do { try await archive.upsert(item); XCTFail("Identity mismatch must block writes") } catch {}
        XCTAssertEqual(try Data(contentsOf: renamed), before)
        XCTAssertEqual(try files(dir).count, 1)
    }

    func testByteBoundsApplyToNewAndDeserializedMultibyteBodies() async throws {
        let dir = try directory()
        var item = record()
        item.requestBody = String(repeating: "猫", count: 40_000)
        item.responseBody = String(repeating: "🙂", count: 50_000)
        try await AIRequestHistoryArchive(directory: dir).upsert(item)
        let stored = try await AIRequestHistoryArchive(directory: dir).load()
        let clipped = try XCTUnwrap(stored.first)
        XCTAssertLessThanOrEqual(clipped.requestBody.utf8.count, 64 * 1024)
        XCTAssertLessThanOrEqual(try XCTUnwrap(clipped.responseBody).utf8.count, 128 * 1024)
        XCTAssertTrue(clipped.requestBody.contains("[TRUNCATED]"))
        XCTAssertTrue(clipped.responseTruncated)
        XCTAssertFalse(clipped.requestBody.contains("�"))
        XCTAssertFalse(try XCTUnwrap(clipped.responseBody).contains("�"))

        item.id = UUID()
        item.responseBody = #"{"api_key":"dummy-injected-secret"}"# + String(repeating: "a", count: 180_000)
        try writeEnvelope(item, in: dir)
        let reloaded = try await AIRequestHistoryArchive(directory: dir).load()
        let injected = try XCTUnwrap(reloaded.first { $0.id == item.id })
        XCTAssertLessThanOrEqual(injected.requestBody.utf8.count, 64 * 1024)
        XCTAssertLessThanOrEqual(try XCTUnwrap(injected.responseBody).utf8.count, 128 * 1024)
        XCTAssertTrue(injected.responseTruncated)
        XCTAssertTrue(injected.responseRedacted)
        XCTAssertFalse(try String(contentsOf: dir.appendingPathComponent("\(item.id.uuidString).json"), encoding: .utf8).contains("dummy-injected-secret"))
    }

    func testOldestRecordsArePrunedForCountAndBytes() async throws {
        let dir = try directory()
        let archive = AIRequestHistoryArchive(directory: dir, maxRecords: 2)
        for time: TimeInterval in [300, 100, 200] { try await archive.upsert(record(time)) }
        let retained = try await archive.load()
        XCTAssertEqual(retained.map { $0.startedAt.timeIntervalSince1970 }, [300, 200])
        XCTAssertEqual(try files(dir).count, 2)

        let byteDir = try directory()
        let byteArchive = AIRequestHistoryArchive(directory: byteDir, maxBytes: 2500)
        for time: TimeInterval in [100, 200, 300] {
            var item = record(time)
            item.responseBody = String(repeating: "x", count: 1000)
            try await byteArchive.upsert(item)
        }
        let byteRetained = try await byteArchive.load()
        XCTAssertEqual(byteRetained.map { $0.startedAt.timeIntervalSince1970 }, [300])
        let total = try files(byteDir).reduce(0) { try $0 + Data(contentsOf: $1).count }
        XCTAssertLessThanOrEqual(total, 2500)
    }

    func testCorruptFutureAndOversizedFilesBlockWritesWithoutChangingEvidence() async throws {
        for payload in [Data("not-json".utf8), Data(#"{"schemaVersion":99,"futureContent":{}}"#.utf8), Data(repeating: 32, count: 2 * 1024 * 1024 + 1)] {
            let dir = try directory()
            let id = UUID()
            let file = dir.appendingPathComponent("\(id.uuidString).json")
            try payload.write(to: file)
            let archive = AIRequestHistoryArchive(directory: dir)
            do { _ = try await archive.load(); XCTFail("Unreadable history must be reported") } catch {}
            do { try await archive.upsert(record()); XCTFail("Must not overwrite unreadable history") } catch {}
            XCTAssertEqual(try Data(contentsOf: file), payload)
            XCTAssertEqual(try files(dir).count, 1)
            try await archive.remove(id)
            XCTAssertTrue(try files(dir).isEmpty)
        }
    }

    func testWriteFailureDoesNotPretendToSaveAndCanRetry() async throws {
        let parent = try directory()
        let blockedDirectory = parent.appendingPathComponent("history")
        try Data("blocker".utf8).write(to: blockedDirectory)
        let archive = AIRequestHistoryArchive(directory: blockedDirectory)
        let item = record()
        do { try await archive.upsert(item); XCTFail("Writing through a file must fail") } catch {}
        XCTAssertEqual(try String(contentsOf: blockedDirectory, encoding: .utf8), "blocker")
        try FileManager.default.removeItem(at: blockedDirectory)
        try await archive.upsert(item)
        let loaded = try await archive.load()
        XCTAssertEqual(loaded, [item])
    }
}
