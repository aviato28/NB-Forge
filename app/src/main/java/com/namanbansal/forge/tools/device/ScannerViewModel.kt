package com.namanbansal.forge.tools.device

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.device.BarcodeClassifier
import com.namanbansal.forge.core.device.ScanContent
import com.namanbansal.forge.core.device.ScanEntry
import com.namanbansal.forge.core.device.ScanHistoryStore
import com.namanbansal.forge.core.device.ScannedCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** State of the QR / barcode scanner: the current result and the persisted history. Survives rotation. */
class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ScanHistoryStore(File(app.filesDir, "scan_history.json"))
    private val _result = MutableStateFlow<ScanContent?>(null)
    val result: StateFlow<ScanContent?> = _result.asStateFlow()
    private val _history = MutableStateFlow<List<ScanEntry>>(emptyList())
    val history: StateFlow<List<ScanEntry>> = _history.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init { viewModelScope.launch(Dispatchers.IO) { _history.value = store.load() } }

    /** Called from the camera analyser thread or the gallery picker. Ignored while a result is showing. */
    fun onCode(code: ScannedCode): Boolean {
        if (_result.value != null) return false
        _result.value = BarcodeClassifier.classify(code.raw, code.format)
        viewModelScope.launch(Dispatchers.IO) { _history.value = store.add(code.raw, code.format) }
        return true
    }

    fun showEntry(e: ScanEntry) { _result.value = BarcodeClassifier.classify(e.raw, e.format) }
    fun dismiss() { _result.value = null }
    fun remove(e: ScanEntry) { viewModelScope.launch(Dispatchers.IO) { _history.value = store.remove(e) } }
    fun clearHistory() { viewModelScope.launch(Dispatchers.IO) { store.clear(); _history.value = emptyList() } }
    fun setNotice(text: String?) { _notice.value = text }
}
