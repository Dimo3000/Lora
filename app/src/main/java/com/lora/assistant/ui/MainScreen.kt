package com.lora.assistant.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lora.assistant.ChatMessage
import com.lora.assistant.LlmState
import com.lora.assistant.Phase
import com.lora.assistant.Role
import com.lora.assistant.SpeechController
import com.lora.assistant.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onMicClick: () -> Unit,
    onSendText: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onPermissionDenied: () -> Unit,
    onSystemRecognitionResult: (String?) -> Unit,
    onNoRecognizer: () -> Unit,
) {
    val context = LocalContext.current
    // Запасной путь: системное окно распознавания речи, если фонового сервиса на телефоне нет.
    val systemRecognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        onSystemRecognitionResult(text)
    }
    LaunchedEffect(state.systemRecognizerRequest) {
        if (state.systemRecognizerRequest == 0L) return@LaunchedEffect
        try {
            systemRecognizer.launch(SpeechController.recognizeIntent(state.settings.language))
        } catch (e: ActivityNotFoundException) {
            onNoRecognizer()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMicClick() else onPermissionDenied()
    }
    val micClick = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || (state.phase != Phase.IDLE && state.phase != Phase.WAKE)) onMicClick()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Лора") },
                actions = {
                    IconButton(onClick = onNewConversation) {
                        Icon(Icons.Filled.AddComment, contentDescription = "Новый разговор")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LlmBanner(state.llm, onOpenSettings)
            ChatList(state.messages, Modifier.weight(1f))
            BottomPanel(state, micClick, onSendText)
        }
    }
}

/** Подсказка вверху: пока нейросеть не скачана, Лора отвечает только на простые вопросы. */
@Composable
private fun LlmBanner(llm: LlmState, onOpenSettings: () -> Unit) {
    val text = when (llm) {
        LlmState.Missing -> "Чтобы Лора могла болтать на любые темы, скачайте её «разум» в настройках."
        is LlmState.Downloading -> "Скачиваю разум Лоры: ${(llm.doneBytes * 100 / llm.totalBytes.coerceAtLeast(1)).toInt()}%"
        LlmState.Loading -> "Лора просыпается, это займёт полминуты…"
        is LlmState.Failed -> "С разумом Лоры проблема, подробности в настройках."
        LlmState.Ready -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ChatList(messages: List<ChatMessage>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Нажмите на микрофон и спросите:\n«Какая погода завтра?», «Курс доллара», «Главные новости», «Кто такой Гагарин?»",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER
    val background = when (message.role) {
        Role.USER -> MaterialTheme.colorScheme.primaryContainer
        Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        Role.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (message.role) {
        Role.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Role.ASSISTANT -> MaterialTheme.colorScheme.onSurfaceVariant
        Role.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = background,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BottomPanel(state: UiState, onMicClick: () -> Unit, onSendText: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val status = when (state.phase) {
                Phase.IDLE -> "Нажмите, чтобы спросить"
                Phase.WAKE -> "Скажите «Лора» или нажмите на микрофон"
                Phase.LISTENING -> state.partialText.ifBlank { "Слушаю…" }
                Phase.THINKING -> "Думаю…"
                Phase.SPEAKING -> "Отвечаю… (нажмите, чтобы остановить)"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            MicButton(state, onMicClick)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Или напишите вопрос") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSendText(typed)
                        typed = ""
                    }),
                )
                IconButton(
                    onClick = {
                        onSendText(typed)
                        typed = ""
                    },
                    enabled = typed.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}

@Composable
private fun MicButton(state: UiState, onClick: () -> Unit) {
    val pulse by animateFloatAsState(
        targetValue = if (state.phase == Phase.LISTENING) 1f + state.soundLevel * 0.35f else 1f,
        label = "pulse",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
        if (state.phase == Phase.LISTENING) {
            Box(
                Modifier
                    .size(80.dp)
                    .scale(pulse * 1.2f)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
            )
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (state.phase == Phase.LISTENING) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
        ) {
            when (state.phase) {
                Phase.IDLE, Phase.WAKE -> Icon(Icons.Filled.Mic, "Говорить", Modifier.size(40.dp))
                Phase.LISTENING -> Icon(Icons.Filled.Stop, "Закончить", Modifier.size(40.dp))
                Phase.THINKING -> Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(56.dp),
                        strokeWidth = 3.dp,
                    )
                    Icon(Icons.Filled.Close, "Отменить", Modifier.size(28.dp))
                }
                Phase.SPEAKING -> Icon(Icons.AutoMirrored.Filled.VolumeOff, "Замолчать", Modifier.size(40.dp))
            }
        }
    }
}
