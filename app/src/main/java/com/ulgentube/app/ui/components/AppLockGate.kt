package com.ulgentube.app.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple
import com.ulgentube.app.ui.theme.UlgenPrimaryGradient
import com.ulgentube.app.ui.theme.UlgenSubtleGradient
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Uygulama kilidi ve Özel Kasa PIN karma (SHA-256 + rastgele tuz) yardımcısı.
 * [hashPin] ve [verifyPin] Android bağımlılığı olmadan birim testlerde doğrudan kullanılabilir.
 */
object PinHashUtil {

    private const val PREFS_NAME = "ulgentube_lock_prefs"
    private const val KEY_PIN_HASH = "pin_hash"

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPin(pin: String, salt: String = generateSalt()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$salt:$pin".toByteArray(Charsets.UTF_8)
        val hashed = digest.digest(input)
        val hex = hashed.joinToString("") { "%02x".format(it) }
        return "$salt:$hex"
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return false
        val expected = hashPin(pin, parts[0])
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun hasPinConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()
    }

    fun savePin(context: Context, pin: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
    }

    fun verifyStoredPin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return verifyPin(pin, stored)
    }

    fun clearPin(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PIN_HASH).apply()
    }
}

/**
 * Tam ekran PIN ve Cihaz Kilidi / Biyometri doğrulama katmanı.
 */
@Composable
fun AppLockOverlay(
    title: String = "UlgenTube Kilitli",
    onUnlocked: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var pinConfigured by remember { mutableStateOf(PinHashUtil.hasPinConfigured(context)) }
    var enteredPin by remember { mutableStateOf("") }
    var firstSetupPin by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val keyguardManager = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    val isDeviceSecure = remember(keyguardManager) {
        keyguardManager?.isDeviceSecure == true
    }

    val deviceCredentialLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            errorMessage = null
            onUnlocked()
        } else {
            errorMessage = "Cihaz kilidi doğrulaması iptal edildi."
        }
    }

    fun handleDigit(digit: String) {
        if (enteredPin.length >= 4) return
        errorMessage = null
        val updated = enteredPin + digit
        enteredPin = updated

        if (updated.length == 4) {
            if (!pinConfigured) {
                val first = firstSetupPin
                if (first == null) {
                    firstSetupPin = updated
                    enteredPin = ""
                } else if (first == updated) {
                    PinHashUtil.savePin(context, updated)
                    pinConfigured = true
                    enteredPin = ""
                    firstSetupPin = null
                    onUnlocked()
                } else {
                    errorMessage = "PIN kodları eşleşmedi, tekrar deneyin."
                    firstSetupPin = null
                    enteredPin = ""
                }
            } else {
                if (PinHashUtil.verifyStoredPin(context, updated)) {
                    enteredPin = ""
                    onUnlocked()
                } else {
                    errorMessage = "Hatalı PIN kodu. Tekrar deneyin."
                    enteredPin = ""
                }
            }
        }
    }

    val subtitleText = when {
        !pinConfigured && firstSetupPin == null -> "Devam etmek için 4 haneli yeni bir PIN belirleyin"
        !pinConfigured && firstSetupPin != null -> "Onaylamak için PIN kodunuzu tekrar girin"
        else -> "Devam etmek için 4 haneli PIN kodunuzu girin"
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}
            ),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Üst Kilit Logosu ve Başlık
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(UlgenPrimaryGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 4 Haneli PIN Noktaları
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val filled = index < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .then(
                                    if (filled) {
                                        Modifier.background(UlgenPrimaryGradient)
                                    } else {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    }
                                )
                        )
                    }
                }

                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Sayısal PIN Tuş Takımı
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9")
                )
                rows.forEach { rowDigits ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowDigits.forEach { digit ->
                            PinDigitKey(
                                label = digit,
                                onClick = { handleDigit(digit) }
                            )
                        }
                    }
                }

                // Alt Satır: İptal / 0 / Sil
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(68.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (onCancel != null) {
                            TextButton(onClick = onCancel) {
                                Text(
                                    text = "İptal",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    PinDigitKey(
                        label = "0",
                        onClick = { handleDigit("0") }
                    )

                    Surface(
                        onClick = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Backspace,
                                contentDescription = "Sil",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Biyometri / Cihaz Kilidi ile Aç Butonu
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isDeviceSecure) {
                    OutlinedButton(
                        onClick = {
                            @Suppress("DEPRECATION")
                            val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
                                title,
                                "Devam etmek için cihaz kilidinizi doğrulayın"
                            )
                            if (intent != null) {
                                deviceCredentialLauncher.launch(intent)
                            } else {
                                errorMessage = "Cihaz kilidi doğrulaması başlatılamadı."
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            tint = CoralPink,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Biyometri / Cihaz Kilidi ile Aç",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            "Cihaz ekran kilidi tanımlı değil, PIN ile açabilirsiniz."
                        } else {
                            "PIN kodu ile doğrulama yapabilirsiniz."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDigitKey(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.size(68.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Ayarlar ekranı için 4 haneli PIN belirleme / değiştirme diyaloğu.
 */
@Composable
fun PinSetupDialog(
    onPinSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val alreadyHasPin = remember { PinHashUtil.hasPinConfigured(context) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(UlgenSubtleGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = ElectricPurple,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = if (alreadyHasPin) "PIN Kodunu Değiştir" else "4 Haneli PIN Belirle",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Uygulama kilidi ve Özel Kasa erişimi için 4 rakamdan oluşan bir PIN girin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(4)
                        pin = digits
                        error = null
                    },
                    label = { Text("Yeni PIN (4 rakam)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(4)
                        confirmPin = digits
                        error = null
                    },
                    label = { Text("PIN Tekrar") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        pin.length != 4 -> error = "PIN tam olarak 4 rakam olmalıdır."
                        pin != confirmPin -> error = "PIN kodları eşleşmiyor."
                        else -> {
                            PinHashUtil.savePin(context, pin)
                            onPinSaved()
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("Kaydet", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}
