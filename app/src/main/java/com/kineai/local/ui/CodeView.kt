package com.kineai.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kineai.local.code.DiffKind
import com.kineai.local.code.DiffLine
import com.kineai.local.ui.theme.OneDarkSyntax
import com.kineai.local.ui.theme.SyntaxTheme

val CodeBg = Color(0xFF282C34)
val CodeFg = Color(0xFFDCDFE4)
private val LineNo = Color(0xFF6B7280)
private val mono = FontFamily.Monospace

private val KEYWORDS = setOf(
    "val", "var", "fun", "class", "object", "interface", "import", "package", "return", "if", "else", "when", "for", "while", "do",
    "def", "lambda", "from", "as", "with", "try", "except", "finally", "raise", "pass", "in", "is", "not", "and", "or", "None", "True", "False",
    "function", "const", "let", "async", "await", "export", "new", "this", "self", "null", "true", "false", "static", "public", "private",
    "protected", "void", "int", "string", "bool", "fn", "pub", "impl", "struct", "enum", "match", "mut", "use", "mod", "go", "func", "type",
    "break", "continue", "switch", "case", "default", "throw", "throws", "extends", "implements", "override", "yield", "elif", "print",
)

private val TOKEN = Regex(
    "(//[^\\n]*|#[^\\n]*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" + // 1 comment
        "|(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')" + // 2 string
        "|\\b([A-Za-z_][A-Za-z0-9_]*)\\b" + // 3 identifier
        "|\\b(\\d+(?:\\.\\d+)?[fFLuU]*)\\b", // 4 number
)

const val MAX_HIGHLIGHT = 2000

/** Lightweight multi-language highlighting; long lines are left plain to bound regex cost. */
fun highlight(line: String, theme: SyntaxTheme): AnnotatedString =
    buildAnnotatedString {
        if (line.length > MAX_HIGHLIGHT) { append(line); return@buildAnnotatedString }
        var last = 0
        for (m in TOKEN.findAll(line)) {
            if (m.range.first > last) append(line.substring(last, m.range.first))
            val style = when {
                m.groups[1] != null -> SpanStyle(color = theme.comment)
                m.groups[2] != null -> SpanStyle(color = theme.string)
                m.groups[3] != null -> when {
                    m.value in KEYWORDS -> SpanStyle(color = theme.keyword)
                    m.value.first().isUpperCase() -> SpanStyle(color = theme.type)
                    else -> SpanStyle()
                }
                else -> SpanStyle(color = theme.number)
            }
            withStyle(style) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < line.length) append(line.substring(last))
    }

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, theme: SyntaxTheme = OneDarkSyntax) {
    val lines = remember(text) { text.split("\n") }
    val h = rememberScrollState()
    LazyColumn(modifier.background(CodeBg)) {
        itemsIndexed(lines) { i, l ->
            Row(Modifier.horizontalScroll(h)) {
                Text("${i + 1}".padStart(4), color = LineNo, fontFamily = mono, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                Text(remember(l) { highlight(l, theme) }, color = CodeFg, fontFamily = mono, fontSize = 13.sp, softWrap = false)
            }
        }
    }
}

/** Non-lazy variant for short snippets inside chat bubbles. */
@Composable
fun InlineCode(text: String, theme: SyntaxTheme = OneDarkSyntax) {
    val h = rememberScrollState()
    Column(Modifier.fillMaxWidth().background(CodeBg).padding(8.dp).horizontalScroll(h)) {
        text.split("\n").forEach { Text(highlight(it, theme), color = CodeFg, fontFamily = mono, fontSize = 13.sp, softWrap = false) }
    }
}

@Composable
fun DiffView(lines: List<DiffLine>, modifier: Modifier = Modifier, contextLines: Int = 3) {
    // Collapse long unchanged runs so the change is what you see.
    val shown = remember(lines) {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l -> if (l.kind != DiffKind.SAME) for (k in (i - contextLines)..(i + contextLines)) if (k in lines.indices) keep[k] = true }
        buildList { var skipped = 0
            lines.forEachIndexed { i, l -> if (keep[i]) { if (skipped > 0) { add(DiffLine(DiffKind.SAME, "⋯ $skipped unchanged lines")); skipped = 0 }; add(l) } else skipped++ }
            if (skipped > 0) add(DiffLine(DiffKind.SAME, "⋯ $skipped unchanged lines")) }
    }
    val h = rememberScrollState()
    Column(modifier.background(CodeBg).horizontalScroll(h)) {
        shown.forEach { l ->
            val (bg, mark) = when (l.kind) {
                DiffKind.ADDED -> Color(0x3328A745) to "+ "
                DiffKind.REMOVED -> Color(0x33F85149) to "- "
                DiffKind.SAME -> Color.Transparent to "  "
            }
            Text(mark + l.text, color = CodeFg, fontFamily = mono, fontSize = 12.sp, softWrap = false, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp))
        }
    }
}

@Composable
fun GitDiffText(text: String, modifier: Modifier = Modifier) {
    val h = rememberScrollState()
    LazyColumn(modifier.background(CodeBg)) {
        itemsIndexed(text.split("\n")) { _, l ->
            val bg = when { l.startsWith("+") && !l.startsWith("+++") -> Color(0x3328A745); l.startsWith("-") && !l.startsWith("---") -> Color(0x33F85149); l.startsWith("@@") -> Color(0x332F81F7); else -> Color.Transparent }
            Text(l, color = CodeFg, fontFamily = mono, fontSize = 12.sp, softWrap = false, modifier = Modifier.fillMaxWidth().background(bg).horizontalScroll(h).padding(horizontal = 6.dp))
        }
    }
}
