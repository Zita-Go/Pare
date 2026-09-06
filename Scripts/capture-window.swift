import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import Foundation
import Darwin

let allowedBundleIDs: Set<String> = ["com.zitago.Pare.ReadmeDemo", "com.zitago.Pare.ShortcutCheck"]
func integer(_ value: Any?) -> Int? {
    if let value = value as? Int { return value }
    if let value = value as? NSNumber { return value.intValue }
    return nil
}
func isPareDemo(_ window: [String: Any]) -> Bool {
    guard let rawPID = integer(window[kCGWindowOwnerPID as String]),
          let pid = pid_t(exactly: rawPID),
          let identifier = NSRunningApplication(processIdentifier: pid)?.bundleIdentifier else { return false }
    return allowedBundleIDs.contains(identifier)
}

if (CommandLine.arguments.count == 2 && CommandLine.arguments[1] == "--list")
    || (CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--list-pid") {
    let requestedPID = CommandLine.arguments.count == 3 ? Int(CommandLine.arguments[2]) : nil
    let windows = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] ?? []
    if requestedPID != nil { print("visible-window-count=\(windows.count)") }
    for window in windows {
        let ownerPID = integer(window[kCGWindowOwnerPID as String])
        guard (requestedPID.map { ownerPID == $0 } ?? isPareDemo(window)),
              let id = integer(window[kCGWindowNumber as String]),
              let dictionary = window[kCGWindowBounds as String] as? [String: Any],
              let bounds = CGRect(dictionaryRepresentation: dictionary as CFDictionary) else { continue }
        let owner = window[kCGWindowOwnerName as String] as? String ?? "Pare demo"
        let name = window[kCGWindowName as String] as? String ?? ""
        let layer = window[kCGWindowLayer as String] as? Int ?? 0
        print("\(id)\t\(owner)\tlayer=\(layer)\t\(Int(bounds.width))x\(Int(bounds.height))\t\(name)")
    }
    exit(EXIT_SUCCESS)
}

// Capture only the selected native window, even while another app is in front.
// The optional top inset omits system sharing controls in the title bar at
// capture time; no UI pixels are repainted or reconstructed.
guard CommandLine.arguments.count >= 3,
      let windowID = UInt32(CommandLine.arguments[1]) else {
    fatalError("Usage: capture-window WINDOW_ID OUTPUT.png [TOP_INSET_PT]")
}
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let inset = CommandLine.arguments.count > 3 ? Double(CommandLine.arguments[3]) ?? 0 : 0
let windows = CGWindowListCopyWindowInfo(.optionIncludingWindow, windowID) as? [[String: Any]] ?? []
guard let window = windows.first,
      isPareDemo(window),
      let dictionary = window[kCGWindowBounds as String] as? [String: Any],
      let bounds = CGRect(dictionaryRepresentation: dictionary as CFDictionary) else {
    fatalError("Select an available Pare Screenshots demo window")
}
guard inset >= 0, inset < bounds.height else { fatalError("Invalid top inset") }
let crop = CGRect(x: bounds.minX, y: bounds.minY + inset,
                  width: bounds.width, height: bounds.height - inset)
guard let image = CGWindowListCreateImage(crop, .optionIncludingWindow, windowID,
                                         [.boundsIgnoreFraming, .bestResolution]),
      let destination = CGImageDestinationCreateWithURL(output as CFURL,
                                                        UTType.png.identifier as CFString, 1, nil) else {
    fatalError("Could not capture selected window")
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Could not save screenshot") }
print("Saved \(output.lastPathComponent): \(image.width) × \(image.height)")
