package com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .softbreak("<br />\n")
        .build()

    fun renderDocument(markdown: String): String {
        val bodyHtml = renderer.render(parser.parse(markdown))
        return """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <style>
                    :root {
                        color-scheme: light dark;
                    }
                    body {
                        font-family: sans-serif;
                        margin: 16px;
                        line-height: 1.5;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                    pre {
                        padding: 12px;
                        border-radius: 8px;
                        overflow-x: auto;
                    }
                    code {
                        font-family: monospace;
                    }
                    table {
                        border-collapse: collapse;
                        width: 100%;
                    }
                    th, td {
                        border: 1px solid rgba(127,127,127,0.4);
                        padding: 6px 8px;
                    }
                </style>
            </head>
            <body>$bodyHtml</body>
            </html>
        """.trimIndent()
    }
}
