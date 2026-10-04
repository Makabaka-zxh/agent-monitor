import AppKit
import Foundation
import UserNotifications

/// One-shot, explicitly launched acceptance capture of Monitor's own live views.
/// No desktop capture, input injection, or remote commands. Notification testing is separately opt-in.
@MainActor
final class AcceptanceProbe {
    static let shared = AcceptanceProbe()
    static let navigation = Notification.Name("MonitorAcceptanceNavigation")
    static let reminders = Notification.Name("MonitorAcceptanceReminders")
    private(set) var active = false
    private var started = false
    private var operation: Task<Void, Never>?
    private var displayedPage = ""
    private var usageVisible = false
    private var remindersVisible = false

    func rootDisplayed(_ page: String) { displayedPage = page }
    func usageDisplayed(_ visible: Bool) { usageVisible = visible }
    func remindersDisplayed() { remindersVisible = true }
    func stop() { operation?.cancel() }

    func start(store: MonitorStore) {
        guard !started, ProcessInfo.processInfo.arguments.contains("--monitor-acceptance") else { return }
        started = true
        active = true
        operation = Task { await run(store: store) }
    }

    private func wait(seconds: TimeInterval, until condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while !condition() && Date() < deadline && !Task.isCancelled {
            do { try await Task.sleep(for: .milliseconds(150)) } catch { return false }
        }
        return !Task.isCancelled && condition()
    }

    private func run(store: MonitorStore) async {
        let began = Date()
        var report: [String: Any] = [
            "started_at": ISO8601DateFormatter().string(from: began), "state": "running",
            "scope": "Monitor_live_view_content_only", "desktop_captured": false,
            "permission_requested": false, "notification_delivery_tested": false,
            "system_banner_verified": false, "notification_click_verified": false,
            "pointer_interaction_verified": false, "visual_review_performed": false
        ]
        var steps = [[String: Any]]()
        var directory: URL?
        defer { active = false }
        do {
            let destination = try makeDirectory()
            directory = destination
            try save(report, in: destination)
            let ready = await wait(seconds: 45) {
                store.connected && store.account != nil && store.lastSuccess != nil && !store.refreshing
            }
            report["account_ready"] = ready
            guard ready else { throw ProbeFailure.accountNotReady }
            let accountIdentity = store.tokenFingerprint // Compared only in memory; never exported.

            let taskStart = Date()
            NotificationCenter.default.post(name: Self.navigation, object: "tasks")
            guard await wait(seconds: 5, until: { self.displayedPage == "tasks" }) else { throw ProbeFailure.navigation }
            try await Task.sleep(for: .milliseconds(700))
            steps.append(try capture("tasks", window: mainWindow(), in: destination, began: taskStart))
            report["steps"] = steps
            try save(report, in: destination)

            let usageStart = Date()
            NotificationCenter.default.post(name: Self.navigation, object: "usage")
            guard await wait(seconds: 5, until: { self.displayedPage == "usage" && self.usageVisible }) else { throw ProbeFailure.navigation }
            let usageReady = await wait(seconds: 35) {
                !store.connected || !store.usageError.isEmpty || (store.usage != nil && !store.usageRefreshing && store.usage?.loading != true)
            }
            guard store.connected && store.tokenFingerprint == accountIdentity else { throw ProbeFailure.accountChanged }
            try await Task.sleep(for: .milliseconds(700))
            var usageStep = try capture("usage", window: mainWindow(), in: destination, began: usageStart)
            usageStep["data_ready"] = usageReady && store.usage != nil && store.usageError.isEmpty && store.usage?.loading != true
            usageStep["refresh_timed_out"] = !usageReady
            usageStep["refresh_error_present"] = !store.usageError.isEmpty
            steps.append(usageStep)
            report["steps"] = steps
            try save(report, in: destination)

            let reminderStart = Date()
            NotificationCenter.default.post(name: Self.reminders, object: nil)
            guard await wait(seconds: 5, until: { self.remindersVisible && self.mainWindow()?.attachedSheet != nil }) else { throw ProbeFailure.navigation }
            try await Task.sleep(for: .milliseconds(700))
            guard store.connected && store.tokenFingerprint == accountIdentity else { throw ProbeFailure.accountChanged }
            steps.append(try capture("reminders", window: mainWindow()?.attachedSheet, in: destination, began: reminderStart))
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            report["notification_permission"] = [
                "authorization_status": settings.authorizationStatus.rawValue,
                "alert_setting": settings.alertSetting.rawValue,
                "sound_setting": settings.soundSetting.rawValue,
                "notification_center_setting": settings.notificationCenterSetting.rawValue,
                "lock_screen_setting": settings.lockScreenSetting.rawValue
            ]
            if ProcessInfo.processInfo.arguments.contains("--monitor-acceptance-test-notification") {
                guard store.connected && store.tokenFingerprint == accountIdentity else { throw ProbeFailure.accountChanged }
                let expectsPermission = settings.authorizationStatus == .notDetermined
                report["steps"] = steps
                report["state"] = expectsPermission ? "waiting_for_notification_permission" : "testing_notification_delivery"
                report["permission_request_expected"] = expectsPermission
                try save(report, in: destination)
                let testBegan = Date()
                let previousMessage = store.usageAlerts.message
                // The regular App action owns authorization. Only the user can allow its system prompt.
                await store.usageAlerts.sendTest()
                guard store.connected && store.tokenFingerprint == accountIdentity else { throw ProbeFailure.accountChanged }
                let expectedIdentifier = "monitor.usage.\(accountIdentity).test" // Memory only.
                let deadline = Date().addingTimeInterval(5)
                var deliveredAt: Date?
                repeat {
                    let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
                    guard store.connected && store.tokenFingerprint == accountIdentity else { throw ProbeFailure.accountChanged }
                    deliveredAt = delivered.first {
                        $0.request.identifier == expectedIdentifier && $0.date >= testBegan
                    }?.date
                    if deliveredAt != nil || Date() >= deadline || Task.isCancelled { break }
                    try await Task.sleep(for: .milliseconds(250))
                } while true
                let submittedMessage = "测试提醒已提交；横幅和声音由系统设置决定"
                let messageIndicatesSubmission = store.usageAlerts.message == submittedMessage
                let after = await UNUserNotificationCenter.current().notificationSettings()
                guard store.connected && store.tokenFingerprint == accountIdentity else { throw ProbeFailure.accountChanged }
                report["notification_delivery_tested"] = true
                report["permission_requested"] = expectsPermission && after.authorizationStatus != .notDetermined
                report["test_submitted"] = deliveredAt != nil || (messageIndicatesSubmission && previousMessage != submittedMessage)
                report["submission_message_indicates_success"] = messageIndicatesSubmission
                report["test_delivery_confirmed"] = deliveredAt != nil
                report["notification_authorization_after_test"] = after.authorizationStatus.rawValue
                report["notification_test_elapsed_seconds"] = Date().timeIntervalSince(testBegan)
                report["notification_test_result"] = deliveredAt == nil ? "delivery_unconfirmed" : "delivered_to_notification_center"
                if let deliveredAt { report["test_delivered_at"] = ISO8601DateFormatter().string(from: deliveredAt) }
                // Delivery is not evidence of banner appearance, sound, or clicking the notification.
            }
            report["state"] = usageStep["data_ready"] as? Bool == true ? "captured" : "captured_with_data_issue"
            report["window_content_capture_completed"] = true
        } catch {
            report["state"] = "failed"
            report["failure"] = (error as? ProbeFailure)?.rawValue ?? (Task.isCancelled ? "cancelled" : "capture_or_file_write_failed")
            report["window_content_capture_completed"] = false
        }
        report["steps"] = steps
        report["finished_at"] = ISO8601DateFormatter().string(from: Date())
        report["elapsed_seconds"] = Date().timeIntervalSince(began)
        if let directory { try? save(report, in: directory) }
    }

    private func mainWindow() -> NSWindow? {
        NSApplication.shared.windows.first { $0.isVisible && $0.canBecomeMain && !$0.isSheet && $0.contentView != nil }
    }

    private func capture(_ name: String, window: NSWindow?, in directory: URL, began: Date) throws -> [String: Any] {
        guard ["tasks", "usage", "reminders"].contains(name), let window, let view = window.contentView else { throw ProbeFailure.windowMissing }
        view.layoutSubtreeIfNeeded()
        view.displayIfNeeded()
        guard view.bounds.width > 100, view.bounds.height > 100,
              let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw ProbeFailure.emptyCapture }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        var opaque = 0, nonblack = 0
        var colors = Set<String>()
        for y in stride(from: 0, to: bitmap.pixelsHigh, by: max(1, bitmap.pixelsHigh / 48)) {
            for x in stride(from: 0, to: bitmap.pixelsWide, by: max(1, bitmap.pixelsWide / 48)) {
                guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB), color.alphaComponent > 0.01 else { continue }
                opaque += 1
                if max(color.redComponent, color.greenComponent, color.blueComponent) > 0.02 { nonblack += 1 }
                colors.insert("\(Int(color.redComponent * 255)),\(Int(color.greenComponent * 255)),\(Int(color.blueComponent * 255))")
            }
        }
        guard opaque > 0, nonblack > 0, colors.count > 1,
              let data = bitmap.representation(using: .png, properties: [:]) else { throw ProbeFailure.emptyCapture }
        let file = directory.appendingPathComponent(name + ".png")
        try data.write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        return ["step": name, "state": "captured", "file": name + ".png",
                "pixels_wide": bitmap.pixelsWide, "pixels_high": bitmap.pixelsHigh,
                "opaque_samples": opaque, "nonblack_samples": nonblack, "distinct_sample_colors": colors.count,
                "source": name == "reminders" ? "live_attached_sheet_content" : "live_main_window_content",
                "elapsed_seconds": Date().timeIntervalSince(began)]
    }

    private func makeDirectory() throws -> URL {
        let manager = FileManager.default
        let base = manager.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Monitor/Acceptance", isDirectory: true)
        // Never follow an existing symlink for this narrowly scoped output directory.
        for url in [base.deletingLastPathComponent(), base] where manager.fileExists(atPath: url.path) {
            guard try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw ProbeFailure.unsafeDirectory }
        }
        try manager.createDirectory(at: base, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8).lowercased()
        let directory = base.appendingPathComponent(formatter.string(from: Date()) + "-" + suffix, isDirectory: true)
        try manager.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        return directory
    }

    private func save(_ report: [String: Any], in directory: URL) throws {
        let file = directory.appendingPathComponent("report.json")
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }

    private enum ProbeFailure: String, Error {
        case accountNotReady = "account_not_ready"
        case accountChanged = "account_changed"
        case navigation = "navigation_timeout"
        case windowMissing = "own_window_missing"
        case emptyCapture = "empty_or_uniform_view_capture"
        case unsafeDirectory = "unsafe_output_directory"
    }
}
