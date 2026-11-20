// Derived from AndCode v1.2.29 (MIT, (c) 2026 AndCode contributors); see licenses/AndCode-MIT.txt
package com.kineai.local.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders model output as Markdown: headings, emphasis, inline code, lists, quotes, tables, code blocks. */
@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> InlineText(
                    block.inlines,
                    when (block.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall }
                        .copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    linkColor, codeBg,
                )
                is MarkdownBlock.Paragraph -> InlineText(block.inlines, body, linkColor, codeBg)
                is MarkdownBlock.CodeBlock -> CodeBlockView(block.code)
                is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item -> Row { Text("•  ", color = MaterialTheme.colorScheme.onSurface); InlineText(item, body, linkColor, codeBg) } }
                }
                is MarkdownBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { i, item -> Row { Text("${i + 1}.  ", color = MaterialTheme.colorScheme.onSurface); InlineText(item, body, linkColor, codeBg) } }
                }
                is MarkdownBlock.Blockquote -> Surface(Modifier.fillMaxWidth(), RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                    Box(Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
                        InlineText(block.inlines, body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), linkColor, codeBg)
                    }
                }
                is MarkdownBlock.Table -> Surface(Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(8.dp).horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) { block.headers.forEach { Cell(it, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), linkColor, codeBg) } }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        block.rows.forEach { row ->
                            val padded = row.take(block.headers.size) + List((block.headers.size - row.size).coerceAtLeast(0)) { "" }
                            Row(Modifier.fillMaxWidth()) { padded.forEach { Cell(it, MaterialTheme.typography.bodyMedium, linkColor, codeBg) } }
                        }
                    }
                }
                is MarkdownBlock.HorizontalRule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, style: TextStyle, linkColor: Color, codeBg: Color) {
    val inlines = remember(text) { MarkdownLite.parseInline(text) }
    Box(Modifier.padding(end = 12.dp)) { InlineText(inlines, style, linkColor, codeBg) }
}

@Composable
private fun InlineText(inlines: List<MarkdownInline>, style: TextStyle, linkColor: Color, codeBg: Color) {
    val annotated = remember(inlines, linkColor, codeBg) { renderInline(inlines, codeBg, linkColor) }
    SelectionContainer { Text(annotated, style = style.copy(textDirection = TextDirection.Content)) }
}

/** A fenced code block with a copy button, as in AndCode. */
@Composable
fun CodeBlockView(code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box {
            Text(
                code,
                modifier = Modifier.absolutePadding(top = 10.dp, right = 52.dp, bottom = 10.dp).horizontalScroll(rememberScrollState()).absolutePadding(left = 10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
            )
            IconButton({ clipboard.setText(AnnotatedString(code)) }, Modifier.align(AbsoluteAlignment.TopRight)) {
                Icon(Icons.Filled.ContentCopy, "Copy code", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Only web links are tappable: model output must never be able to hand a file:// or intent: URI to the system.
private fun safeLink(url: String, color: Color): LinkAnnotation.Url? =
    if (url.startsWith("https://") || url.startsWith("http://")) LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) else null

private fun renderInline(inlines: List<MarkdownInline>, codeBackground: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        fun add(nodes: List<MarkdownInline>) {
            nodes.forEach { inline ->
                when (inline) {
                    is MarkdownInline.Plain -> append(inline.text)
                    is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { add(inline.inlines) }
                    is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(inline.inlines) }
                    is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(inline.inlines) }
                    is MarkdownInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(inline.text) }
                    is MarkdownInline.Link -> safeLink(inline.url, linkColor)?.let { withLink(it) { append(inline.text) } } ?: append(inline.text)
                    is MarkdownInline.Image -> append(inline.text.ifBlank { "[image]" })
                }
            }
        }
        add(inlines)
    }
