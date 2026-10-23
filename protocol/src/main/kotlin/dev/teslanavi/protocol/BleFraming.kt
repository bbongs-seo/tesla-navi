package dev.teslanavi.protocol

/** Tesla BLE uses a two-byte big-endian length followed by a protobuf message. */
class BleFraming(private val maxSize: Int = 1024) {
    private var pending = byteArrayOf()
    private var lastChunkAt: Long? = null

    @Synchronized
    fun accept(chunk: ByteArray, elapsedMs: Long): List<ByteArray> {
        if (lastChunkAt?.let { elapsedMs - it > 1000 } == true) pending = byteArrayOf()
        lastChunkAt = elapsedMs
        pending += chunk
        val messages = mutableListOf<ByteArray>()
        while (pending.size >= 2) {
            val size = ((pending[0].toInt() and 255) shl 8) or (pending[1].toInt() and 255)
            if (size !in 1..maxSize) {
                pending = byteArrayOf()
                throw IllegalArgumentException("잘못된 차량 메시지 길이: $size")
            }
            if (pending.size < size + 2) break
            messages += pending.copyOfRange(2, size + 2)
            pending = pending.copyOfRange(size + 2, pending.size)
        }
        return messages
    }

    companion object {
        fun encode(message: ByteArray, mtu: Int): List<ByteArray> {
            require(message.size in 1..1024) { "차량 메시지 크기 초과" }
            require(mtu >= 23)
            val frame = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message
            return frame.asList().chunked(minOf(mtu - 3, 512)).map { it.toByteArray() }
        }
    }
}
