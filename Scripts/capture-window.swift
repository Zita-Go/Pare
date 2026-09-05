import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import Foundation

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
      let owner = window[kCGWindowOwnerName as String] as? String,
      ["Pare Screenshots", "Pare Shortcut Check"].contains(owner),
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
