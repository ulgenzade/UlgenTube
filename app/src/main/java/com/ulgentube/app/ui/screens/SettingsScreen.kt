package com.ulgentube.app.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ulgentube.app.BuildConfig
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.ThemeMode
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient

private val VIDEO_QUALITY_OPTIONS = listOf("2160p", "1080p", "720p", "480p")
private val AUDIO_FORMAT_OPTIONS = listOf("mp3", "m4a", "opus", "flac", "wav")

@Composable
fun SettingsScreen(
    settings: AppSettings,
    ytDlpUpdateMessage: String?,
    isUpdatingYtDlp: Boolean,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetDefaultVideoQuality: (String) -> Unit,
    onSetDefaultAudioFormat: (String) -> Unit,
    onSetUseAria2c: (Boolean) -> Unit,
    onSetEmbedThumbnail: (Boolean) -> Unit,
    onSetAutoDetectClipboard: (Boolean) -> Unit,
    onUpdateYtDlp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "settings_title") {
            Column {
                Text(
                    text = "Ayarlar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Görünüm, indirme kalitesi ve motor tercihleri",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Görünüm Kartı
        item(key = "appearance_card") {
            SettingsGroupCard(title = "Görünüm") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Tema Modu",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    SegmentedOptionRow(
                        options = listOf(
                            ThemeMode.SYSTEM to "Sistem",
                            ThemeMode.DARK to "Koyu",
                            ThemeMode.LIGHT to "Açık"
                        ),
                        selected = settings.themeMode,
                        onSelect = onSetThemeMode
                    )

                    if (supportsDynamicColor) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Palette,
                            title = "Dinamik Renk (Material You)",
                            subtitle = "Android 12+ duvar kağıdı renk paletini uygular",
                            checked = settings.dynamicColor,
                            onCheckedChange = onSetDynamicColor
                        )
                    }
                }
            }
        }

        // Varsayılan Kalite ve Formatlar
        item(key = "quality_card") {
            SettingsGroupCard(title = "Varsayılan Kalite") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Video Kalitesi",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SegmentedOptionRow(
                            options = VIDEO_QUALITY_OPTIONS.map { it to it },
                            selected = settings.defaultVideoQuality,
                            onSelect = onSetDefaultVideoQuality
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Ses Formatı",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowOptionRow(
                            options = AUDIO_FORMAT_OPTIONS.map { it to it.uppercase() },
                            selected = settings.defaultAudioFormat,
                            onSelect = onSetDefaultAudioFormat
                        )
                    }
                }
            }
        }

        // İndirme Motoru Tercihleri
        item(key = "engine_prefs_card") {
            SettingsGroupCard(title = "İndirme Tercihleri") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Bolt,
                        title = "aria2c Hızlandırıcı",
                        subtitle = "Çoklu bağlantı ile daha yüksek indirme hızı",
                        checked = settings.useAria2c,
                        onCheckedChange = onSetUseAria2c
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Image,
                        title = "Kapak Görseli Gömme",
                        subtitle = "Ses ve video dosyalarına kapak ve meta veri ekler",
                        checked = settings.embedThumbnail,
                        onCheckedChange = onSetEmbedThumbnail
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        icon = Icons.Rounded.ContentPaste,
                        title = "Otomatik Pano Algılama",
                        subtitle = "Uygulama açıldığında panodaki linkleri tanır",
                        checked = settings.autoDetectClipboard,
                        onCheckedChange = onSetAutoDetectClipboard
                    )
                }
            }
        }

        // yt-dlp Güncelleme Kartı
        item(key = "ytdlp_update_card") {
            Surface(
                onClick = { if (!isUpdatingYtDlp) onUpdateYtDlp() },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(UlgenPrimaryGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isUpdatingYtDlp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdateAlt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "yt-dlp Motorunu Güncelle",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = ytDlpUpdateMessage
                                ?: "Yeni site destekleri ve hız iyileştirmeleri için güncelleyin",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Hakkında Kartı
        item(key = "about_card") {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "UlgenTube v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = ElectricPurple,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Reklamsız & Ücretsiz • ${context.packageName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "settings_bottom_spacer") {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FlowOptionRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (isSelected) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            ) {
                Box(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier.background(UlgenPrimaryGradient, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedOptionRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .then(
                            if (isSelected) {
                                Modifier.background(UlgenPrimaryGradient)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
