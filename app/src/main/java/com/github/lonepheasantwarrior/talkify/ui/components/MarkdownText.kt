package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val isMarkdown = remember(content) {
        content.contains("#") ||
        content.contains("**") ||
        content.contains("*") ||
        content.contains("`") ||
        content.contains("- ") ||
        content.contains("1. ") ||
        content.contains(">")
    }

    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val codeTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (isMarkdown) {
        val parsedText = remember(content, style, color) {
            parseMarkdown(
                content = content,
                baseStyle = style,
                baseColor = color,
                codeBackgroundColor = codeBackgroundColor,
                codeTextColor = codeTextColor
            )
        }
        Text(
            text = parsedText,
            modifier = modifier
                .fillMaxWidth(),
            style = style
        )
    } else {
        Text(
            text = content,
            modifier = modifier
                .fillMaxWidth(),
            style = style.copy(color = color)
        )
    }
}

private fun parseMarkdown(
    content: String,
    baseStyle: TextStyle,
    baseColor: Color,
    codeBackgroundColor: Color,
    codeTextColor: Color
): androidx.compose.ui.text.AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)
    val codeStyle = SpanStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        background = codeBackgroundColor,
        color = codeTextColor
    )
    val header1Style = SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)
    val header2Style = SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)
    val header3Style = SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)

    return buildAnnotatedString {
        val lines = content.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            when {
                line.startsWith("### ") -> {
                    withStyle(header3Style) {
                        append(line.removePrefix("### "))
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(header2Style) {
                        append(line.removePrefix("## "))
                    }
                }
                line.startsWith("# ") -> {
                    withStyle(header1Style) {
                        append(line.removePrefix("# "))
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    append("• ")
                    pushStyle(baseStyle.toSpanStyle())
                    append(processInlineFormatting(line.removePrefix("- ").removePrefix("* "), boldStyle, italicStyle, codeStyle))
                    pop()
                }
                line.startsWith("> ") -> {
                    pushStyle(baseStyle.copy(color = baseColor.copy(alpha = 0.7f)).toSpanStyle())
                    append("│ ")
                    append(processInlineFormatting(line.removePrefix("> "), boldStyle, italicStyle, codeStyle))
                    pop()
                }
                line.matches(Regex("^\\d+\\. .*")) -> {
                    val number = line.substringBefore(". ")
                    append("$number. ")
                    pushStyle(baseStyle.toSpanStyle())
                    append(processInlineFormatting(line.substringAfter(". "), boldStyle, italicStyle, codeStyle))
                    pop()
                }
                else -> {
                    pushStyle(baseStyle.toSpanStyle())
                    append(processInlineFormatting(line, boldStyle, italicStyle, codeStyle))
                    pop()
                }
            }
            if (i < lines.size - 1) {
                append("\n")
            }
            i++
        }
    }
}

private fun processInlineFormatting(
    text: String,
    boldStyle: SpanStyle,
    italicStyle: SpanStyle,
    codeStyle: SpanStyle
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val remaining = text
        var index = 0

        while (index < remaining.length) {
            val boldStart = remaining.indexOf("**", index)
            val italicStart = remaining.indexOf("*", index)
            val codeStart = remaining.indexOf("`", index)

            val nextBold = if (boldStart >= 0) boldStart else Int.MAX_VALUE
            val nextItalic = if (italicStart >= 0) italicStart else Int.MAX_VALUE
            val nextCode = if (codeStart >= 0) codeStart else Int.MAX_VALUE

            when {
                nextBold == Int.MAX_VALUE && nextItalic == Int.MAX_VALUE && nextCode == Int.MAX_VALUE -> {
                    append(remaining.substring(index))
                    break
                }
                nextBold < nextItalic && nextBold < nextCode -> {
                    append(remaining.substring(index, nextBold))
                    val boldEnd = remaining.indexOf("**", nextBold + 2)
                    if (boldEnd > nextBold + 2) {
                        val boldContent = remaining.substring(nextBold + 2, boldEnd)
                        withStyle(boldStyle) { append(boldContent) }
                        index = boldEnd + 2
                    } else {
                        append("**")
                        index = nextBold + 2
                    }
                }
                nextItalic < nextBold && nextItalic < nextCode -> {
                    val endIndex = nextItalic + 1
                    if (endIndex < remaining.length && remaining[endIndex] != ' ') {
                        append(remaining.substring(index, nextItalic))
                        val italicEnd = remaining.indexOf("*", endIndex)
                        if (italicEnd > endIndex) {
                            val italicContent = remaining.substring(endIndex, italicEnd)
                            withStyle(italicStyle) { append(italicContent) }
                            index = italicEnd + 1
                        } else {
                            append("*")
                            index = endIndex
                        }
                    } else {
                        append(remaining[index])
                        index++
                    }
                }
                nextCode < nextBold && nextCode < nextItalic -> {
                    append(remaining.substring(index, nextCode))
                    val codeEnd = remaining.indexOf("`", nextCode + 1)
                    if (codeEnd > nextCode) {
                        val codeContent = remaining.substring(nextCode + 1, codeEnd)
                        withStyle(codeStyle) { append(codeContent) }
                        index = codeEnd + 1
                    } else {
                        append("`")
                        index = nextCode + 1
                    }
                }
                else -> {
                    append(remaining[index])
                    index++
                }
            }
        }
    }
}


