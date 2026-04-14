package com.myapplication

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.myapplication.common.App
import com.myapplication.common.PyTorchModel
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.ui.App as AppUI

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Pass the Android Context and repositories
            AppUI(
                pyTorchModel = PyTorchModel(applicationContext),
                settingsRepository = SettingsRepository(applicationContext),
                historyRepository = AnalysisHistoryRepository(applicationContext),
                context = applicationContext
            )
        }
    }
}