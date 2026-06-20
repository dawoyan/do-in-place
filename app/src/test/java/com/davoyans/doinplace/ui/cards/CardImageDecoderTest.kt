package com.davoyans.doinplace.ui.cards

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CardImageDecoderTest {
    @Test
    fun decodeFromPixelsReadsQrCode() {
        val matrix = QRCodeWriter().encode("shop-code-123", BarcodeFormat.QR_CODE, 256, 256)
        val pixels = IntArray(256 * 256) { index ->
            val x = index % 256
            val y = index / 256
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        val decoded = CardImageDecoder.decodeFromPixels(256, 256, pixels)

        assertNotNull(decoded)
        assertEquals("shop-code-123", decoded.codeValue)
        assertEquals(SavedCardCodeTypes.QR, decoded.codeType)
        assertEquals(BarcodeFormat.QR_CODE.name, decoded.barcodeFormat)
    }
}
