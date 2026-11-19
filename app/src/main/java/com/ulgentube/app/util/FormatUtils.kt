package com.ulgentube.app.util

import java.util.Locale

/**
 * Boyut ve süre formatlama yardımcıları.
 */
object FormatUtils {

    /**
     * Bayt değerini okunabilir metne çevirir (ör. `12.4 MB`, `1.25 GB`).
     */
    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    /**
     * Saniye cinsinden süreyi `MM:SS` veya `SS:DD:SN` formatına dönüştürür.
     */
    fun formatDuration(seconds: Long?): String {
        if (seconds == null || seconds <= 0L) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }
}
