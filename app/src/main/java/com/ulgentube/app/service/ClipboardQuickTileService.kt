package com.ulgentube.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.ulgentube.app.SharePopupActivity

/**
 * Android Hızlı Ayarlar (Quick Settings) panelinden tek dokunuşla panodaki bağlantıyı
 * analiz edip indirme penceresini açan kutucuk servisi.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ClipboardQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "UlgenTube İndir"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SharePopupActivity::class.java).apply {
            action = ACTION_QUICK_TILE_CLIPBOARD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_QUICK_TILE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val ACTION_QUICK_TILE_CLIPBOARD = "com.ulgentube.app.action.QUICK_TILE_CLIPBOARD"
        private const val REQUEST_CODE_QUICK_TILE = 3001
    }
}
