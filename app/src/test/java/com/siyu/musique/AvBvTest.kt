package com.siyu.musique

import com.siyu.musique.core.network.av2bv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AvBvTest {

    @Test
    fun `all 20 live aid bvid pairs from the Nov 14 probe convert correctly`() {
        // aid/bvid pairs taken verbatim from the live search/all/v2
        // response (aid and bvid in the same video item).
        val pairs = mapOf(
            113911642789603L to "BV1q4FTeBEMj",
            116779154212963L to "BV1FPjy6TEiE",
            117600684083565L to "BV1FjWX61EXr",
            116796904507371L to "BV1kt7g61EGq",
            117602982561248L to "BV1mUWR6hEAH",
            1415480L to "BV1fx411N7bU",
            114115888551423L to "BV1emRNYmEuK",
            117530102404456L to "BV18gxq6HEgX",
            117414272437114L to "BV1cXpY6xEkt",
            929472589L to "BV1HK4y1D7WP",
            117599073475583L to "BV1c3WU6YET4",
            115093362709656L to "BV1WGvPzWEeR",
            117591272065359L to "BV1FFWg64E1G",
            117593654433204L to "BV16UWG6hEL2",
            117454890145415L to "BV1AuJv6XE4P",
            117601556631738L to "BV1noWD66ER6",
            117586910056752L to "BV1cJW86LEML",
            117414339746477L to "BV193pe6VEzQ",
            117597160872104L to "BV1UtWy6NE2V",
            114912554719208L to "BV1BZbSzZEGT",
        )
        for ((aid, expected) in pairs) {
            assertEquals("aid $aid", expected, av2bv(aid))
        }
    }

    @Test
    fun `classic small aid converts correctly`() {
        assertEquals("BV17x411w7KC", av2bv(170001L))
    }

    @Test
    fun `zero and negative aids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { av2bv(0L) }
        assertThrows(IllegalArgumentException::class.java) { av2bv(-5L) }
    }
}
