package dev.earbuds.buds.protocol

enum class NoiseMode(val code: Int, val title: String) {
    OFF(0,"Off"), ANC(1,"ANC"), TRANSPARENCY(2,"Transparency");
    companion object { fun from(code: Int) = entries.firstOrNull { it.code==code }
        ?: throw ProtocolException("Unknown noise mode $code") }
}
enum class EqPreset(val code: Int, val title: String) {
    BALANCED(0,"Balanced"), BASS(5,"Bass"), TREBLE(6,"Treble"), VOICE(1,"Voice"), CUSTOM(10,"Custom");
    companion object { fun from(code: Int) = entries.firstOrNull { it.code==code }
        ?: throw ProtocolException("Unknown EQ preset $code") }
}
enum class Gesture(val code: Int, val title: String) {
    SINGLE(4,"Single tap"), DOUBLE(1,"Double tap"), TRIPLE(2,"Triple tap"), HOLD(3,"Press and hold"), SWIPE(5,"Swipe");
    val allowedActions get() = when(this) {
        HOLD -> listOf(GestureAction.ASSISTANT,GestureAction.NOISE)
        SWIPE -> listOf(GestureAction.VOLUME,GestureAction.NONE)
        else -> listOf(GestureAction.PLAY,GestureAction.PREVIOUS,GestureAction.NEXT,
            GestureAction.VOLUME_UP,GestureAction.VOLUME_DOWN,GestureAction.NONE)
    }
}
enum class GestureAction(val code: Int, val title: String) {
    ASSISTANT(0,"Voice assistant"), PLAY(1,"Play / pause"), PREVIOUS(2,"Previous track"),
    NEXT(3,"Next track"), VOLUME_UP(4,"Volume up"), VOLUME_DOWN(5,"Volume down"),
    NOISE(6,"Noise control"), NONE(8,"Disabled"), VOLUME(11,"Adjust volume");
    companion object { fun from(code: Int) = entries.firstOrNull { it.code==code }
        ?: throw ProtocolException("Unsupported gesture action $code") }
}
data class Battery(val percent: Int, val charging: Boolean)
data class DeviceInfo(
    val name: String, val firmware: String, val vid: Int, val pid: Int,
    val left: Battery?, val right: Battery?, val case: Battery?,
)
data class EqBand(val frequency: Int, val gain: Int)
data class EqProfile(val mode: Int, val maxGain: Int, val minGain: Int, val bands: List<EqBand>)
data class Snapshot(
    val info: DeviceInfo, val mode: NoiseMode, val level: Int,
    val eq: EqPreset, val profile: EqProfile,
    val gestures: Map<Gesture,Pair<GestureAction,GestureAction>>,
    val adaptiveAnc: Boolean, val personalizedAnc: Boolean, val adaptiveSound: Boolean,
)

private fun version(bytes: ByteArray): String {
    if (bytes.size!=2 || bytes.any { (it.u() shr 4) > 9 || (it.u() and 15) > 9 })
        throw ProtocolException("Invalid firmware version")
    return bytes.flatMap { listOf(it.u() shr 4,it.u() and 15) }.joinToString(".")
}
fun targetInfo(data: ByteArray): DeviceInfo {
    val attributes=mutableMapOf<Int,ByteArray>(); var offset=0
    while (offset<data.size) {
        val length=data[offset].u(); val end=offset+length+1
        if (length<1 || end>data.size) throw ProtocolException("Truncated target info")
        attributes[data[offset+1].u()]=data.copyOfRange(offset+2,end); offset=end
    }
    val identity=attributes[3] ?: throw ProtocolException("Missing device identity")
    val firmware=attributes[1] ?: throw ProtocolException("Missing firmware version")
    if (identity.size!=4 || firmware.size<2) throw ProtocolException("Invalid device identity/version")
    val battery=attributes[7] ?: byteArrayOf()
    fun slot(i: Int): Battery? = battery.getOrNull(i)?.u()?.takeIf { it!=255 }
        ?.let { Battery(it and 127,it and 128 != 0) }
    return DeviceInfo(attributes[0]?.decodeToString() ?: "Redmi Buds 6 Pro",
        version(firmware.copyOfRange(0,2)), identity[0].u()*256+identity[1].u(),
        identity[2].u()*256+identity[3].u(),slot(0),slot(1),slot(2))
}
fun eqProfile(value: ByteArray): EqProfile {
    if (value.size<7 || value[0].u()!=1) throw ProtocolException("Unsupported EQ profile")
    val countOffset=6+value[5].u()
    if (countOffset>=value.size) throw ProtocolException("Truncated EQ name")
    val count=value[countOffset].u(); val start=countOffset+1
    if (value.size!=start+count*3) throw ProtocolException("EQ band count mismatch")
    val bands=(0 until count).map { i ->
        val p=start+i*3; val gain=value[p+2].u()
        EqBand(value[p].u()*256+value[p+1].u(),if (gain<128) gain else 128-gain)
    }
    return EqProfile(value[1].u(),value[2].u().takeIf { it!=0 } ?: 10,-value[3].u(),bands)
}
fun gestureValues(value: ByteArray): Map<Gesture,Pair<GestureAction,GestureAction>> {
    if (value.size%3!=0) throw ProtocolException("Invalid gesture triplets")
    return (value.indices step 3).associate { offset ->
        val gesture=Gesture.entries.firstOrNull { it.code==value[offset].u() }
            ?: throw ProtocolException("Unknown gesture")
        gesture to (GestureAction.from(value[offset+1].u()) to GestureAction.from(value[offset+2].u()))
    }
}
fun customEqValue(bands: List<EqBand>, preview: Boolean): ByteArray {
    require(bands.size in 1..80)
    val prefix=if (preview) hex("050101") else hex("010a01010100")
    return prefix+byteArrayOf(bands.size.toByte())+bands.flatMap { band ->
        require(band.frequency in 1..65535 && band.gain in -127..127)
        listOf((band.frequency shr 8).toByte(),band.frequency.toByte(),
            (if (band.gain<0) 128-band.gain else band.gain).toByte())
    }.toByteArray()
}
