import Foundation

/// Transport bodies only. Credentials and other request headers are never included.
/// Callers must redact body content before storing or displaying it.
public enum AIRequestTraceEvent: Sendable {
    case request(body: String, endpoint: String)
    case response(body: String, statusCode: Int?, truncated: Bool)
}

public typealias AIRequestTraceHandler = @Sendable (AIRequestTraceEvent) async -> Void

/// Shared by both clients so snapshots and response limits cannot drift apart.
enum AIRequestTransport {
    private static let maximumResponseBytes = 128_000

    static func data(for request: URLRequest, session: URLSession, trace: AIRequestTraceHandler?,
                     rejectOversizedContentLength: Bool = false) async throws -> Data {
        try Task.checkCancellation()
        await trace?(.request(body: String(decoding: request.httpBody ?? Data(), as: UTF8.self),
                              endpoint: request.url?.absoluteString ?? ""))
        var data = Data()
        var status: Int?
        var receivedResponse = false
        var finishedReading = false
        var truncated = false
        do {
            // A history callback may suspend; cancellation must still prevent sending.
            try Task.checkCancellation()
            let (bytes, response) = try await session.bytes(for: request)
            defer { bytes.task.cancel() }
            receivedResponse = true
            guard let response = response as? HTTPURLResponse else { throw AIError.invalidResponse }
            status = response.statusCode
            let successful = (200..<300).contains(response.statusCode)
            if !successful && trace == nil { throw AIError.http(response.statusCode) }
            if successful && rejectOversizedContentLength && response.expectedContentLength > maximumResponseBytes {
                truncated = true
                throw AIError.responseTooLarge
            }
            for try await byte in bytes {
                try Task.checkCancellation()
                guard data.count < maximumResponseBytes else {
                    truncated = true
                    throw AIError.responseTooLarge
                }
                data.append(byte)
            }
            finishedReading = true
            try Task.checkCancellation()
            guard successful else { throw AIError.http(response.statusCode) }
        } catch {
            await emitResponse(data, status: status,
                               truncated: truncated || (receivedResponse && !finishedReading), trace: trace)
            // Preserve cancellation even when collecting an HTTP error's body.
            if Task.isCancelled { throw CancellationError() }
            if let status, !(200..<300).contains(status) { throw AIError.http(status) }
            throw error
        }
        await emitResponse(data, status: status, truncated: truncated, trace: trace)
        try Task.checkCancellation()
        return data
    }

    private static func emitResponse(_ data: Data, status: Int?, truncated: Bool,
                                     trace: AIRequestTraceHandler?) async {
        guard let trace else { return }
        var text = String(decoding: data, as: UTF8.self)
        var cut = truncated
        // Replacement characters for malformed UTF-8 can expand the captured bytes.
        // Bound the displayed text too, without splitting a final UTF-8 scalar.
        if text.utf8.count > maximumResponseBytes {
            var prefix = Array(text.utf8.prefix(maximumResponseBytes))
            while String(bytes: prefix, encoding: .utf8) == nil { prefix.removeLast() }
            text = String(decoding: prefix, as: UTF8.self)
            cut = true
        }
        await trace(.response(body: text, statusCode: status, truncated: cut))
    }
}
