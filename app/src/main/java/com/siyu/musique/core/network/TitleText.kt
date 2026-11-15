package com.siyu.musique.core.network

private val emOpenTag = Regex("<em[^>]*>", RegexOption.IGNORE_CASE)
private val emCloseTag = Regex("</em>", RegexOption.IGNORE_CASE)
private val entity = Regex("&(#(x|X)?([0-9a-fA-F]+)|amp|lt|gt|quot|apos|nbsp);")

private val namedEntities = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

/** Removes the <em class="keyword"> highlight tags search results wrap keywords in. */
fun stripEmTags(title: String): String =
    title.replace(emOpenTag, "").replace(emCloseTag, "")

/**
 * Unescapes HTML entities in one pass: numeric entities (&#39;,
 * &#x27;) and the common named ones. Decoding happens once, so
 * "&amp;lt;" becomes "&lt;", not "<".
 */
fun unescapeHtmlEntities(text: String): String {
    return entity.replace(text) { match ->
        val body = match.groupValues[1]
        if (body.startsWith("#")) {
            val digits = match.groupValues[3]
            val isHex = match.groupValues[2].isNotEmpty()
            val codePoint = digits.toIntOrNull(if (isHex) 16 else 10)
            if (codePoint == null || codePoint < 0 || codePoint > 0x10FFFF) {
                match.value
            } else {
                String(Character.toChars(codePoint))
            }
        } else {
            namedEntities[body] ?: match.value
        }
    }
}

/** Search-result title cleanup: strip highlight tags, then unescape entities. */
fun cleanSearchTitle(rawTitle: String): String = unescapeHtmlEntities(stripEmTags(rawTitle))
