package com.siyu.musique

import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliApiException
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.BiliParseException
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiServiceTest {

companion object {
// Fixtures trimmed from the live Nov 14, 2026 probe responses.
// Field names and values are verbatim; only unrelated fields
// were dropped.
const val SEARCH_ITEM_1 = """{"type":"video","id":113911642789603,"author":"无损音乐合集","arcurl":"http://www.bilibili.com/video/av113911642789603","aid":113911642789603,"bvid":"BV1q4FTeBEMj","title":"<em class=\"keyword\">周杰伦</em>歌曲精选100首 歌词版纯享 地表最高音质 每一首都是顶级文案 中国流行音乐开拓者JAY","description":"","pic":"//i0.hdslb.com/bfs/archive/25c409c43790cc13bf9ebc473b36c4f650527653.jpg","duration":"419:2","pubdate":1738326240}"""
const val SEARCH_ITEM_2 = """{"author":"超级爱下雨天","aid":116779154212963,"bvid":"BV1FPjy6TEiE","title":"50首精选合集/后台播放/无损音质/HIFI音质/华语流行音乐才是最叼的","pic":"//i0.hdslb.com/bfs/archive/3acdced5472e3db18d3cf861a8cb414247ebed4d.jpg","duration":"222:28"}"""
const val SEARCH_DATA = """{"page":1,"pagesize":20,"numResults":1000,"numPages":50,"result":[{"result_type":"bili_user","data":[{"uname":"someone","usign":"hi"}]},{"result_type":"video","data":[$SEARCH_ITEM_1,$SEARCH_ITEM_2]}]}"""
const val VIEW_DATA = """{"bvid":"BV1q4FTeBEMj","aid":113911642789603,"pic":"http://i2.hdslb.com/bfs/archive/25c409c43790cc13bf9ebc473b36c4f650527653.jpg","title":"周杰伦歌曲精选100首 歌词版纯享 地表最高音质 每一首都是顶级文案 中国流行音乐开拓者JAY","duration":25142,"cid":28139389900,"owner":{"mid":1346923466,"name":"无损音乐合集"},"pages":[{"cid":28139389900,"page":1,"part":"001. 夜曲-周杰伦","duration":228}]}"""
const val PAGELIST_DATA = """[{"cid":28139389900,"page":1,"part":"001. 夜曲-周杰伦","duration":228}]"""
const val NAV_DATA = """{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}"""
}

private fun service(
gateway: FakeGateway,
keys: WbiKeyStore = WbiKeyStore(
fetcher = {
WbiKeyStore.WbiKeys(WbiSignerTest.IMG_KEY, WbiSignerTest.SUB_KEY)
},
),
): ApiService = ApiService(
BiliClient(
gateway = gateway,
cookieJar = CookieJar(),
wbiKeys = keys,
sleeper = {},
jitter = { 0.5},
nowSeconds = { 1794710448L},
),
)

@Test
fun `search parses video results only, cleaning titles and covers`() = runTest {
val gateway = FakeGateway()
gateway.enqueue(envelopeResponse(0, SEARCH_DATA))
val tracks = service(gateway).search("周杰伦")

assertEquals(2, tracks.size)
val first = tracks[0]
assertEquals("BV1q4FTeBEMj", first.id)
assertEquals("", first.cid)
assertEquals(
"周杰伦歌曲精选100首 歌词版纯享 地表最高音质 每一首都是顶级文案 中国流行音乐开拓者JAY",
first.title,
)
assertEquals("无损音乐合集", first.artist)
assertEquals(
"https://i0.hdslb.com/bfs/archive/25c409c43790cc13bf9ebc473b36c4f650527653.jpg",
first.cover,
)
// "419:2" is 419 minutes 2 seconds, never hours.
assertEquals(25_142_000L, first.duration.inWholeMilliseconds)

val second = tracks[1]
assertEquals("BV1FPjy6TEiE", second.id)
assertEquals(13_348_000L, second.duration.inWholeMilliseconds)
}

@Test
fun `search sends keyword only, unsigned, first page only`() = runTest {
val gateway = FakeGateway()
gateway.enqueue(envelopeResponse(0, SEARCH_DATA))
service(gateway).search("周杰伦")
val url = gateway.requests.single().url
assertTrue(url.startsWith("https://api.bilibili.com/x/web-interface/search/all/v2?keyword="))
assertTrue(!url.contains("w_rid"))
assertTrue(!url.contains("page="))
assertTrue(!url.contains("pn="))
}

@Test
fun `search with no video results returns an empty list`() = runTest {
val gateway = FakeGateway()
gateway.enqueue(
envelopeResponse(
0,
"""{"result":[{"result_type":"bili_user","data":[]}]}""",
),
)
assertTrue(service(gateway).search("zzz-no-such-song").isEmpty())
}

@Test
fun `search without a result array throws BiliParseException`() = runTest {
val gateway = FakeGateway()
gateway.enqueue(envelopeResponse(0, """{"numResults":0}"""))
try {
service(gateway).search("test")
fail("expected BiliParseException")
} catch (_: BiliParseException) {
// expected
}
}

@Test
fun `detail uses the signed wbi view path and fills cid from pages`() = runTest {
val gateway = FakeGateway()
gateway.enqueue(envelopeResponse(0, VIEW_DATA))
val track = service(gateway).fetchDetail("BV1q4FTeBEMj")

val url = gateway.requests.single().url
assertTrue(url.startsWith("https://api.bilibili.com/x/web-interface/wbi/view?"))
assertTrue(url.contains("bvid=BV1q4FTeBEMj"))
assertTrue(url.contains("w_rid="))

assertEquals("BV1q4FTeBEMj", track.id)
assertEquals("28139389900", track.cid)
assertEquals("无损音乐合集", track.artist)
assertEquals(228_000L, track.duration.inWholeMilliseconds)
assertTrue(track.cover.startsWith("http://i2.hdslb.com/"))
}

@Test
fun `detail falls back to pagelist when wbi view fails`() = runTest {
val gateway = FakeGateway()
gateway.handler = { request ->
when {
request.url.contains("wbi/view") ->
envelopeResponse(-412, "null", message = "banned")
request.url.contains("pagelist") ->
envelopeResponse(0, PAGELIST_DATA)
else -> error("unexpected ${request.url}")
}
}
val track = service(gateway).fetchDetail("BV1q4FTeBEMj")
assertEquals("28139389900", track.cid)
assertEquals("", track.title)
assertEquals(228_000L, track.duration.inWholeMilliseconds)
assertTrue(gateway.requests.any { it.url.contains("pagelist")})
// The pagelist fallback is unsigned.
val pagelistUrl = gateway.requests.first { it.url.contains("pagelist")}.url
assertTrue(!pagelistUrl.contains("w_rid"))
}

@Test
fun `detail throws when both view and pagelist fail`() = runTest {
val gateway = FakeGateway()
gateway.handler = { envelopeResponse(-412, "null", message = "banned")}
try {
service(gateway).fetchDetail("BV1q4FTeBEMj")
fail("expected an exception")
} catch (e: BiliApiException) {
assertEquals(-412, e.code)
}
}

@Test
fun `nav keys parse from wbi_img despite the minus 101 code`() = runTest {
val gateway = FakeGateway()
gateway.enqueue(envelopeResponse(-101, NAV_DATA, message = "not logged in"))
val keys = service(gateway).fetchNavKeys()
assertEquals(WbiSignerTest.IMG_KEY, keys.imgKey)
assertEquals(WbiSignerTest.SUB_KEY, keys.subKey)
}
}
