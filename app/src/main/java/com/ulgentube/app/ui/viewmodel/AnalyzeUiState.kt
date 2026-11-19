package com.ulgentube.app.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.ulgentube.app.domain.model.ExtractedMedia
import com.ulgentube.app.domain.model.PlatformType

/**
 * Bağlantı analiz sürecinin UI durum modeli.
 */
@Immutable
sealed interface AnalyzeUiState {
    data object Idle : AnalyzeUiState

    data class Loading(
        val url: String,
        val platform: PlatformType
    ) : AnalyzeUiState

    data class Success(
        val media: ExtractedMedia
    ) : AnalyzeUiState

    data class Error(
        val message: String,
        val originalInput: String
    ) : AnalyzeUiState
}
