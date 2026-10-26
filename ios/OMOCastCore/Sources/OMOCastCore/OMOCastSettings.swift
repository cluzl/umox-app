import Foundation

/// Settings shared between the app and the broadcast extension.
/// The App Group must match the one in project.yml (change both when re-signing with your own team).
public enum OMOCastSettings {
    public static let appGroup = "group.com.umox.omocast"
    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    public static var clusterIP: String {
        get { defaults.string(forKey: "clusterIP") ?? "192.168.2.1" }
        set { defaults.set(newValue, forKey: "clusterIP") }
    }

    /// Last log lines from the extension, newest last, so the app can show what happened.
    public static var log: [String] {
        get { defaults.stringArray(forKey: "log") ?? [] }
        set { defaults.set(Array(newValue.suffix(40)), forKey: "log") }
    }

    public static func appendLog(_ line: String) {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        log += ["\(stamp) \(line)"]
    }
}
