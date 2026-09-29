package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    // 增加对链接标识符的预判
    val isMarkdown = remember(content) {
        content.contains("#") ||
                content.contains("**") ||
                content.contains("*") ||
                content.contains("`") ||
                content.contains("- ") ||
                content.contains("1. ") ||
                content.contains(">") ||
                (content.contains("[") && content.contains("]("))
    }

    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val codeTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current

    val linkInteractionListener = remember(uriHandler) {
        LinkInteractionListener { annotation ->
            val url = (annotation as LinkAnnotation.Clickable).tag
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                // 防止非标准 URI 导致崩溃
                TtsLogger.e("Failed to open markdown link: $url", e)
            }
        }
    }

    if (isMarkdown) {
        val parsedText = remember(content, style, color, linkColor, codeBackgroundColor, codeTextColor, linkInteractionListener) {
            parseMarkdown(
                content = content,
                baseStyle = style.copy(color = color),
                baseColor = color,
                codeBackgroundColor = codeBackgroundColor,
                codeTextColor = codeTextColor,
                linkColor = linkColor,
                linkInteractionListener = linkInteractionListener
            )
        }

        Text(
            text = parsedText,
            modifier = modifier.fillMaxWidth(),
            style = style.copy(color = color)
        )
    } else {
        Text(
            text = content,
            modifier = modifier.fillMaxWidth(),
            style = style.copy(color = color)
        )
    }
}

private fun parseMarkdown(
    content: String,
    baseStyle: TextStyle,
    baseColor: Color,
    codeBackgroundColor: Color,
    codeTextColor: Color,
    linkColor: Color,
    linkInteractionListener: LinkInteractionListener
): androidx.compose.ui.text.AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)
    val codeStyle = SpanStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        background = codeBackgroundColor,
        color = codeTextColor
    )
    // 定义链接样式：跟随主色调并添加下划线
    val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)

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
                    append(processInlineFormatting(line.substring(2), boldStyle, italicStyle, codeStyle, linkStyle, linkInteractionListener))
                    pop()
                }
                line.startsWith("> ") -> {
                    pushStyle(baseStyle.copy(color = baseColor.copy(alpha = 0.7f)).toSpanStyle())
                    append("│ ")
                    append(processInlineFormatting(line.removePrefix("> "), boldStyle, italicStyle, codeStyle, linkStyle, linkInteractionListener))
                    pop()
                }
                line.matches(Regex("^\\d+\\. .*")) -> {
                    val number = line.substringBefore(". ")
                    append("$number. ")
                    pushStyle(baseStyle.toSpanStyle())
                    append(processInlineFormatting(line.substringAfter(". "), boldStyle, italicStyle, codeStyle, linkStyle, linkInteractionListener))
                    pop()
                }
                else -> {
                    pushStyle(baseStyle.toSpanStyle())
                    append(processInlineFormatting(line, boldStyle, italicStyle, codeStyle, linkStyle, linkInteractionListener))
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
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    linkInteractionListener: LinkInteractionListener
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        // 使用 Regex 统一捕获加粗、斜体、代码块和链接，大幅简化原本复杂的嵌套判断逻辑
        val inlineRegex = Regex("""(\*\*.*?\*\*|\*.*?\*|`.*?`|\[.*?]\([^)]+\))""")
        var lastIndex = 0
        val results = inlineRegex.findAll(text)

        for (match in results) {
            // 拼接匹配项之前的普通文本
            append(text.substring(lastIndex, match.range.first))

            val matchText = match.value
            when {
                matchText.startsWith("**") -> {
                    withStyle(boldStyle) { append(matchText.removeSurrounding("**")) }
                }
                matchText.startsWith("*") -> {
                    withStyle(italicStyle) { append(matchText.removeSurrounding("*")) }
                }
                matchText.startsWith("`") -> {
                    withStyle(codeStyle) { append(matchText.removeSurrounding("`")) }
                }
                matchText.startsWith("[") -> {
                    // 解析 [text](url) 结构
                    val bracketEnd = matchText.indexOf("]")
                    val parenStart = matchText.indexOf("(", bracketEnd)
                    val parenEnd = matchText.indexOf(")", parenStart)

                    if (bracketEnd != -1 && parenStart != -1 && parenEnd != -1) {
                        val linkText = matchText.substring(1, bracketEnd)
                        val linkUrl = matchText.substring(parenStart + 1, parenEnd)

                        withLink(LinkAnnotation.Clickable(tag = linkUrl, linkInteractionListener = linkInteractionListener)) {
                            withStyle(linkStyle) { append(linkText) }
                        }
                    } else {
                        append(matchText)
                    }
                }
                else -> append(matchText)
            }
            lastIndex = match.range.last + 1
        }
        // 拼接尾部剩余文本
        append(text.substring(lastIndex))
    }
}