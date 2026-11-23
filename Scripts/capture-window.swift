import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import ScreenCaptureKit
import Foundation
import Darwin

let allowedBundleIDs: Set<String> = ["com.zitago.Pare.ReadmeDemo", "com.zitago.Pare.ShortcutCheck"]
func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("capture-window: \(message)\n".utf8))
    exit(EXIT_FAILURE)
}

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
    let requestedPID: Int?
    if CommandLine.arguments.count == 3 {
        guard let pid = pid_t(CommandLine.arguments[2]),
              let bundleID = NSRunningApplication(processIdentifier: pid)?.bundleIdentifier,
              allowedBundleIDs.contains(bundleID) else {
            fail("--list-pid accepts only a running Step Screenshots or Shortcut Check demo")
        }
        requestedPID = Int(pid)
    } else { requestedPID = nil }
    let windows = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] ?? []
    let selected = windows.filter { window in
        isPareDemo(window) && (requestedPID.map { integer(window[kCGWindowOwnerPID as String]) == $0 } ?? true)
    }
    if requestedPID != nil { print("visible-window-count=\(selected.count)") }
    for window in selected {
        guard let id = integer(window[kCGWindowNumber as String]),
              let dictionary = window[kCGWindowBounds as String] as? [String: Any],
              let bounds = CGRect(dictionaryRepresentation: dictionary as CFDictionary) else { continue }
        let owner = window[kCGWindowOwnerName as String] as? String ?? "Step demo"
        let name = window[kCGWindowName as String] as? String ?? ""
        let layer = window[kCGWindowLayer as String] as? Int ?? 0
        print("\(id)\t\(owner)\tlayer=\(layer)\t\(Int(bounds.width))x\(Int(bounds.height))\t\(name)")
    }
    exit(EXIT_SUCCESS)
}

// Documentation exports always include the complete native window and title bar.
// Accept the old explicit zero inset for compatibility, but reject partial exports.
guard (3...4).contains(CommandLine.arguments.count),
      let windowID = UInt32(CommandLine.arguments[1]), windowID != 0 else {
    fail("Usage: capture-window WINDOW_ID OUTPUT.png [0] | --list | --list-pid DEMO_PID")
}
if CommandLine.arguments.count == 4 {
    guard let inset = Double(CommandLine.arguments[3]), inset == 0 else {
        fail("Only full-window screenshots are supported; omit the top inset or use 0")
    }
}
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let windows = CGWindowListCopyWindowInfo(.optionIncludingWindow, windowID) as? [[String: Any]] ?? []
guard let window = windows.first,
      isPareDemo(window),
      let ownerPID = integer(window[kCGWindowOwnerPID as String]) else {
    fail("Select an available Step Screenshots demo window")
}

@available(macOS 14.0, *)
func captureFullWindow(id: CGWindowID, ownerPID: Int, output: URL) async throws {
    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
    guard let window = content.windows.first(where: {
        $0.windowID == id && $0.owningApplication.map {
            allowedBundleIDs.contains($0.bundleIdentifier) && Int($0.processID) == ownerPID
        } == true
    }) else {
        fail("Selected demo window is no longer available")
    }
    // A desktop-independent filter includes only the selected window, not the user's
    // other apps or desktop. The native scale is normally 2 on a Retina display.
    let filter = SCContentFilter(desktopIndependentWindow: window)
    let rect = filter.contentRect
    let scale = CGFloat(filter.pointPixelScale)
    guard rect.width.isFinite, rect.height.isFinite, scale.isFinite,
          rect.width > 0, rect.height > 0, scale > 0 else {
        fail("ScreenCaptureKit returned invalid window geometry")
    }
    let config = SCStreamConfiguration()
    config.width = Int((rect.width * scale).rounded())
    config.height = Int((rect.height * scale).rounded())
    config.captureResolution = .best
    config.scalesToFit = false
    config.preservesAspectRatio = true
    config.showsCursor = false
    config.capturesAudio = false
    config.ignoreShadowsSingleWindow = true
    config.ignoreGlobalClipSingleWindow = true
    // sourceRect intentionally remains unset: the full window includes the title bar.
    let image = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
    guard image.width == config.width, image.height == config.height else {
        fail("Unexpected capture dimensions; refusing to resize or crop the image")
    }
    guard let destination = CGImageDestinationCreateWithURL(output as CFURL,
                                                           UTType.png.identifier as CFString, 1, nil) else {
        fail("Could not create PNG output")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fail("Could not save screenshot") }
    print("Saved \(output.lastPathComponent): \(image.width) × \(image.height), complete window at \(scale)× native scale")
}

if #available(macOS 14.0, *) {
    Task {
        do {
            try await captureFullWindow(id: windowID, ownerPID: ownerPID, output: output)
            exit(EXIT_SUCCESS)
        } catch {
            fail("ScreenCaptureKit capture failed: \(error.localizedDescription)")
        }
    }
    dispatchMain()
} else {
    fail("Screenshot export requires macOS 14 or later; the Step app's requirements are unchanged")
}
