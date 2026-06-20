package com.davoyans.doinplace.ui.cards

import android.graphics.Bitmap
import android.graphics.Color
import com.davoyans.doinplace.data.model.SavedCardEntity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

object SavedCardCodeTypes {
    const val QR = "QR"
    const val BARCODE = "BARCODE"
}

data class BarcodeRenderResult(
    val bitmap: Bitmap?,
    val usedQrFallback: Boolean
)

fun barcodeFormatLabel(format: String?): String {
    if (format.isNullOrBlank()) return "CODE-128"
    return when (format) {
        "QR_CODE" -> "QR Code"
        "EAN_13" -> "EAN-13"
        "EAN_8" -> "EAN-8"
        "UPC_A" -> "UPC-A"
        "UPC_E" -> "UPC-E"
        "CODE_39" -> "CODE-39"
        "CODE_128" -> "CODE-128"
        "PDF_417" -> "PDF-417"
        else -> format.replace('_', '-')
    }
}

fun barcodeTypeLabel(codeType: String): String = when (codeType) {
    SavedCardCodeTypes.QR -> SavedCardCodeTypes.QR
    else -> "Barcode"
}

fun renderSavedCardBitmap(
    card: SavedCardEntity,
    widthPx: Int,
    heightPx: Int
): BarcodeRenderResult {
    val preferredFormat = barcodeFormatFromCard(card)
    val preferredBitmap = encodeCardBitmap(card.codeValue, preferredFormat, widthPx, heightPx)
    if (preferredBitmap != null) {
        return BarcodeRenderResult(bitmap = preferredBitmap, usedQrFallback = false)
    }

    if (preferredFormat == BarcodeFormat.QR_CODE) {
        return BarcodeRenderResult(bitmap = null, usedQrFallback = false)
    }

    val qrFallback = encodeCardBitmap(
        content = card.codeValue,
        format = BarcodeFormat.QR_CODE,
        widthPx = widthPx,
        heightPx = widthPx
    )
    return BarcodeRenderResult(bitmap = qrFallback, usedQrFallback = qrFallback != null)
}

fun barcodeFormatFromCard(card: SavedCardEntity): BarcodeFormat =
    barcodeFormatFromValues(card.codeType, card.barcodeFormat)

fun barcodeFormatFromValues(codeType: String, barcodeFormat: String?): BarcodeFormat =
    when {
        barcodeFormat != null -> barcodeFormatFromName(barcodeFormat)
        codeType == SavedCardCodeTypes.QR -> BarcodeFormat.QR_CODE
        else -> BarcodeFormat.CODE_128
    }

private fun barcodeFormatFromName(name: String): BarcodeFormat = when (name) {
    "QR_CODE" -> BarcodeFormat.QR_CODE
    "EAN_13" -> BarcodeFormat.EAN_13
    "EAN_8" -> BarcodeFormat.EAN_8
    "UPC_A" -> BarcodeFormat.UPC_A
    "UPC_E" -> BarcodeFormat.UPC_E
    "CODE_39" -> BarcodeFormat.CODE_39
    "CODE_128" -> BarcodeFormat.CODE_128
    "ITF" -> BarcodeFormat.ITF
    "PDF_417" -> BarcodeFormat.PDF_417
    else -> BarcodeFormat.CODE_128
}

private fun encodeCardBitmap(
    content: String,
    format: BarcodeFormat,
    widthPx: Int,
    heightPx: Int
): Bitmap? = runCatching {
    val targetHeight = if (format == BarcodeFormat.QR_CODE) widthPx else heightPx
    val matrix = MultiFormatWriter().encode(
        content,
        format,
        widthPx,
        targetHeight,
        mapOf(EncodeHintType.MARGIN to 2)
    )
    Bitmap.createBitmap(widthPx, targetHeight, Bitmap.Config.RGB_565).apply {
        for (x in 0 until widthPx) {
            for (y in 0 until targetHeight) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}.getOrNull()
