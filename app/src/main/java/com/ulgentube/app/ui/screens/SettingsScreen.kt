package com.ulgentube.app.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient

private val VIDEO_QUALITY_OPTIONS = listOf("2160p", "1080p", "720p", "480p")
private val AUDIO_FORMAT_OPTIONS = listOf("mp3", "m4a", "opus", "flac", "wav")
private val FILE_NAME_TEMPLATE_OPTIONS = listOf(
    "{title}" to "Sadece Başlık",
    "{author} - {title}" to "Kanal - Başlık",
    "{date}_{title}" to "Tarih_Başlık"
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    ytDlpUpdateMessage: String?,
    isUpdatingYtDlp: Boolean,
    hasCookies: Boolean,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetDefaultVideoQuality: (String) -> Unit,
    onSetDefaultAudioFormat: (String) -> Unit,
    onSetUseAria2c: (Boolean) -> Unit,
    onSetEmbedThumbnail: (Boolean) -> Unit,
    onSetAutoDetectClipboard: (Boolean) -> Unit,
    onSetSponsorBlockEnabled: (Boolean) -> Unit,
    onSetEmbedChapters: (Boolean) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetZeroFrictionShareMode: (Boolean) -> Unit,
    onSetPlatformSubfolders: (Boolean) -> Unit,
    onSetFileNameTemplate: (String) -> Unit,
    onSetCookiesEnabled: (Boolean) -> Unit,
    onImportCookies: (Uri) -> Unit,
    onClearCookies: () -> Unit,
    onUpdateYtDlp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val cookieFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCookies(uri)
        }
    }

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
                    text = "Görünüm, akıllı indirme, klasör düzeni ve motor tercihleri",
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

        // Akıllı İndirme Kartı
        item(key = "smart_download_card") {
            SettingsGroupCard(title = "Akıllı İndirme") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.FlashOn,
                        title = "Zero-Friction Anında Sessiz İndir",
                        subtitle = "Paylaşımda 2.5 sn geri sayımlı mini kapsül ile otomatik indirir",
                        checked = settings.zeroFrictionShareMode,
                        onCheckedChange = onSetZeroFrictionShareMode
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Wifi,
                        title = "Yalnızca Wi-Fi ile İndir",
                        subtitle = "Mobil veri harcamasını önlemek için Wi-Fi bağlantısını bekler",
                        checked = settings.wifiOnly,
                        onCheckedChange = onSetWifiOnly
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        icon = Icons.Rounded.FastForward,
                        title = "SponsorBlock (Sponsor/Intro Kes)",
                        subtitle = "Videolardaki sponsor ve tanıtım bölümlerini otomatik atlar",
                        checked = settings.sponsorBlockEnabled,
                        onCheckedChange = onSetSponsorBlockEnabled
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Bookmarks,
                        title = "Bölüm İşaretlerini (Chapters) Göm",
                        subtitle = "Video içi bölüm başlıklarını dosyaya meta veri olarak ekler",
                        checked = settings.embedChapters,
                        onCheckedChange = onSetEmbedChapters
                    )
                }
            }
        }

        // Dosya & Klasör Düzeni Kartı
        item(key = "file_org_card") {
            SettingsGroupCard(title = "Dosya & Klasör Düzeni") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.FolderCopy,
                        title = "Platform Bazlı Alt Klasörler",
                        subtitle = "Movies/UlgenTube/YouTube/ gibi platform klasörlerine ayırır",
                        checked = settings.platformSubfolders,
                        onCheckedChange = onSetPlatformSubfolders
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Dosya Adı Şablonu",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowOptionRow(
                            options = FILE_NAME_TEMPLATE_OPTIONS,
                            selected = settings.fileNameTemplate,
                            onSelect = onSetFileNameTemplate
                        )
                    }
                }
            }
        }

        // Özel İçerik & Çerezler (Cookies) Kartı
        item(key = "cookies_card") {
            SettingsGroupCard(
                title = "Özel İçerik & Çerezler (Cookies)",
                trailingBadge = {
                    val statusColor = if (hasCookies) {
                        EmeraldSuccess
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = if (hasCookies) "Yüklü" else "Yüklü Değil",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Cookie,
                        title = "Çerez Desteği (Cookies)",
                        subtitle = "Yaş sınırlı veya oturum gerektiren içerikler için cookies.txt kullanır",
                        checked = settings.cookiesEnabled,
                        onCheckedChange = onSetCookiesEnabled
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { cookieFilePicker.launch(arrayOf("text/plain", "*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(UlgenPrimaryGradient, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.UploadFile,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "cookies.txt İçe Aktar",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onClearCookies,
                            enabled = hasCookies,
                            modifier = Modifier.height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Temizle",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
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
    trailingBadge: (@Composable () -> Unit)? = null,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                trailingBadge?.invoke()
            }
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
