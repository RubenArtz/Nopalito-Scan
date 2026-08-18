/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.screens.tools.qrgenerator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.data.CloudScanUploader
import nopalito.app.ui.screens.cloud.model.*
import nopalito.app.ui.screens.qr.QrScanEntity
import nopalito.app.ui.screens.qr.QrScanRepository
import java.io.File

/** Content types accepted by POST /api/qr/generate (backend type ids). */
enum class QrContentType(val id: String) {
    URL("url"),
    TEXT("text"),
    WIFI("wifi"),
    EMAIL("email"),
    SMS("sms"),
    PHONE("tel")
}

/** Result of the cloud-history sync when the user presses Guardar. */
sealed interface CloudSyncResult {
    data object IDLE : CloudSyncResult
    data object PUSHED : CloudSyncResult
    data object NOT_AUTHENTICATED : CloudSyncResult
    data object FAILED : CloudSyncResult
}

data class QrGeneratorUiState(
    val styles: List<QrStyle> = emptyList(),
    val selectedStyleId: String? = null,
    val contentType: QrContentType = QrContentType.URL,
    val data: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val wifiSecurity: String = "WPA",
    val emailAddress: String = "",
    val emailSubject: String = "",
    val emailBody: String = "",
    val smsNumber: String = "",
    val smsMessage: String = "",
    val phoneNumber: String = "",
    val foregroundColor: String = "#000000",
    val backgroundColor: String = "#FFFFFF",
    val moduleShape: String = "square",
    val errorCorrection: String = "H",
    val size: Int = 512,
    val frameText: String = "",
    val format: String = "png",
    val scanCheck: Boolean = false,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val result: QrGenerateData? = null,
    val resultBitmap: Bitmap? = null,
    val resultBytes: ByteArray? = null,
    /** The request that produced the current result (for saving to history on Guardar). */
    val lastRequest: QrGenerateRequest? = null,
    /** Outcome of the last Guardar cloud-sync attempt. */
    val cloudSync: CloudSyncResult = CloudSyncResult.IDLE,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QrGeneratorUiState

        if (size != other.size) return false
        if (scanCheck != other.scanCheck) return false
        if (isGenerating != other.isGenerating) return false
        if (styles != other.styles) return false
        if (selectedStyleId != other.selectedStyleId) return false
        if (contentType != other.contentType) return false
        if (data != other.data) return false
        if (wifiSsid != other.wifiSsid) return false
        if (wifiPassword != other.wifiPassword) return false
        if (wifiSecurity != other.wifiSecurity) return false
        if (emailAddress != other.emailAddress) return false
        if (emailSubject != other.emailSubject) return false
        if (emailBody != other.emailBody) return false
        if (smsNumber != other.smsNumber) return false
        if (smsMessage != other.smsMessage) return false
        if (phoneNumber != other.phoneNumber) return false
        if (foregroundColor != other.foregroundColor) return false
        if (backgroundColor != other.backgroundColor) return false
        if (moduleShape != other.moduleShape) return false
        if (errorCorrection != other.errorCorrection) return false
        if (frameText != other.frameText) return false
        if (format != other.format) return false
        if (error != other.error) return false
        if (result != other.result) return false
        if (resultBitmap != other.resultBitmap) return false
        if (!resultBytes.contentEquals(other.resultBytes)) return false
        if (lastRequest != other.lastRequest) return false
        if (cloudSync != other.cloudSync) return false

        return true
    }

    override fun hashCode(): Int {
        var result1 = size
        result1 = 31 * result1 + scanCheck.hashCode()
        result1 = 31 * result1 + isGenerating.hashCode()
        result1 = 31 * result1 + styles.hashCode()
        result1 = 31 * result1 + selectedStyleId.hashCode()
        result1 = 31 * result1 + contentType.hashCode()
        result1 = 31 * result1 + data.hashCode()
        result1 = 31 * result1 + wifiSsid.hashCode()
        result1 = 31 * result1 + wifiPassword.hashCode()
        result1 = 31 * result1 + wifiSecurity.hashCode()
        result1 = 31 * result1 + emailAddress.hashCode()
        result1 = 31 * result1 + emailSubject.hashCode()
        result1 = 31 * result1 + emailBody.hashCode()
        result1 = 31 * result1 + smsNumber.hashCode()
        result1 = 31 * result1 + smsMessage.hashCode()
        result1 = 31 * result1 + phoneNumber.hashCode()
        result1 = 31 * result1 + foregroundColor.hashCode()
        result1 = 31 * result1 + backgroundColor.hashCode()
        result1 = 31 * result1 + moduleShape.hashCode()
        result1 = 31 * result1 + errorCorrection.hashCode()
        result1 = 31 * result1 + frameText.hashCode()
        result1 = 31 * result1 + format.hashCode()
        result1 = 31 * result1 + error.hashCode()
        result1 = 31 * result1 + result.hashCode()
        result1 = 31 * result1 + resultBitmap.hashCode()
        result1 = 31 * result1 + (resultBytes?.contentHashCode() ?: 0)
        result1 = 31 * result1 + lastRequest.hashCode()
        result1 = 31 * result1 + cloudSync.hashCode()
        return result1
    }
}

class QrGeneratorViewModel(
    private val context: Application,
    private val repository: CloudRepository,
    private val scanRepository: QrScanRepository,
    private val qrScansDir: File,
    private val cloudScanUploader: CloudScanUploader,
) : ViewModel() {

    private val _state = MutableStateFlow(QrGeneratorUiState())
    val state: StateFlow<QrGeneratorUiState> = _state.asStateFlow()
    private val gson = Gson()

    init {
        loadStyles()
    }

    fun loadStyles() {
        viewModelScope.launch {
            repository.listQrStyles()
                .onSuccess { styles ->
                    _state.update { it.copy(styles = styles) }
                }
                .onFailure { /* optional: styles are a nice-to-have, generation still works */ }
        }
    }

    fun selectStyle(styleId: String?) {
        _state.update { it.copy(selectedStyleId = styleId) }
    }

    fun setContentType(type: QrContentType) {
        _state.update { it.copy(contentType = type) }
    }

    fun setData(value: String) = _state.update { it.copy(data = value) }
    fun setWifiSsid(value: String) = _state.update { it.copy(wifiSsid = value) }
    fun setWifiPassword(value: String) = _state.update { it.copy(wifiPassword = value) }
    fun setWifiSecurity(value: String) = _state.update { it.copy(wifiSecurity = value) }
    fun setEmailAddress(value: String) = _state.update { it.copy(emailAddress = value) }
    fun setEmailSubject(value: String) = _state.update { it.copy(emailSubject = value) }
    fun setEmailBody(value: String) = _state.update { it.copy(emailBody = value) }
    fun setSmsNumber(value: String) = _state.update { it.copy(smsNumber = value) }
    fun setSmsMessage(value: String) = _state.update { it.copy(smsMessage = value) }
    fun setPhoneNumber(value: String) = _state.update { it.copy(phoneNumber = value) }
    fun setForegroundColor(value: String) = _state.update { it.copy(foregroundColor = value) }
    fun setBackgroundColor(value: String) = _state.update { it.copy(backgroundColor = value) }
    fun setModuleShape(value: String) = _state.update { it.copy(moduleShape = value) }
    fun setErrorCorrection(value: String) = _state.update { it.copy(errorCorrection = value) }
    fun setSize(value: Int) = _state.update { it.copy(size = value) }
    fun setFrameText(value: String) = _state.update { it.copy(frameText = value) }
    fun setFormat(value: String) {
        if (_state.value.format == value) return
        _state.update { it.copy(format = value) }
        // If a QR was already generated, regenerate it so the preview and the
        // exported bytes match the newly selected format.
        if (_state.value.result != null) generate()
    }

    fun toggleScanCheck() = _state.update { it.copy(scanCheck = !it.scanCheck) }

    /** Resets the cloud-sync result so its UI (toast/dialog) is shown only once. */
    fun consumeCloudSync() {
        _state.update { it.copy(cloudSync = CloudSyncResult.IDLE) }
    }

    /** Persists the current generated QR to the local and cloud history (Guardar button). */
    fun saveCurrent() {
        val s = _state.value
        val request = s.lastRequest ?: return
        viewModelScope.launch {
            val result = runCatching { saveToHistory(request, s.resultBitmap) }
                .getOrElse { CloudSyncResult.FAILED }
            _state.update { it.copy(cloudSync = result) }
        }
    }

    /** Builds the request and generates the QR, then downloads the image bytes. */
    fun generate() {
        val s = _state.value
        if (s.isGenerating) return
        if (s.contentType in setOf(QrContentType.URL, QrContentType.TEXT) && s.data.isBlank()) {
            _state.update { it.copy(error = context.stringFor(R.string.qr_content_required, AppLocaleOverride.locale)) }
            return
        }
        val request = buildRequest(s)
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isGenerating = true, error = null, result = null,
                    resultBitmap = null, resultBytes = null, cloudSync = CloudSyncResult.IDLE,
                )
            }
            repository.generateQr(request)
                .onSuccess { result ->
                    val bytes = repository.fetchQrBytes(result.url)
                    val bitmap = decodeBitmap(bytes)
                        ?: fetchPngPreview(request, result)
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            result = result,
                            resultBitmap = bitmap,
                            resultBytes = bytes,
                            lastRequest = request,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            error = CloudErrorPresenter.message(context, e, R.string.qr_generate_error),
                        )
                    }
                }
        }
    }

    private fun decodeBitmap(bytes: ByteArray?): Bitmap? =
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /**
     * SVG/PDF bytes are not raster, so [BitmapFactory] cannot decode them for
     * the preview. Requests an equivalent PNG from the backend (same design,
     * format=png) and decodes that; only used for the preview, never exported.
     */
    private suspend fun fetchPngPreview(primary: QrGenerateRequest, result: QrGenerateData): Bitmap? {
        if (result.format == "png") return null
        return repository.generateQr(primary.copy(format = "png")).getOrNull()
            ?.let { repository.fetchQrBytes(it.url) }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /**
     * Persists the generated QR to the local history and pushes it to the cloud
     * history (when authenticated). The stored recipe excludes format and
     * scanCheck so identical designs generated in different formats are not
     * duplicated and any export variant can be re-downloaded from history.
     *
     * The cloud push is idempotent and retryable: it only happens while the
     * recipe is not marked [QrScanEntity.cloudSynced]. If the first attempt
     * fails (offline, backend error, not authenticated yet), pressing Guardar
     * again retries the push.
     */
    private suspend fun saveToHistory(request: QrGenerateRequest, bitmap: Bitmap?): CloudSyncResult {
        val designJson = gson.toJson(
            request.copy(format = null, design = request.design?.copy(scanCheck = null))
        )
        var existing = scanRepository.findByDesign(designJson)
        if (existing == null) {
            val imagePath = bitmap?.let { savePreview(it) }
            val contentType = QrContentType.entries.firstOrNull { it.id == request.type } ?: QrContentType.URL
            val entity = QrScanEntity(
                content = displayContent(request, contentType),
                dateTime = System.currentTimeMillis(),
                format = "QR Code",
                imagePath = imagePath,
                typeData = encodeTypeData(contentType, request),
                designJson = designJson,
            )
            scanRepository.insert(entity)
            existing = scanRepository.findByDesign(designJson)
        }
        val current = existing ?: return CloudSyncResult.FAILED
        if (current.cloudSynced) return CloudSyncResult.PUSHED
        if (!cloudScanUploader.hasSession()) return CloudSyncResult.NOT_AUTHENTICATED
        val push = cloudScanUploader.uploadGenerated(
            content = current.content,
            type = QrContentType.entries.firstOrNull { it.id == request.type }?.id ?: "text",
            typeData = current.typeData,
            format = current.format,
            design = current.designJson,
            imagePath = current.imagePath,
        )
        return if (push.isSuccess) {
            scanRepository.markCloudSynced(designJson)
            CloudSyncResult.PUSHED
        } else {
            CloudSyncResult.FAILED
        }
    }

    private fun savePreview(bitmap: Bitmap): String? = try {
        qrScansDir.mkdirs()
        val file = File(qrScansDir, "scan_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    private fun displayContent(request: QrGenerateRequest, type: QrContentType): String = when (type) {
        QrContentType.URL, QrContentType.TEXT, QrContentType.PHONE -> request.data
        QrContentType.WIFI -> request.fields?.ssid ?: "Red WiFi"
        QrContentType.EMAIL -> request.fields?.to ?: "Correo"
        QrContentType.SMS -> request.fields?.phone ?: "SMS"
    }

    /** Tab-separated serialization compatible with the scanner's encodeQrType. */
    private fun encodeTypeData(type: QrContentType, request: QrGenerateRequest): String? {
        val f = request.fields
        return when (type) {
            QrContentType.URL -> "U\t${request.data}"
            QrContentType.TEXT -> null
            QrContentType.WIFI -> "W\t${f?.ssid.orEmpty()}\t${f?.password.orEmpty()}\t${f?.encryption ?: "WPA"}"
            QrContentType.EMAIL -> "E\t${f?.to.orEmpty()}\t${f?.subject.orEmpty()}\t${f?.body.orEmpty()}"
            QrContentType.SMS -> "S\t${f?.phone.orEmpty()}\t${f?.message.orEmpty()}"
            QrContentType.PHONE -> "P\t${request.data}"
        }
    }

    private fun buildRequest(s: QrGeneratorUiState): QrGenerateRequest {
        val (data, fields, type) = when (s.contentType) {
            QrContentType.URL -> Triple(s.data.trim(), null, "url")
            QrContentType.TEXT -> Triple(s.data.trim(), null, "text")
            QrContentType.WIFI -> Triple(
                "",
                QrFieldsRequest(ssid = s.wifiSsid.trim(), password = s.wifiPassword, encryption = s.wifiSecurity),
                "wifi"
            )

            QrContentType.EMAIL -> Triple(
                "", QrFieldsRequest(to = s.emailAddress.trim(), subject = s.emailSubject, body = s.emailBody), "email"
            )

            QrContentType.SMS -> Triple(
                "", QrFieldsRequest(phone = s.smsNumber.trim(), message = s.smsMessage), "sms"
            )

            QrContentType.PHONE -> Triple(s.phoneNumber.trim(), null, "tel")
        }
        val design = QrDesignRequest(
            foregroundColor = s.foregroundColor.ifBlank { null },
            backgroundColor = s.backgroundColor.ifBlank { null },
            moduleShape = s.moduleShape,
            errorCorrection = s.errorCorrection,
            size = s.size,
            frame = s.frameText.ifBlank { null }?.let { QrFrameRequest(it) },
            scanCheck = s.scanCheck.takeIf { it },
        )
        return QrGenerateRequest(
            data = data,
            type = type,
            fields = fields,
            design = design,
            format = s.format,
            styleId = s.selectedStyleId,
        )
    }
}
