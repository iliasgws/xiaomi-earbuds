package dev.earbuds.buds.protocol

/** Exact final native response, replay-verified against libxm_bluetooth ARM64. */
object Auth {
    private val seed = hex("112233332211")
    private val exp = IntArray(256).also { table ->
        var value = 1
        for (i in table.indices) {
            table[i] = value and 255
            value = (value * 45) % 257
        }
    }
    private val log = IntArray(256).also { table -> exp.forEachIndexed { i, v -> table[v] = i } }
    private val layers = arrayOf(
        intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),
        intArrayOf(0,7,2,1,4,3,6,5,8,11,10,9,12,15,14,13),
        intArrayOf(10,13,0,3,6,1,14,9,4,7,8,5,2,15,12,11),
        intArrayOf(14,7,0,13,8,15,6,11,4,9,2,5,10,1,12,3),
    )
    private val gather = intArrayOf(14,7,8,15,0,13,6,11,4,9,2,5,10,1,12,3)
    private fun xorPosition(i: Int) = ((0x9999 shr i) and 1) != 0

    fun response(challenge: ByteArray, customSeed: ByteArray = seed): ByteArray {
        require(challenge.size == 16) { "Authentication factor must be 16 bytes" }
        require(customSeed.size == 6) { "Authentication seed must be 6 bytes" }
        val key = challenge.map { it.toInt() and 255 }.toIntArray()
        key[15] = key[15] xor 6
        val ring = key + intArrayOf(key.fold(0) { a, b -> a xor b })
        val schedule = IntArray(272)
        key.copyInto(schedule)
        for (s in 1..16) {
            for (i in ring.indices) ring[i] = ((ring[i] shl 3) or (ring[i] shr 5)) and 255
            for (i in 0..15) schedule[16*s+i] =
                (ring[(s+i)%17] + exp[exp[(17*(s+1)+i+1)%256]]) and 255
        }
        val original = IntArray(16) { customSeed[it%6].toInt() and 255 }
        var state = original.copyOf()
        for (round in 0..7) {
            if (round == 2) for (i in state.indices) state[i] = if (xorPosition(i))
                state[i] xor original[i] else (state[i] + original[i]) and 255
            for (i in state.indices) {
                val first = schedule[32*round+i]
                state[i] = if (xorPosition(i)) state[i] xor first else (state[i]+first) and 255
                state[i] = if (xorPosition(i)) exp[state[i]] else log[state[i]]
                val second = schedule[32*round+16+i]
                state[i] = if (xorPosition(i)) (state[i]+second) and 255 else state[i] xor second
            }
            for (layer in layers) for (p in layer.indices step 2) {
                val i = layer[p]; val j = layer[p+1]
                val a = state[i]; val b = state[j]
                state[i] = (2*a+b) and 255; state[j] = (a+b) and 255
            }
            state = IntArray(16) { state[gather[it]] }
        }
        return ByteArray(16) { i -> (if (xorPosition(i)) state[i] xor schedule[256+i]
            else (state[i]+schedule[256+i]) and 255).toByte() }
    }
}

fun hex(text: String): ByteArray {
    require(text.length%2 == 0 && text.all { it in "0123456789abcdefABCDEF" }) { "Invalid hex bytes" }
    return ByteArray(text.length/2) { text.substring(it*2,it*2+2).toInt(16).toByte() }
}
fun ByteArray.hexString() = joinToString("") { "%02x".format(it.toInt() and 255) }
