package com.siyu.musique

import com.siyu.musique.core.network.SUGGESTION_LIMIT
import com.siyu.musique.core.network.parseSuggest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search suggest parser: plain `value` texts from result.tag,
 * in order. Suggestions are best-effort, so any misshaped input
 * yields an empty list instead of throwing.
 */
class SuggestParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = parseSuggest(json.parseToJsonElement(body))

    @Test
    fun `parses values in order and ignores the markup names`() {
        val suggestions = parse(ApiServiceTest.SUGGEST_BODY)
        assertEquals(
            listOf("周杰伦歌曲全集", "周杰伦", "周杰伦mv"),
            suggestions,
        )
        assertTrue(suggestions.none { it.contains("<em") })
    }

    @Test
    fun `falls back to term and skips blank values`() {
        val body = """{"code":0,"result":{"tag":[
            {"term":"only-term"},
            {"value":"","term":"blank-value"},
            {"value":"  ","term":"spaces-value"},
            {"value":"kept","term":"kept-term"},
            {"name":"no value or term"}
        ]}}"""
        assertEquals(
            listOf("only-term", "blank-value", "spaces-value", "kept"),
            parse(body),
        )
    }

    @Test
    fun `missing or misshaped nodes yield an empty list`() {
        assertTrue(parse("""{"code":0,"result":{}}""").isEmpty())
        assertTrue(parse("""{"code":0}""").isEmpty())
        assertTrue(parse("""{"code":0,"result":[]}""").isEmpty())
        assertTrue(parse("""{"code":0,"result":{"tag":{}}}""").isEmpty())
        assertTrue(parse("""{"code":0,"result":{"tag":["not-an-object"]}}""").isEmpty())
        assertTrue(parse("""[1,2,3]""").isEmpty())
        assertTrue(parse(""""just a string"""").isEmpty())
    }

    @Test
    fun `dedupes and caps at the suggestion limit`() {
        val values = listOf("dup", "dup") +
            (1..SUGGESTION_LIMIT + 3).map { "suggestion-$it" }
        val tags = values.joinToString(",") { value ->
            """{"value":"$value","term":"$value"}"""
        }
        val suggestions = parse("""{"code":0,"result":{"tag":[$tags]}}""")
        assertEquals(SUGGESTION_LIMIT, suggestions.size)
        assertEquals(suggestions.size, suggestions.toSet().size)
        assertEquals("dup", suggestions.first())
        assertEquals("suggestion-9", suggestions.last())
    }
}
