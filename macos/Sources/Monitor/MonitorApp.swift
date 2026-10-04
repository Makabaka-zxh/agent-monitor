import AppKit
import CoreText
import SwiftUI

enum MonitorResources {
    private static var bundle: Bundle {
        if let url = Bundle.main.resourceURL?.appendingPathComponent("AgentMonitor_Monitor.bundle"), let installed = Bundle(url: url) { return installed }
        return Bundle.module
    }
    static func url(_ name: String, extension suffix: String) -> URL? {
        bundle.url(forResource: name, withExtension: suffix, subdirectory: "Resources")
    }
    static func image(_ name: String) -> NSImage? { url(name, extension: "png").flatMap(NSImage.init(contentsOf:)) }
    static func registerFont() {
        if let url = url("MiSans-Regular", extension: "ttf") { CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil) }
    }
}

@main
@MainActor
struct MonitorApp: App {
    @NSApplicationDelegateAdaptor(MonitorApplicationDelegate.self) private var appDelegate
    init() { MonitorResources.registerFont() }
    var body: some Scene {
        Window("Monitor", id: "main") {
            RootView()
                .environmentObject(appDelegate.store)
                .font(.custom("MiSans-Regular", size: 14))
                .frame(minWidth: 940, minHeight: 650)
                .onOpenURL { url in Task { await appDelegate.store.handleURL(url) } }
        }
        .defaultSize(width: 1160, height: 780)
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(after: .newItem) {
                Button("刷新任务") { Task { await appDelegate.store.refresh() } }
                    .keyboardShortcut("r")
            }
        }
    }
}

@MainActor
final class MonitorApplicationDelegate: NSObject, NSApplicationDelegate {
    let store = MonitorStore()
    private var polling: Task<Void, Never>?
    func applicationWillFinishLaunching(_ notification: Notification) {
        store.usageAlerts.start()
    }
    func applicationDidFinishLaunching(_ notification: Notification) {
        polling = Task { await store.run() }
        AcceptanceProbe.shared.start(store: store)
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationWillTerminate(_ notification: Notification) { polling?.cancel(); AcceptanceProbe.shared.stop() }
}
