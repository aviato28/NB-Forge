package com.namanbansal.forge.core.device

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.compositionLocalOf
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ScannedCode(val raw: String, val format: String)

/** Seam over ML Kit's bundled barcode model (replaced by a fake in tests). */
interface BarcodeDecoder {
    /** Decodes one camera frame; always closes [image]. */
    fun decodeFrame(image: ImageProxy, onResult: (ScannedCode?) -> Unit)
    suspend fun decodeUri(context: Context, uri: Uri): ScannedCode?
    fun close()
}

class MlKitBarcodeDecoder : BarcodeDecoder {
    private val scanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }

    @androidx.camera.core.ExperimentalGetImage
    override fun decodeFrame(image: ImageProxy, onResult: (ScannedCode?) -> Unit) {
        val media = image.image
        if (media == null) { image.close(); return }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes -> onResult(codes.firstNotNullOfOrNull(::convert)) }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { image.close() }
    }

    override suspend fun decodeUri(context: Context, uri: Uri): ScannedCode? = suspendCancellableCoroutine { cont ->
        try {
            val input = InputImage.fromFilePath(context, uri)
            scanner.process(input)
                .addOnSuccessListener { codes -> if (cont.isActive) cont.resume(codes.firstNotNullOfOrNull(::convert)) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    override fun close() { runCatching { scanner.close() } }

    private fun convert(b: Barcode): ScannedCode? {
        val raw = b.rawValue ?: b.rawBytes?.let { String(it, Charsets.UTF_8) } ?: return null
        return ScannedCode(raw, formatName(b.format))
    }

    private fun formatName(f: Int): String = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"; Barcode.FORMAT_EAN_13 -> "EAN_13"; Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"; Barcode.FORMAT_UPC_E -> "UPC_E"; Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"; Barcode.FORMAT_CODE_93 -> "CODE_93"; Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"; Barcode.FORMAT_PDF417 -> "PDF417"; Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_CODABAR -> "CODABAR"; else -> "UNKNOWN"
    }
}

val LocalBarcodeDecoder = compositionLocalOf<BarcodeDecoder?> { null }
