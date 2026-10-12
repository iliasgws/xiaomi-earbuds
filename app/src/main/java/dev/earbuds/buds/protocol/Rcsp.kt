package dev.earbuds.buds.protocol

class ProtocolException(message: String) : Exception(message)
class AuthenticationException(message: String) : Exception(message)
class DeviceException(val opcode: Int, val status: Int) :
    Exception("Earbuds rejected command 0x${opcode.toString(16)} (status $status)")

data class Packet(
    val flags: Int, val opcode: Int, val sequence: Int,
    val payload: ByteArray = byteArrayOf(), val status: Int = 0,
    val xmOpcode: Int? = null,
) {
    val isRequest get() = flags and 0x80 != 0
    val responseRequired get() = flags and 0x40 != 0
    val target get() = flags and 7
    fun encode(): ByteArray {
        require(flags in 0..255 && opcode in 0..255 && sequence in 0..255 && status in 0..255)
        val fields = if (isRequest) {
            byteArrayOf(sequence.toByte()) + if (opcode == 1) byteArrayOf(requireNotNull(xmOpcode).toByte()) else byteArrayOf()
        } else byteArrayOf(status.toByte(), if (opcode == 1) requireNotNull(xmOpcode).toByte() else sequence.toByte())
        val body = fields + payload
        require(body.size <= 65535)
        return hex("fedcba") + byteArrayOf(flags.toByte(), opcode.toByte(),
            (body.size shr 8).toByte(), body.size.toByte()) + body + byteArrayOf(0xef.toByte())
    }
    fun reply(data: ByteArray = byteArrayOf(), result: Int = 0) =
        Packet(4,opcode,sequence,data,result,xmOpcode)
    companion object {
        fun command(opcode: Int, sequence: Int, payload: ByteArray) = Packet(0xc4,opcode,sequence,payload)
        fun decode(bytes: ByteArray): Packet {
            if (bytes.size < 8 || !bytes.copyOfRange(0,3).contentEquals(hex("fedcba")) || bytes.last().u() != 239)
                throw ProtocolException("Invalid RCSP envelope")
            val length = bytes[5].u()*256+bytes[6].u()
            if (length+8 != bytes.size) throw ProtocolException("Invalid RCSP length")
            val flags=bytes[3].u(); val opcode=bytes[4].u(); val request=flags and 128 != 0
            val minimum=if (!request || opcode==1) 2 else 1
            if (length<minimum) throw ProtocolException("Missing packet fields")
            val sequence=if (request) bytes[7].u() else bytes[8].u()
            val start=if (!request || opcode==1) 9 else 8
            return Packet(flags,opcode,sequence,bytes.copyOfRange(start,bytes.size-1),
                if (request) 0 else bytes[7].u(), if (opcode==1) bytes[8].u() else null)
        }
    }
}

class FrameDecoder(private val maxBody: Int = 4096) {
    private var buffer=byteArrayOf()
    var discardedBytes=0; private set
    fun feed(chunk: ByteArray): List<Packet> {
        buffer+=chunk
        val result=mutableListOf<Packet>()
        while (true) {
            val start=(0..buffer.size-3).firstOrNull { buffer[it].u()==254 && buffer[it+1].u()==220 && buffer[it+2].u()==186 }
            if (start==null) {
                val keep=if (buffer.size>=2 && buffer[buffer.size-2].u()==254 && buffer.last().u()==220) 2
                    else if (buffer.isNotEmpty() && buffer.last().u()==254) 1 else 0
                discardedBytes+=buffer.size-keep
                buffer=buffer.takeLast(keep).toByteArray(); break
            }
            discardedBytes+=start; buffer=buffer.drop(start).toByteArray()
            if (buffer.size<7) break
            val length=buffer[5].u()*256+buffer[6].u()
            if (length>maxBody) throw ProtocolException("RCSP body exceeds $maxBody bytes")
            if (buffer.size<length+8) break
            try {
                result+=Packet.decode(buffer.copyOfRange(0,length+8))
                buffer=buffer.drop(length+8).toByteArray()
            } catch (_: ProtocolException) {
                discardedBytes++; buffer=buffer.drop(1).toByteArray()
            }
        }
        return result
    }
}

fun Byte.u() = toInt() and 255
fun configRecord(id: Int, value: ByteArray): ByteArray {
    require(id in 0..65535 && value.size+2<255)
    return byteArrayOf((value.size+2).toByte(),(id shr 8).toByte(),id.toByte())+value
}
fun configIds(ids: List<Int>): ByteArray {
    require(ids.isNotEmpty() && ids.all { it in 0..65535 })
    return ids.flatMap { listOf((it shr 8).toByte(),it.toByte()) }.toByteArray()
}
fun configValues(data: ByteArray): Map<Int,ByteArray> {
    val values=linkedMapOf<Int,ByteArray>(); var offset=0
    while (offset<data.size) {
        if (offset+3>data.size) throw ProtocolException("Truncated config header")
        val length=data[offset].u(); val id=data[offset+1].u()*256+data[offset+2].u()
        val end=if (length==255) {
            if (id!=52 || offset+5>data.size) throw ProtocolException("Unsupported extended config")
            offset+5+data[offset+3].u()*256+data[offset+4].u()
        } else {
            if (length<2) throw ProtocolException("Invalid config length")
            offset+1+length
        }
        if (end>data.size) throw ProtocolException("Truncated config value")
        val value=data.copyOfRange(offset+3,end)
        if (id in values && !values.getValue(id).contentEquals(value)) throw ProtocolException("Conflicting configs")
        values[id]=value; offset=end
    }
    return values
}
