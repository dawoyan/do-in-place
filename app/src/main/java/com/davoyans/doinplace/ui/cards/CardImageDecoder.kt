package com.davoyans.doinplace.ui.cards

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

object CardImageDecoder {
    fun decodeFromUri(context: Context, uri: Uri): ScannedCardPayload? =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream) ?: return null
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            decodeFromPixels(width, height, pixels)
        }

    fun decodeFromPixels(width: Int, height: Int, pixels: IntArray): ScannedCardPayload? {
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.POSSIBLE_FORMATS to BarcodeFormat.entries
                )
            )
        }
        val result = tryDecode(reader, source) ?: return null
        val format = result.barcodeFormat.name
        return ScannedCardPayload(
            codeValue = result.codeValue,
            codeType = if (result.barcodeFormat == BarcodeFormat.QR_CODE) SavedCardCodeTypes.QR else SavedCardCodeTypes.BARCODE,
            barcodeFormat = format
        )
    }

    private fun tryDecode(reader: MultiFormatReader, source: RGBLuminanceSource): DecodeResult? {
        val candidates = listOf(
            source,
            RGBLuminanceSource(source.width, source.height, source.matrix.map { 0x00FFFFFF or ((255 - it.toInt()) shl 16) or ((255 - it.toInt()) shl 8) or (255 - it.toInt()) }.toIntArray())
        )
        for (candidate in candidates) {
            try {
                val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate)))
                return DecodeResult(result.text.orEmpty().trim(), result.barcodeFormat)
            } catch (_: NotFoundException) {
                reader.reset()
            }
        }
        return null
    }

    private data class DecodeResult(
        val codeValue: String,
        val barcodeFormat: BarcodeFormat
    )
}
