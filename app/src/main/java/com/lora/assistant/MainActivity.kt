package com.lora.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lora.assistant.ui.MainScreen
import com.lora.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val accent = Color(0xFF5B4FCF)
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFB9B1FF), secondary = Color(0xFF9C93F0))
            } else {
                lightColorScheme(primary = accent, secondary = Color(0xFF7A70E0))
            }
            MaterialTheme(colorScheme = colors) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        initial = state.settings,
                        onSave = {
                            viewModel.saveSettings(it)
                            showSettings = false
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    MainScreen(
                        state = state,
                        onMicClick = viewModel::onMicClick,
                        onSendText = viewModel::sendText,
                        onNewConversation = viewModel::newConversation,
                        onOpenSettings = { showSettings = true },
                        onPermissionDenied = viewModel::permissionDenied,
                        onSystemRecognitionResult = viewModel::onSystemRecognitionResult,
                        onNoRecognizer = viewModel::noRecognizerAtAll,
                    )
                }
            }
        }
    }
}
