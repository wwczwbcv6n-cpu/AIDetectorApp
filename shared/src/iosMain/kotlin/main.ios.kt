import androidx.compose.ui.window.ComposeUIViewController
import com.myapplication.common.Logger
import com.myapplication.common.PyTorchModel
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.secure.HpkeBridge
import com.myapplication.common.secure.HpkeBridgeRegistry
import com.myapplication.common.ui.App

/**
 * Swift calls this once at launch (see `iOSApp.swift`) with the CryptoKit
 * implementation of [HpkeBridge]. Without it every sealed upload fails
 * closed — there is deliberately no plaintext fallback.
 */
fun installHpkeBridge(bridge: HpkeBridge) = HpkeBridgeRegistry.install(bridge)

fun MainViewController() = ComposeUIViewController {
    if (!HpkeBridgeRegistry.isInstalled) {
        Logger.warn("HpkeBridge not installed — uploads will fail closed until iOSApp installs CryptoKitHpkeBridge")
    }
    App(
        pyTorchModel = PyTorchModel(Unit),
        settingsRepository = SettingsRepository(),
        historyRepository = AnalysisHistoryRepository(),
        context = Unit
    )
}
