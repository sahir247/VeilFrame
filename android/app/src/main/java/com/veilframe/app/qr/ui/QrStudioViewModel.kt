package com.veilframe.app.qr.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veilframe.app.qr.*
import com.veilframe.app.qr.exporter.QrExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrStudioViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val content: String = "https://veilframe.app",
        val bitmap: Bitmap? = null,
        val style: QrStyle = QrStyle.BASIC,
        val foreground: Int = Color.BLACK,
        val background: Int = Color.WHITE,
        val logo: Bitmap? = null,
        val backgroundImage: Bitmap? = null,
        val isLoading: Boolean = false,
        val saveResult: String? = null,   // transient message after save/share
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun updateContent(text: String) {
        _state.value = _state.value.copy(content = text)
        regenerate()
    }

    fun updateStyle(style: QrStyle) {
        _state.value = _state.value.copy(style = style)
        regenerate()
    }

    fun updateForeground(color: Int) {
        _state.value = _state.value.copy(foreground = color)
        regenerate()
    }

    fun updateBackground(color: Int) {
        _state.value = _state.value.copy(background = color)
        regenerate()
    }

    fun updateLogo(bmp: Bitmap?) {
        _state.value = _state.value.copy(logo = bmp)
        regenerate()
    }

    fun updateBackgroundImage(bmp: Bitmap?) {
        _state.value = _state.value.copy(backgroundImage = bmp)
        regenerate()
    }

    fun regenerate() {
        val s = _state.value
        if (s.content.isBlank()) return
        _state.value = s.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = try {
                val params = QrStyleParams(
                    outputSize = 512,
                    style = s.style,
                    foreground = s.foreground,
                    background = s.background,
                    logo = s.logo,
                    backgroundImage = s.backgroundImage
                )
                QrGenerator.generate(s.content, params)
            } catch (e: Exception) {
                null
            }
            _state.value = _state.value.copy(
                bitmap = bitmap,
                isLoading = false,
                errorMessage = if (bitmap == null) "Failed to generate QR code" else null
            )
        }
    }

    fun saveToGallery() {
        val bmp = _state.value.bitmap ?: return
        viewModelScope.launch {
            val uri = QrExporter.saveToGallery(getApplication(), bmp)
            _state.value = _state.value.copy(
                saveResult = if (uri != null) "Saved to Gallery" else "Save failed"
            )
        }
    }

    fun share() {
        val bmp = _state.value.bitmap ?: return
        viewModelScope.launch {
            QrExporter.share(getApplication(), bmp)
        }
    }

    fun clearSaveResult() {
        _state.value = _state.value.copy(saveResult = null)
    }
}
