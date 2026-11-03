package dev.amatv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.common.BitMatrix
import kotlin.math.floor

/** Dialog callers retain their own focus requesters, navigation and input gates. */
@Composable
internal fun TvDialogAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick, modifier.height(43.dp), cornerRadius = 22.dp,
        background = Color.White.copy(alpha = .10f), focusedBackground = Color(0xFFEBEBEB),
        focusedScale = 1.025f, padding = PaddingValues(horizontal = 18.dp)) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(label, color = focusSurfaceContentColor(Color.White, Color(0xFF1E1E22)),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Integral modules keep the encoded quiet zone crisp at the actual TV pixel scale. */
@Composable
internal fun QrCodeCanvas(code: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val module = floor(minOf(size.width / code.width, size.height / code.height)).coerceAtLeast(1f)
        val left = floor((size.width - module * code.width) / 2f)
        val top = floor((size.height - module * code.height) / 2f)
        for (y in 0 until code.height) for (x in 0 until code.width) if (code[x, y]) {
            drawRect(Color.Black, Offset(left + x * module, top + y * module), Size(module, module))
        }
    }
}
