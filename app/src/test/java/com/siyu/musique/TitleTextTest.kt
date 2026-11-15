package com.siyu.musique

import com.siyu.musique.core.network.cleanSearchTitle
import com.siyu.musique.core.network.stripEmTags
import com.siyu.musique.core.network.unescapeHtmlEntities
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleTextTest {

@Test
fun `em highlight tags are stripped, keeping the keyword text`() {
assertEquals(
"周杰伦歌曲精选100首",
stripEmTags("<em class=\"keyword\">周杰伦</em>歌曲精选100首"),
)
}

@Test
fun `the verbatim probe title cleans to plain text`() {
assertEquals(
"周杰伦歌曲精选100首 歌词版纯享 地表最高音质 每一首都是顶级文案 中国流行音乐开拓者JAY",
cleanSearchTitle(
"<em class=\"keyword\">周杰伦</em>歌曲精选100首 歌词版纯享 地表最高音质 每一首都是顶级文案 中国流行音乐开拓者JAY",
),
)
}

@Test
fun `named entities unescape`() {
assertEquals(
"A & B < C > \"D\" 'E'",
unescapeHtmlEntities("A &amp; B &lt; C &gt; &quot;D&quot; &#39;E&#39;"),
)
}

@Test
fun `numeric and hex entities unescape`() {
assertEquals("'", unescapeHtmlEntities("&#39;"))
assertEquals("'", unescapeHtmlEntities("&#x27;"))
assertEquals("&", unescapeHtmlEntities("&#38;"))
}

@Test
fun `decoding happens once, no double decode`() {
assertEquals("&lt;", unescapeHtmlEntities("&amp;lt;"))
}

@Test
fun `unknown entities and plain text pass through`() {
assertEquals("plain title", unescapeHtmlEntities("plain title"))
assertEquals("&foo;", unescapeHtmlEntities("&foo;"))
}
}
