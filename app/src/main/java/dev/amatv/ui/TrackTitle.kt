package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

/** Reserve the rating suffix independently of title ellipsis and artwork/motion state. */
@Composable
internal fun TrackTitle(
    track: Track,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    if (track.catalogKind != CatalogKind.Song || track.contentRating != "explicit") {
        Text(track.title, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
            textAlign = textAlign, lineHeight = lineHeight, maxLines = maxLines, overflow = overflow)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp,
            if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start)) {
        Text(track.title, Modifier.weight(1f, fill = false), color = color, fontSize = fontSize,
            fontWeight = fontWeight, textAlign = textAlign, lineHeight = lineHeight,
            maxLines = maxLines, overflow = overflow)
        val badgeColor = if (color == Color.Unspecified) LocalContentColor.current else color
        ExplicitContentBadge(color = badgeColor.copy(alpha = badgeColor.alpha * .85f),
            fontSize = if (fontSize == TextUnit.Unspecified) 8.sp else (fontSize.value * .6f).coerceAtLeast(8f).sp)
    }
}

/** Same compact E marker previously rendered by search results. */
@Composable
private fun ExplicitContentBadge(color: Color, fontSize: TextUnit) {
    val description = stringResource(R.string.resource_explicit)
    Text("E", Modifier.background(Color.White.copy(alpha = .20f), RoundedCornerShape(2.dp))
        .padding(horizontal = 2.dp).clearAndSetSemantics { contentDescription = description },
        color = color, fontSize = fontSize, lineHeight = (fontSize.value + 2f).sp,
        fontWeight = FontWeight.Bold, maxLines = 1)
}
