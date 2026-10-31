import Foundation
import CoreFoundation

/// Explicit local consent, scoped to one account and installation. Never synchronized with task data.
public final class ExecutionPresencePreferences {
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func isEnabled(accountID: UUID, deviceID: UUID) -> Bool {
        guard let stored = defaults.object(forKey: key(accountID, deviceID)) as? NSNumber,
              CFGetTypeID(stored) == CFBooleanGetTypeID() else { return false }
        return stored.boolValue
    }

    public func setEnabled(_ enabled: Bool, accountID: UUID, deviceID: UUID) throws {
        let name = key(accountID, deviceID)
        defaults.set(enabled, forKey: name)
        guard defaults.synchronize(), (defaults.object(forKey: name) as? Bool) == enabled else {
            // A failed consent write must never leave uploading enabled in this process.
            defaults.set(false, forKey: name)
            _ = defaults.synchronize()
            throw SyncHTTPError.invalidRequest
        }
    }

    private func key(_ accountID: UUID, _ deviceID: UUID) -> String {
        "pare.presence.sharing.v1.\(accountID.uuidString.lowercased()).\(deviceID.uuidString.lowercased())"
    }
}
