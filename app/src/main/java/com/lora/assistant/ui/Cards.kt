package com.lora.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lora.assistant.AudioCard
import com.lora.assistant.AudioPlayer
import com.lora.assistant.Card
import com.lora.assistant.GalleryCard
import com.lora.assistant.LinkCard
import com.lora.assistant.WeatherCard

@Composable
fun CardView(card: Card) {
    when (card) {
        is WeatherCard -> WeatherView(card)
        is GalleryCard -> GalleryView(card)
        is AudioCard -> AudioView(card)
        is LinkCard -> LinkView(card)
    }
}

@Composable
private fun open(link: String): () -> Unit {
    val context = LocalContext.current
    return {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun WeatherView(card: WeatherCard) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(card.city, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.emoji, fontSize = 52.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        card.temperature?.let { "$it°" } ?: card.days.getOrNull(1)?.let { "${it.max}°" }.orEmpty(),
                        fontSize = 40.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(card.description, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            val details = listOfNotNull(
                card.feelsLike?.let { "Ощущается как $it°" },
                card.wind?.let { "Ветер $it м/с" },
            ).joinToString(" · ")
            if (details.isNotBlank()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                card.days.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(d.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(d.emoji, fontSize = 26.sp)
                        Text("${d.max}° / ${d.min}°", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (d.rain >= 0) {
                            Text("💧${d.rain}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryView(card: GalleryCard) {
    Column {
        Text(card.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(card.tiles) { tile ->
                val go = open(tile.link)
                Column(Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).clickable { go() }) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(tile.imageUrl)
                            .addHeader("User-Agent", "Lora/1.6 (Android voice assistant)").crossfade(true).build(),
                        contentDescription = tile.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    Text(tile.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                    if (tile.subtitle.isNotBlank()) {
                        Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioView(card: AudioCard) {
    val active = AudioPlayer.isCurrent(card.url)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                if (active && AudioPlayer.preparing) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    IconButton(onClick = { AudioPlayer.toggle(card.url) }) {
                        Text(if (active && AudioPlayer.playing) "⏸" else "▶", fontSize = 26.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(card.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LinkView(card: LinkCard) {
    val go = open(card.link)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable { go() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↗", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(card.title, style = MaterialTheme.typography.titleSmall)
                Text(card.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
