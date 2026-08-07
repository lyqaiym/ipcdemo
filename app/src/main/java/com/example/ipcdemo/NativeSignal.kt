package com.example.ipcdemo

/**
 * Per-process POSIX signal channel. Only works between processes of the same UID.
 */
object NativeSignal {

    init {
        System.loadLibrary("ipcdemo")
    }

    class Packet(val senderPid: Int, val value: Int)

    @Volatile
    var onPacket: ((Packet) -> Unit)? = null

    val signalNumber: Int get() = nativeSignalNumber()

    private var receiver: Thread? = null

    @Synchronized
    fun start() {
        val errno = nativeInstall()
        check(errno == 0) { "sigaction failed, errno=$errno" }
        if (receiver != null) return
        receiver = Thread(::receiveLoop, "signal-receiver").apply {
            isDaemon = true
            start()
        }
    }

    fun send(targetPid: Int, value: Int): Int = nativeSend(targetPid, value)

    private fun receiveLoop() {
        while (true) {
            val raw = nativeWaitOne()
            if (raw < 0) return
            onPacket?.invoke(Packet((raw ushr 32).toInt(), raw.toInt()))
        }
    }

    private external fun nativeSignalNumber(): Int
    private external fun nativeInstall(): Int
    private external fun nativeSend(targetPid: Int, value: Int): Int
    private external fun nativeWaitOne(): Long
}