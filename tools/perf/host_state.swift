import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

// Read host visibility without capturing screen contents or window titles.
// Activation uses the existing accessibility grant when normal AppKit activation
// is declined by macOS. No permission prompt or power preference is changed.
let target = CommandLine.arguments.dropFirst().first.flatMap(Int32.init) ?? -1
let targetApp = NSRunningApplication(processIdentifier: target)
var output: [String: Any] = ["target_pid": target, "wall_time": Date().timeIntervalSince1970]
output["previous_frontmost_pid"] = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1
let locked = NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.loginwindow"
output["frontmost_is_loginwindow"] = locked
if CommandLine.arguments.contains("--activate") && locked {
    output["activation_requested"] = true
    output["activation_accepted"] = false
    output["activation_blocked_reason"] = "host_session_locked"
} else if CommandLine.arguments.contains("--activate") {
    output["activation_requested"] = true
    output["activation_accepted"] = targetApp?.activate(options: [.activateAllWindows]) ?? false
    RunLoop.current.run(until: Date().addingTimeInterval(0.15))
    if NSWorkspace.shared.frontmostApplication?.processIdentifier != target && AXIsProcessTrusted() {
        let application = AXUIElementCreateApplication(target)
        var settable = DarwinBoolean(false)
        let capability = AXUIElementIsAttributeSettable(application, kAXFrontmostAttribute as CFString, &settable)
        output["ax_frontmost_settable"] = capability == .success && settable.boolValue
        if capability == .success && settable.boolValue {
            output["ax_activation_result"] = AXUIElementSetAttributeValue(application, kAXFrontmostAttribute as CFString, kCFBooleanTrue).rawValue
        }
        let deadline = Date().addingTimeInterval(1.0)
        while NSWorkspace.shared.frontmostApplication?.processIdentifier != target && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
    }
}
output["target_active"] = targetApp?.isActive ?? false
output["target_hidden"] = targetApp?.isHidden ?? false
output["target_activation_policy"] = targetApp?.activationPolicy.rawValue ?? -1
let front = NSWorkspace.shared.frontmostApplication
output["frontmost_pid"] = front?.processIdentifier ?? -1
output["target_frontmost"] = front?.processIdentifier == target
let all = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
    as? [[String: Any]] ?? []
output["onscreen_windows"] = all.filter {
    ($0[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == target
}.map { row in
    ["layer": row[kCGWindowLayer as String] ?? -1,
     "bounds": row[kCGWindowBounds as String] ?? [:]]
}
output["wall_time"] = Date().timeIntervalSince1970
let data = try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys])
print(String(data: data, encoding: .utf8)!)
