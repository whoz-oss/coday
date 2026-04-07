import Foundation

enum AppConfig {
    /// Default backend base URL. Override in Settings.
    static let defaultBaseURL = "http://localhost:3000"

    /// UserDefaults key for the stored base URL
    static let baseURLKey = "coday_base_url"
    static let usernameKey = "coday_username"
    static let defaultUsername = "ios-user@coday.local"

    static var baseURL: String {
        get { UserDefaults.standard.string(forKey: baseURLKey) ?? defaultBaseURL }
        set { UserDefaults.standard.set(newValue, forKey: baseURLKey) }
    }

    static var username: String {
        get { UserDefaults.standard.string(forKey: usernameKey) ?? defaultUsername }
        set { UserDefaults.standard.set(newValue, forKey: usernameKey) }
    }
}
