package com.lora.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lora.assistant.ui.MainScreen
import com.lora.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onMicPermissionResult(granted)
    }

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

                // Пока ждём имя «Лора», экран не гаснет, иначе телефон перестанет слушать.
                DisposableEffect(state.settings.wakeWord) {
                    if (state.settings.wakeWord) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }

                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        initial = state.settings,
                        llm = state.llm,
                        onSave = {
                            viewModel.saveSettings(it)
                            showSettings = false
                            if (it.wakeWord) ensureMicPermission()
                        },
                        onBack = { showSettings = false },
                        onDownloadModel = viewModel::downloadModel,
                        onCancelDownload = viewModel::cancelDownload,
                        onDeleteModel = viewModel::deleteModel,
                        voices = state.voices,
                        onEnrollVoice = {
                            showSettings = false
                            ensureMicPermission()
                            viewModel.startEnroll(it)
                        },
                        onRemoveVoice = viewModel::removeVoice,
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

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        if (viewModel.state.value.settings.wakeWord) ensureMicPermission()
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
