package com.vigilia.app.ui.terms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text

/**
 * Renderiza texto com dois marcadores: `**bold**` e `__italic__`. Serve os
 * assets dos termos legais (assets/terms/) sem depender de uma lib markdown
 * completa. Delimitador sem par é emitido literal (ex: prefixo com dois
 * asteriscos sem fechamento aparece como texto puro), pra não engolir
 * conteúdo silenciosamente.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text) { parseSimpleMarkdown(text) }
    Text(
        text = annotated,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        modifier = modifier,
    )
}

internal fun parseSimpleMarkdown(input: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < input.length) {
        val bold = input.indexOf("**", i)
        val italic = input.indexOf("__", i)
        val nextDelim = when {
            bold < 0 && italic < 0 -> -1
            bold < 0 -> italic
            italic < 0 -> bold
            else -> minOf(bold, italic)
        }
        if (nextDelim < 0) {
            append(input.substring(i))
            return@buildAnnotatedString
        }
        if (nextDelim > i) append(input.substring(i, nextDelim))
        val delimiter = input.substring(nextDelim, nextDelim + 2)
        val closing = input.indexOf(delimiter, nextDelim + 2)
        if (closing < 0) {
            append(input.substring(nextDelim))
            return@buildAnnotatedString
        }
        val span = when (delimiter) {
            "**" -> SpanStyle(fontWeight = FontWeight.Bold)
            "__" -> SpanStyle(fontStyle = FontStyle.Italic)
            else -> SpanStyle()
        }
        withStyle(span) { append(input.substring(nextDelim + 2, closing)) }
        i = closing + 2
    }
}
