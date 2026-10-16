package com.lora.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import com.lora.assistant.LlmState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lora.assistant.AppSettings
import com.lora.assistant.Option

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: AppSettings,
    llm: LlmState,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    asr: LlmState,
    onDownloadAsr: () -> Unit,
    onCancelAsr: () -> Unit,
    onDeleteAsr: () -> Unit,
    voices: List<String>,
    onEnrollVoice: (String) -> Unit,
    onRemoveVoice: (String) -> Unit,
) {
    var city by rememberSaveable { mutableStateOf(initial.city) }
    var language by rememberSaveable { mutableStateOf(initial.language) }
    var autoListen by rememberSaveable { mutableStateOf(initial.autoListen) }
    var wakeWord by rememberSaveable { mutableStateOf(initial.wakeWord) }
    var yandexKey by rememberSaveable { mutableStateOf(initial.yandexKey) }
    var yandexFolder by rememberSaveable { mutableStateOf(initial.yandexFolder) }
    var speechRate by rememberSaveable { mutableStateOf(initial.speechRate) }
    var cloudSpeech by rememberSaveable { mutableStateOf(initial.cloudSpeech) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrainSection(llm, onDownloadModel, onCancelDownload, onDeleteModel)

            Spacer(Modifier.height(8.dp))
            AsrSection(asr, onDownloadAsr, onCancelAsr, onDeleteAsr)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Отзываться на имя «Лора»", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Работает, пока приложение открыто (экран при этом не гаснет). Скажите «Лора», дождитесь сигнала и задайте вопрос. Только русский язык.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wakeWord, onCheckedChange = { wakeWord = it })
            }

            Spacer(Modifier.height(8.dp))
            Text("Город для погоды", style = MaterialTheme.typography.titleMedium)
            Text(
                "Используется, если в вопросе не назван город.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = city,
                onValueChange = { city = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Москва") },
            )

            Spacer(Modifier.height(8.dp))
            Text("Язык распознавания и озвучки", style = MaterialTheme.typography.titleMedium)
            RadioGroup(AppSettings.LANGUAGES, language) { language = it }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Режим разговора", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "После ответа сразу снова слушать, без нажатия на кнопку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoListen, onCheckedChange = { autoListen = it })
            }

            Spacer(Modifier.height(8.dp))
            Text("Скорость речи", style = MaterialTheme.typography.titleMedium)
            Slider(value = speechRate, onValueChange = { speechRate = it }, valueRange = 0.8f..1.25f)
            Text(
                "%.2f: левее медленнее, правее быстрее.".format(speechRate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            AccountsSection()

            Spacer(Modifier.height(8.dp))
            Text("Яндекс Поиск (по желанию)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Если вставить ключ API и идентификатор каталога из Yandex Cloud (сервис Search API, он платный), " +
                    "Лора будет искать через Яндекс. Без них ищет через DuckDuckGo и Bing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = yandexKey, onValueChange = { yandexKey = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("API-ключ") })
            OutlinedTextField(value = yandexFolder, onValueChange = { yandexFolder = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Идентификатор каталога") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Яндекс SpeechKit: голос и распознавание", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "С ключом Лора говорит голосом Яндекса и распознаёт речь через Яндекс (платно, нужен интернет). " +
                            "У ключа должны быть роли ai.speechkit-tts.user и ai.speechkit-stt.user. Если сервис недоступен, работает офлайн.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cloudSpeech, onCheckedChange = { cloudSpeech = it })
            }

            Spacer(Modifier.height(8.dp))
            VoicesSection(voices, onEnrollVoice, onRemoveVoice)

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(AppSettings(city, language, autoListen, wakeWord, yandexKey, yandexFolder, cloudSpeech, speechRate)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = city.isNotBlank(),
            ) {
                Text("Сохранить")
            }
        }
    }
}

/** Мощная офлайн-модель распознавания русской речи. */
@Composable
private fun AsrSection(asr: LlmState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    Text("Точное распознавание речи", style = MaterialTheme.typography.titleMedium)
    Text(
        "Модель GigaAM для русского языка работает прямо на телефоне, без интернета и ключей, и понимает речь заметно лучше " +
            "встроенной маленькой. Скачивается один раз, около 230 МБ (лучше по Wi-Fi).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (asr) {
        LlmState.Missing -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Скачать точное распознавание (230 МБ)") }
        is LlmState.Downloading -> {
            val fraction = (asr.doneBytes.toFloat() / asr.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("Скачано ${asr.doneBytes / 1_000_000} из ${asr.totalBytes / 1_000_000} МБ", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Пауза") }
        }
        LlmState.Loading -> Text("Загружаю в память…", style = MaterialTheme.typography.bodyMedium)
        LlmState.Ready -> {
            Text("Готово: Лора распознаёт речь точнее.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Удалить модель") }
        }
        is LlmState.Failed -> {
            Text(asr.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Попробовать ещё раз") }
        }
    }
}

/** Музыкальные сервисы: вход выполняется в самом приложении сервиса, Лора просто запускает там музыку. */
@Composable
private fun AccountsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val services = androidx.compose.runtime.remember { com.lora.assistant.MusicApps.services(context) }
    Text("Музыка: войдите в аккаунт", style = MaterialTheme.typography.titleMedium)
    Text(
        "Лора включает музыку в приложениях сервисов по вашей подписке. Войдите в аккаунт в нужном приложении один раз, " +
            "дальше она будет запускать там треки по голосу. Пароли в Лору вводить не нужно.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    services.forEach { svc ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(svc.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (svc.installed) "Установлено" else "Не установлено",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { com.lora.assistant.MusicApps.openForLogin(context, svc.packageName) }) {
                Text(if (svc.installed) "Открыть и войти" else "Установить")
            }
        }
    }
}

/** Голоса семьи: Лора запоминает, кто говорит, и обращается по имени. */
@Composable
private fun VoicesSection(voices: List<String>, onEnroll: (String) -> Unit, onRemove: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Text("Голоса семьи", style = MaterialTheme.typography.titleMedium)
    Text(
        "Назовите имя и нажмите «Запомнить голос»: Лора попросит сказать три фразы. Потом она будет узнавать этого человека " +
            "и обращаться к нему по имени. Работает с встроенным распознаванием и не идеально: похожие голоса (например, мама и дочь) " +
            "она может путать. Голоса хранятся только на телефоне.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    voices.forEach { v ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { onRemove(v) }) { Text("Забыть") }
        }
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Имя, например Маша") },
    )
    Button(
        onClick = { onEnroll(name.trim()); name = "" },
        modifier = Modifier.fillMaxWidth(),
        enabled = name.isNotBlank(),
    ) { Text("Запомнить голос") }
}

/** Блок про «разум» Лоры: нейросеть, которая работает прямо на телефоне. */
@Composable
private fun BrainSection(llm: LlmState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    Text("Разум Лоры", style = MaterialTheme.typography.titleMedium)
    Text(
        "Нейросеть работает прямо на телефоне: без интернета и без ключей. Нужно скачать один раз около 2,6 ГБ " +
            "(лучше по Wi-Fi), свободно около 3 ГБ памяти и телефон с 6 ГБ оперативной памяти или больше. " +
            "Отвечает не мгновенно: на слабых телефонах несколько секунд на фразу.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (llm) {
        LlmState.Missing -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Скачать разум Лоры (2,6 ГБ)") }
        is LlmState.Downloading -> {
            val fraction = (llm.doneBytes.toFloat() / llm.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("Скачано ${llm.doneBytes / 1_000_000} из ${llm.totalBytes / 1_000_000} МБ", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Пауза") }
        }
        LlmState.Loading -> Text("Загружаю в память…", style = MaterialTheme.typography.bodyMedium)
        LlmState.Ready -> {
            Text("Готово: Лора умеет болтать на любые темы.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Удалить разум и освободить 2,6 ГБ") }
        }
        is LlmState.Failed -> {
            Text(llm.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Попробовать ещё раз") }
        }
    }
}

@Composable
private fun RadioGroup(options: List<Option>, selected: String, onSelect: (String) -> Unit) {
    Column {
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option.id == selected,
                        onClick = { onSelect(option.id) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option.id == selected, onClick = null)
                Text(option.title, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
