package dev.amatv.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneSignInQrTest {
    @Test fun pairingUrlIncludingNonceSurvivesQrRoundTrip() {
        val url = "http://192.168.50.12:38671/auth/e8eeab6512b04b18847e32cc81d70b80/"
        val matrix = phoneSignInQrMatrix(url)
        val scale = 6
        val width = matrix.width * scale
        val pixels = IntArray(width * width) { index ->
            if (matrix[(index % width) / scale, (index / width) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }

    @Test fun allFourQuietZoneModulesAreBlank() {
        val matrix = phoneSignInQrMatrix("http://192.168.1.3:50000/a/nonce")
        for (edge in 0 until 4) for (index in 0 until matrix.width) {
            assertFalse(matrix[edge, index])
            assertFalse(matrix[index, edge])
            assertFalse(matrix[matrix.width - 1 - edge, index])
            assertFalse(matrix[index, matrix.height - 1 - edge])
        }
    }
}
