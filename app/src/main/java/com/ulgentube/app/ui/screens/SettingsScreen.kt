package com.ulgentube.app.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ulgentube.app.BuildConfig
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.EqualizerPreset
import com.ulgentube.app.domain.model.ThemeMode
import com.ulgentube.app.ui.components.PinHashUtil
import com.ulgentube.app.ui.components.PinSetupDialog
import com.ulgentube.app.ui.components.UlgenSwitch
import com.ulgentube.app.ui.components.bounceClick
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.EmeraldSuccess
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.util.FormatUtils

private val VIDEO_QUALITY_OPTIONS = listOf("2160p", "1080p", "720p", "480p")
private val AUDIO_FORMAT_OPTIONS = listOf("mp3", "m4a", "opus", "flac", "wav")
private val PARALLEL_CONNECTION_OPTIONS = listOf(2, 4, 8, 16)
private val MAX_CONCURRENT_OPTIONS = listOf(1, 2, 3, 4)
private val SPEED_LIMIT_OPTIONS = listOf(
    0 to "Sınırsız",
    500 to "500 KB/sn",
    1024 to "1 MB/sn",
    2048 to "2 MB/sn",
    5120 to "5 MB/sn"
)
private val FILE_NAME_TEMPLATE_OPTIONS = listOf(
    "{title}" to "Sadece Başlık",
    "{author} - {title}" to "Kanal - Başlık",
    "{date}_{title}" to "Tarih_Başlık"
)

private enum class SettingsPage(
    val title: String,
    val subtitle: String
) {
    APPEARANCE("Görünüm & Tema", "Tema modu, dinamik renkler"),
    DOWNLOADS("İndirme & Kalite", "Varsayılan kalite, klasör düzeni, hız ve zamanlama"),
    ENGINE_ACCOUNTS("Hesaplar, Çerez & Motor", "Oturum girişi, cookies.txt, paralel motor ve yt-dlp"),
    AUDIO_PRIVACY("Ses, Oynatıcı & Gizlilik", "PoToken, şarkı sözleri, ekolayzır ve uygulama kilidi"),
    STORAGE_SYSTEM("Depolama, Yedek & Teşhis", "Önbellek, JSON yedek, hata günlüğü ve hakkında")
}

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
    onSetParallelDownloadEnabled: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetMaxConcurrentDownloads: (Int) -> Unit,
    onImportCookies: (Uri) -> Unit,
    onSyncWebViewCookies: () -> Unit = {},
    onClearCookies: () -> Unit,
    onUpdateYtDlp: () -> Unit,
    tempCacheBytes: Long = 0L,
    isRefreshingPoToken: Boolean = false,
    onSetMaxDownloadSpeedKbps: (Int) -> Unit = {},
    onSetScheduledDownloadEnabled: (Boolean) -> Unit = {},
    onSetScheduleWindowHours: (Int, Int) -> Unit = { _, _ -> },
    onSetPoTokenEnabled: (Boolean) -> Unit = {},
    onRefreshPoToken: () -> Unit = {},
    onSetAutoFetchLyrics: (Boolean) -> Unit = {},
    onSetEqualizerPreset: (EqualizerPreset) -> Unit = {},
    onSetAppLockEnabled: (Boolean) -> Unit = {},
    onClearTempCache: () -> Unit = {},
    onExportBackup: (Uri) -> Unit = {},
    onImportBackup: (Uri) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var showBrowserLoginDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var enableLockAfterPinSetup by remember { mutableStateOf(false) }

    if (showBrowserLoginDialog) {
        InAppCookieLoginDialog(
            onSaveAndClose = {
                showBrowserLoginDialog = false
                onSyncWebViewCookies()
            },
            onDismiss = { showBrowserLoginDialog = false }
        )
    }

    if (showPinSetupDialog) {
        PinSetupDialog(
            onPinSaved = {
                showPinSetupDialog = false
                if (enableLockAfterPinSetup) {
                    enableLockAfterPinSetup = false
                    onSetAppLockEnabled(true)
                }
            },
            onDismiss = {
                showPinSetupDialog = false
                enableLockAfterPinSetup = false
            }
        )
    }

    val cookieFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCookies(uri)
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            onExportBackup(uri)
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportBackup(uri)
        }
    }

    var activePage by remember { mutableStateOf<SettingsPage?>(null) }

    androidx.activity.compose.BackHandler(enabled = activePage != null) {
        activePage = null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (activePage == null) {
            item(key = "settings_hub_title") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Ayarlar",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Kategorilerden birini seçerek ayarları düzenleyin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "settings_hub_categories") {
                val diagCount = remember {
                    com.ulgentube.app.util.DiagnosticLogManager.getEntryCount(context)
                }
                val categories = listOf(
                    Triple(SettingsPage.APPEARANCE, Icons.Rounded.Palette, when (settings.themeMode) {
                        ThemeMode.DARK -> "Koyu"
                        ThemeMode.LIGHT -> "Açık"
                        ThemeMode.SYSTEM -> "Sistem"
                    }),
                    Triple(
                        SettingsPage.DOWNLOADS,
                        Icons.Rounded.Download,
                        "${settings.defaultVideoQuality} • ${settings.defaultAudioFormat.uppercase()}"
                    ),
                    Triple(
                        SettingsPage.ENGINE_ACCOUNTS,
                        Icons.Rounded.Cookie,
                        if (hasCookies && settings.cookiesEnabled) "Çerez Aktif" else "Oturum / Motor"
                    ),
                    Triple(
                        SettingsPage.AUDIO_PRIVACY,
                        Icons.Rounded.Lock,
                        if (settings.appLockEnabled) "Kilit Açık" else settings.equalizerPreset.displayName
                    ),
                    Triple(
                        SettingsPage.STORAGE_SYSTEM,
                        Icons.Rounded.Backup,
                        if (diagCount > 0) "$diagCount hata kaydı" else FormatUtils.formatBytes(tempCacheBytes).ifBlank { "Temiz" }
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    categories.forEach { (page, icon, badge) ->
                        Surface(
                            onClick = { activePage = page },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = page.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = page.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val currentPage = activePage!!
            item(key = "settings_subpage_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { activePage = null },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Geri",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentPage.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currentPage.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Görünüm Kartı
        if (activePage == SettingsPage.APPEARANCE) {
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

        }

        // Varsayılan Kalite ve Formatlar
        if (activePage == SettingsPage.DOWNLOADS) {
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

        }

        // Özel İçerik & Çerezler Kartı
        if (activePage == SettingsPage.ENGINE_ACCOUNTS) {
        item(key = "cookies_card") {
            SettingsGroupCard(
                title = "Özel İçerik & Çerezler",
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
                                color = statusColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Cookie,
                        title = "Çerez Desteği (Cookies)",
                        subtitle = "Yaş sınırlı veya oturum gerektiren içerikler için oturum çerezlerini kullanır",
                        checked = settings.cookiesEnabled,
                        onCheckedChange = onSetCookiesEnabled
                    )

                    // Snaptube tarzı uygulama içi tek tıkla tarayıcı girişi
                    Button(
                        onClick = { showBrowserLoginDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .bounceClick(),
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Cookie,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Oturum Aç (Otomatik cookies.txt Oluştur)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Text(
                        text = "Elinizde cookies.txt dosyası olmasına gerek yok. Yukarıdan Instagram, YouTube, X, TikTok veya Reddit hesabınıza giriş yaptığınızda UlgenTube çerez dosyasını cihazınızda otomatik oluşturur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cookieFilePicker.launch(arrayOf("text/plain", "*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .bounceClick(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Hazır cookies.txt Yükle",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = onClearCookies,
                            enabled = hasCookies,
                            modifier = Modifier
                                .height(42.dp)
                                .bounceClick(),
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
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
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
                        icon = Icons.Rounded.Speed,
                        title = "Paralel Bağlantı İndirme",
                        subtitle = "Dosyaları çoklu parçalar halinde aynı anda indirerek hızı artırır",
                        checked = settings.parallelDownloadEnabled,
                        onCheckedChange = onSetParallelDownloadEnabled
                    )
                    AnimatedVisibility(visible = settings.parallelDownloadEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Paralel Bağlantı Sayısı",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            SegmentedOptionRow(
                                options = PARALLEL_CONNECTION_OPTIONS.map { it to "${it}x" },
                                selected = settings.parallelConnectionCount,
                                onSelect = onSetParallelConnectionCount
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Aynı Anda İndirme Sayısı",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SegmentedOptionRow(
                            options = MAX_CONCURRENT_OPTIONS.map { it to "$it" },
                            selected = settings.maxConcurrentDownloads,
                            onSelect = onSetMaxConcurrentDownloads
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

        }

        // Hız Limiti & Zamanlama Kartı
        if (activePage == SettingsPage.DOWNLOADS) {
        item(key = "speed_schedule_card") {
            SettingsGroupCard(title = "Hız Limiti & Zamanlama") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Maksimum İndirme Hızı",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowOptionRow(
                            options = SPEED_LIMIT_OPTIONS,
                            selected = settings.maxDownloadSpeedKbps,
                            onSelect = onSetMaxDownloadSpeedKbps
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsSwitchRow(
                        icon = Icons.Rounded.Schedule,
                        title = "Zamanlanmış İndirme Penceresi",
                        subtitle = "İndirmeleri yalnızca belirlenen saat aralığında çalıştırır",
                        checked = settings.scheduledDownloadEnabled,
                        onCheckedChange = onSetScheduledDownloadEnabled
                    )

                    AnimatedVisibility(visible = settings.scheduledDownloadEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HourStepperChip(
                                label = "Başlangıç",
                                hour = settings.scheduleStartHour,
                                onDecrement = {
                                    val next = (settings.scheduleStartHour + 23) % 24
                                    onSetScheduleWindowHours(next, settings.scheduleEndHour)
                                },
                                onIncrement = {
                                    val next = (settings.scheduleStartHour + 1) % 24
                                    onSetScheduleWindowHours(next, settings.scheduleEndHour)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            HourStepperChip(
                                label = "Bitiş",
                                hour = settings.scheduleEndHour,
                                onDecrement = {
                                    val next = (settings.scheduleEndHour + 23) % 24
                                    onSetScheduleWindowHours(settings.scheduleStartHour, next)
                                },
                                onIncrement = {
                                    val next = (settings.scheduleEndHour + 1) % 24
                                    onSetScheduleWindowHours(settings.scheduleStartHour, next)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        }

        // Gelişmiş YouTube & Ses Kartı
        if (activePage == SettingsPage.AUDIO_PRIVACY) {
        item(key = "advanced_youtube_audio_card") {
            SettingsGroupCard(
                title = "Gelişmiş YouTube & Ses",
                trailingBadge = {
                    val hasToken = !settings.cachedPoToken.isNullOrBlank()
                    val statusColor = if (hasToken) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = if (hasToken) "PoToken Hazır" else "Token Yok",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Key,
                        title = "YouTube PoToken Koruması",
                        subtitle = "403/Bot doğrulaması hatalarını azaltmak için PoToken kullanır",
                        checked = settings.poTokenEnabled,
                        onCheckedChange = onSetPoTokenEnabled
                    )

                    OutlinedButton(
                        onClick = { if (!isRefreshingPoToken) onRefreshPoToken() },
                        enabled = !isRefreshingPoToken,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isRefreshingPoToken) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ElectricPurple
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRefreshingPoToken) "PoToken Yenileniyor..." else "PoToken Yenile",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsSwitchRow(
                        icon = Icons.Rounded.Lyrics,
                        title = "Otomatik Şarkı Sözü Getir",
                        subtitle = "Müzik çalarken LRCLIB üzerinden şarkı sözlerini arar",
                        checked = settings.autoFetchLyrics,
                        onCheckedChange = onSetAutoFetchLyrics
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Equalizer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Varsayılan Ekolayzer Profili",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        FlowOptionRow(
                            options = EqualizerPreset.entries.map { it to it.displayName },
                            selected = settings.equalizerPreset,
                            onSelect = onSetEqualizerPreset
                        )
                    }
                }
            }
        }

        // Gizlilik ve Kilit Kartı
        item(key = "privacy_lock_card") {
            SettingsGroupCard(title = "Gizlilik ve Kilit") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Lock,
                        title = "Uygulama Kilidi (PIN / Biyometri)",
                        subtitle = "Uygulama açılışında PIN veya cihaz kilidi doğrulaması ister",
                        checked = settings.appLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !PinHashUtil.hasPinConfigured(context)) {
                                enableLockAfterPinSetup = true
                                showPinSetupDialog = true
                            } else {
                                onSetAppLockEnabled(enabled)
                            }
                        }
                    )

                    OutlinedButton(
                        onClick = {
                            enableLockAfterPinSetup = false
                            showPinSetupDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (PinHashUtil.hasPinConfigured(context)) {
                                "PIN Kodunu Değiştir"
                            } else {
                                "PIN Kodu Belirle"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        }

        // Depolama & Yedekleme Kartı
        if (activePage == SettingsPage.STORAGE_SYSTEM) {
        item(key = "storage_backup_card") {
            SettingsGroupCard(title = "Depolama & Yedekleme") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Geçici Önbellek",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = FormatUtils.formatBytes(tempCacheBytes).ifBlank { "0 B" } + " kullanılıyor",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onClearTempCache,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Temizle",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportBackupLauncher.launch("ulgentube_backup.json") },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Backup,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Dışa Aktar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "İçe Aktar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        }

        // yt-dlp Güncelleme Kartı
        if (activePage == SettingsPage.ENGINE_ACCOUNTS) {
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

        }

        // Hakkında Kartı
        if (activePage == null || activePage == SettingsPage.STORAGE_SYSTEM) {
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

        }

        // Hata & Teşhis Günlüğü (Tek tıkla kopyala veya .txt olarak paylaş)
        if (activePage == SettingsPage.STORAGE_SYSTEM) {
        item(key = "section_diagnostics") {
            var logEntryCount by remember {
                mutableStateOf(com.ulgentube.app.util.DiagnosticLogManager.getEntryCount(context))
            }
            var showLogPreview by remember { mutableStateOf(false) }
            var previewText by remember { mutableStateOf("") }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hata & Teşhis Günlüğü",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (logEntryCount > 0) {
                                    "$logEntryCount kayıtlı teşhis olayı var (çerez ve tokenlar gizlenir)."
                                } else {
                                    "İndirme/çözümleme hataları otomatik kaydedilir; kopyalayıp veya .txt paylaşabilirsin."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val copied = com.ulgentube.app.util.DiagnosticLogManager.copyReportToClipboard(context)
                            android.widget.Toast.makeText(
                                context,
                                if (copied) "Hata raporu panoya kopyalandı" else "Rapor kopyalanamadı",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Raporu Kopyala",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val shared = com.ulgentube.app.util.DiagnosticLogManager.shareReportAsFile(context)
                            if (shared.isFailure) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Dosya paylaşılamadı: ${shared.exceptionOrNull()?.message}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Dosya Paylaş (.txt)",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showLogPreview = !showLogPreview
                            if (showLogPreview) {
                                previewText = com.ulgentube.app.util.DiagnosticLogManager.readRecentReport(context, 4000)
                                logEntryCount = com.ulgentube.app.util.DiagnosticLogManager.getEntryCount(context)
                            }
                        }
                    ) {
                        Text(if (showLogPreview) "Önizlemeyi Gizle" else "Son Hataları Gör")
                    }

                    androidx.compose.material3.TextButton(
                        onClick = {
                            com.ulgentube.app.util.DiagnosticLogManager.clear(context)
                            logEntryCount = 0
                            previewText = ""
                            showLogPreview = false
                            android.widget.Toast.makeText(
                                context,
                                "Teşhis günlüğü temizlendi",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text("Temizle", color = MaterialTheme.colorScheme.error)
                    }
                }

                AnimatedVisibility(visible = showLogPreview) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = previewText.ifBlank { "(Henüz kayıt yok)" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
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
private fun HourStepperChip(
    label: String,
    hour: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatted = String.format(java.util.Locale.US, "%02d:00", hour.coerceIn(0, 23))
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = onDecrement,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("-", fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = formatted,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    onClick = onIncrement,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+", fontWeight = FontWeight.Bold)
                    }
                }
            }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
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
        UlgenSwitch(
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
                modifier = Modifier.bounceClick(),
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
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
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
                        .bounceClick()
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
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Tam ekran uygulama içi WebView hesap giriş ekranı:
 * Kullanıcının Instagram, YouTube, X, TikTok veya Reddit hesabına doğrudan giriş yapıp
 * çerezleri otomatik olarak `cookies.txt` biçimine kaydetmesini sağlar.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppCookieLoginDialog(
    onSaveAndClose: () -> Unit,
    onDismiss: () -> Unit,
    initialPlatform: com.ulgentube.app.domain.model.PlatformType? = null
) {
    val sites = remember {
        listOf(
            "Instagram" to "https://www.instagram.com/",
            "YouTube" to "https://accounts.google.com/ServiceLogin?service=youtube",
            "X" to "https://x.com/login",
            "TikTok" to "https://www.tiktok.com/login",
            "Reddit" to "https://www.reddit.com/login/"
        )
    }
    val defaultIndex = when (initialPlatform) {
        com.ulgentube.app.domain.model.PlatformType.YOUTUBE -> 1
        com.ulgentube.app.domain.model.PlatformType.TWITTER_X -> 2
        com.ulgentube.app.domain.model.PlatformType.TIKTOK -> 3
        com.ulgentube.app.domain.model.PlatformType.REDDIT -> 4
        else -> 0
    }
    var selectedSiteIndex by androidx.compose.runtime.remember(initialPlatform) {
        androidx.compose.runtime.mutableIntStateOf(defaultIndex)
    }
    var targetUrl by androidx.compose.runtime.remember(initialPlatform) {
        androidx.compose.runtime.mutableStateOf(sites[defaultIndex].second)
    }
    var activeHost by androidx.compose.runtime.remember(initialPlatform) {
        androidx.compose.runtime.mutableStateOf(sites[defaultIndex].first)
    }
    var loadProgress by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var canGoBack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var webViewRef by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching {
                android.webkit.CookieManager.getInstance().flush()
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1) Kompakt, tam ekran uyumlu üst araç çubuğu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Kapat",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (canGoBack) {
                        androidx.compose.material3.IconButton(
                            onClick = { webViewRef?.goBack() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Geri",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hesap Girişi",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$activeHost • Giriş yapıp Kaydet'e basın",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    androidx.compose.material3.IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Yenile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Button(
                        onClick = {
                            runCatching { android.webkit.CookieManager.getInstance().flush() }
                            onSaveAndClose()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Kaydet",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // 2) Platform geçiş sekmeleri (yatay kaydırılabilir, kesilmez)
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(bottom = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sites.size) { idx ->
                        val (name, url) = sites[idx]
                        val selected = selectedSiteIndex == idx
                        Surface(
                            onClick = {
                                selectedSiteIndex = idx
                                targetUrl = url
                                activeHost = name
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                // 3) Yükleme ilerleme çubuğu
                if (loadProgress in 1..99) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                } else {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                // 4) Kenardan kenara, kırpılmamış (no-clip) gerçek mobil WebView
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true

                            val cm = android.webkit.CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.textZoom = 100
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false

                            // Cihazın gerçek Chrome WebView UA'sından yalnızca "; wv" ve "Version/x.y"
                            // token'larını temizle (Sec-CH-UA Client Hints ile %100 uyumlu kalır;
                            // Instagram, X ve TikTok siyah ekrana veya masaüstü görünümüne düşmez).
                            val cleanUa = settings.userAgentString
                                .replace("; wv)", ")")
                                .replace("; wv", "")
                                .replace(Regex("""\s*Version/\d+\.\d+\s*"""), " ")
                                .trim()
                            settings.userAgentString = cleanUa

                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: android.webkit.WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): Boolean {
                                    val scheme = request?.url?.scheme?.lowercase(java.util.Locale.ROOT)
                                    // Harici uygulama (intent://, instagram://, twitter://, snssdk://) geçişlerini engelle
                                    return scheme != "http" && scheme != "https"
                                }

                                override fun onPageStarted(
                                    view: android.webkit.WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    canGoBack = view?.canGoBack() == true
                                    val host = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }
                                        .getOrNull()
                                    if (!host.isNullOrBlank()) {
                                        activeHost = host
                                    }
                                }

                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    canGoBack = view?.canGoBack() == true
                                    runCatching { android.webkit.CookieManager.getInstance().flush() }
                                }
                            }
                            tag = targetUrl
                            webViewRef = this
                            loadUrl(targetUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.tag != targetUrl) {
                            webView.tag = targetUrl
                            loadProgress = 10
                            webView.stopLoading()
                            webView.loadUrl(targetUrl)
                        }
                    }
                )
            }
        }
    }
}
