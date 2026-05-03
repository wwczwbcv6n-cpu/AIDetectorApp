import androidx.compose.ui.window.ComposeUIViewController
import com.myapplication.common.PyTorchModel
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.ui.App

fun MainViewController() = ComposeUIViewController {
    App(
        pyTorchModel = PyTorchModel(Unit),
        settingsRepository = SettingsRepository(),
        historyRepository = AnalysisHistoryRepository(),
        context = Unit
    )
}
