import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.myapplication.common.PyTorchModel
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AI Image Detector"
    ) {
        App(
            pyTorchModel = PyTorchModel(),
            settingsRepository = SettingsRepository(),
            historyRepository = AnalysisHistoryRepository(),
            context = Unit
        )
    }
}