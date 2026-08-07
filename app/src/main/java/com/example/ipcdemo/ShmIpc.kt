package com.example.ipcdemo

import java.nio.ByteBuffer

/**
 * Shared memory channel: only the file descriptor crosses Binder, so the payload is
 * not bounded by the ~1 MB Binder transaction limit and is never copied.
 */
object ShmIpc {

    const val MSG_WRITE = 1
    const val MSG_RESULT = 2
    const val KEY_SHM = "shm"
    const val SIZE = 1 shl 20

    fun fill(buffer: ByteBuffer, seed: Int) {
        val chunk = "payload #$seed ".toByteArray()
        while (buffer.remaining() >= chunk.size) buffer.put(chunk)
        while (buffer.hasRemaining()) buffer.put(0)
    }

    fun checksum(buffer: ByteBuffer): Int {
        var sum = 0
        while (buffer.hasRemaining()) sum = sum * 31 + buffer.get().toInt()
        return sum
    }
}