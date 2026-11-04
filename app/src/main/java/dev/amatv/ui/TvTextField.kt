package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TvTextFieldSize { Compact, Search }

/** One native text target, with presentation selected independently of editing behavior. */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: String? = null,
    label: String? = null,
    size: TvTextFieldSize = TvTextFieldSize.Compact,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactive = enabled && LocalFocusTargetsEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val hasFocus by interaction.collectIsFocusedAsState()
    val hasHover by interaction.collectIsHoveredAsState()
    val focused = interactive && hasFocus
    val hovered = interactive && hasHover
    val search = size == TvTextFieldSize.Search
    val classic = LocalInterfaceStyle.current == InterfaceStyle.Classic
    val minimumHeight = if (classic) { if (search) 56.dp else 44.dp } else if (search) 42.5.dp else 33.dp
    val foreground = when {
        !interactive -> Color.White.copy(alpha = 0.4f)
        classic -> Color.White
        search -> Color.White.copy(alpha = 0.9f)
        focused -> Color(0xFF1B1B1F)
        else -> Color.White.copy(alpha = 0.9f)
    }
    val background = if (classic) Color.White.copy(alpha = when {
        !interactive -> 0.03f
        focused && !readOnly -> 0.14f
        focused -> 0.08f
        hovered -> 0.08f
        else -> 0.05f
    }) else if (search) Color.Transparent else Color.White.copy(alpha = when {
        !interactive -> 0.03f
        focused -> 0.85f
        hovered -> 0.18f
        else -> 0.08f
    })
    val border = if (classic) Color.White.copy(alpha = when {
        !interactive -> 0.10f
        focused && !readOnly -> 1f
        focused -> 0.82f
        hovered -> 0.52f
        else -> 0.22f
    }) else if (search) Color.Transparent else Color.White.copy(alpha = when {
        !interactive -> 0.04f
        focused -> 0.92f
        hovered -> 0.4f
        else -> 0.10f
    })
    val textStyle = LocalTextStyle.current.copy(
        color = foreground,
        fontSize = if (classic) 16.sp else if (search) 28.5.sp else 12.sp,
        lineHeight = if (classic) 24.sp else if (search) 34.sp else 17.sp,
        fontWeight = if (classic) FontWeight.Normal else if (search) FontWeight.Bold else FontWeight.Medium,
    )
    val shape = if (classic) MaterialTheme.shapes.extraSmall else RoundedCornerShape(if (search) 21.25.dp else 16.5.dp)
    val selection = TextSelectionColors(
        handleColor = foreground,
        backgroundColor = if (focused && !search && !classic) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.16f),
    )
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.heightIn(min = minimumHeight)
                .clip(shape).background(background).border(if (classic && focused) 2.dp else 1.dp, border, shape)
                .hoverable(interactionSource = interaction, enabled = interactive),
            enabled = interactive,
            readOnly = readOnly,
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = textStyle,
            cursorBrush = SolidColor(foreground),
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = minimumHeight)
                        .padding(horizontal = if (classic) 16.dp else if (search) 0.dp else 12.dp,
                            vertical = if (classic) 12.dp else if (search) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (search && !classic) Icon(SearchInputIcon, null,
                        Modifier.size(width = 35.dp, height = (35f * 100.399f / 122.037f).dp),
                        tint = Color.White.copy(alpha = if (interactive) 0.3f else 0.15f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (label != null) Text(label, color = foreground.copy(alpha = if (interactive) 0.7f else 0.4f),
                            fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle,
                                color = if (classic) Color.White.copy(alpha = if (focused) 0.52f else 0.38f)
                                    else if (focused && !search) foreground.copy(alpha = 0.65f) else foreground,
                                maxLines = if (singleLine) 1 else maxLines, overflow = TextOverflow.Ellipsis)
                            innerTextField()
                        }
                    }
                }
            },
        )
    }
}

/** SearchPageFactory's shipped search glyph, retaining its full source canvas. */
private val SearchInputIcon: ImageVector by lazy {
    ImageVector.Builder("TV17.SearchInput", 35.dp, (35f * 100.399f / 122.037f).dp, 122.037f, 100.399f).addPath(
        pathData = PathParser().parsePathString("M11.215 42.274C11.215 65.634 30.132 84.534 53.491 84.534 60.518 84.534 67.075 82.772 72.831 79.698L90.086 96.971C92.407 99.278 95.563 100.399 98.782 100.399 105.642 100.399 110.815 95.137 110.815 88.331 110.815 85.188 109.71 82.137 107.362 79.755L90.315 62.691C93.779 56.709 95.749 49.735 95.749 42.274 95.749 18.917 76.849 0 53.491 0 30.132 0 11.215 18.917 11.215 42.274ZM27.218 42.274C27.218 27.802 39.017 16.003 53.491 16.003 67.991 16.003 79.763 27.802 79.763 42.274 79.763 56.732 67.991 68.548 53.491 68.548 39.017 68.548 27.218 56.732 27.218 42.274Z").toNodes(),
        fill = SolidColor(Color.White),
    ).build()
}
