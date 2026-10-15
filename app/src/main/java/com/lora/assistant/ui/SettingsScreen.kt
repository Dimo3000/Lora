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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
fun SettingsScreen(initial: AppSettings, onSave: (AppSettings) -> Unit, onBack: () -> Unit) {
    var city by rememberSaveable { mutableStateOf(initial.city) }
    var language by rememberSaveable { mutableStateOf(initial.language) }
    var autoListen by rememberSaveable { mutableStateOf(initial.autoListen) }

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

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(AppSettings(city, language, autoListen)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = city.isNotBlank(),
            ) {
                Text("Сохранить")
            }
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
