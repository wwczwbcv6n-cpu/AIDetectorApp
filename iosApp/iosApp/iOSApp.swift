import SwiftUI
import shared

@main
struct iOSApp: App {
	init() {
		// CryptoKit HPKE bridge for the TSE2 upload envelope. Must run before the
		// first upload; without it the Kotlin side fails closed (no plaintext path).
		Main_iosKt.installHpkeBridge(bridge: CryptoKitHpkeBridge())
	}

	var body: some Scene {
		WindowGroup {
			ContentView()
		}
	}
}
