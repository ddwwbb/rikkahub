package me.rerere.tts.controller

import java.io.IOException

/** Single-consumer byte pipe. Backpressure bounds memory independently of provider chunk sizes. */
internal class VoiceAudioPipe(capacity: Int = 256 * 1024) {
    private val bytes = ByteArray(capacity.also { require(it > 0) })
    private val lock = Object()
    private var start = 0
    private var size = 0
    private var ended = false
    private var failure: IOException? = null

    fun write(source: ByteArray) {
        var offset = 0
        synchronized(lock) {
            while (offset < source.size) {
                while (size == bytes.size && failure == null && !ended) lock.wait()
                failure?.let { throw it }
                check(!ended) { "Audio stream already ended" }
                val end = (start + size) % bytes.size
                val count = minOf(source.size - offset, bytes.size - size, bytes.size - end)
                source.copyInto(bytes, end, offset, offset + count)
                size += count
                offset += count
                lock.notifyAll()
            }
        }
    }

    fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length)
        if (length == 0) return 0
        synchronized(lock) {
            while (size == 0 && !ended && failure == null) lock.wait()
            failure?.let { throw it }
            if (size == 0) return -1
            val count = minOf(length, size, bytes.size - start)
            bytes.copyInto(target, offset, start, start + count)
            start = (start + count) % bytes.size
            size -= count
            lock.notifyAll()
            return count
        }
    }

    fun finish() = synchronized(lock) {
        ended = true
        lock.notifyAll()
    }

    fun abort(cause: Throwable? = null) = synchronized(lock) {
        failure = IOException("Voice audio stream stopped", cause)
        size = 0
        ended = true
        lock.notifyAll()
    }
}
