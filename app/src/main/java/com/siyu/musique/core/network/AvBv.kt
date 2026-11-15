package com.siyu.musique.core.network

/**
 * AV number to BV id conversion, ported from NaivG/bilimusic's
 * av_bv.dart and verified against 20 live aid/bvid pairs from the
 * search API (Nov 14, 2026 probe) plus the classic av170001 ->
 * BV17x411w7KC pair.
 *
 * Algorithm: tmp = ((1 << 51) | aid) xor 23442827791579, base-58
 * encode tmp from the end of the "BV1000000000" template using the
 * table below, then swap positions 3/9 and 4/7.
 */
fun av2bv(aid: Long): String {
    require(aid in 1 until (1L shl 51)) { "aid out of range: $aid" }
    val table = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    val chars = "BV1000000000".toCharArray()
    var tmp = ((1L shl 51) or aid) xor 23442827791579L
    var index = chars.size - 1
    while (tmp > 0) {
        chars[index] = table[(tmp % 58L).toInt()]
        tmp /= 58L
        index--
    }
    chars.swap(3, 9)
    chars.swap(4, 7)
    return chars.concatToString()
}

private fun CharArray.swap(i: Int, j: Int) {
    val temp = this[i]
    this[i] = this[j]
    this[j] = temp
}
