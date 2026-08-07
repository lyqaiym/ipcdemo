package com.example.ipcdemo

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SharedMemory
import android.system.ErrnoException
import android.util.Log
import androidx.annotation.RequiresApi

class RemoteService : Service() {

    private var echoServer: LocalIpc.EchoServer? = null
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        NativeSignal.onPacket = { packet ->
            val echo = packet.value * 2
            Log.i(TAG, "remote(pid=${Process.myPid()}) got value=${packet.value}" +
                    " from pid=${packet.senderPid}, echoing $echo")
            NativeSignal.send(packet.senderPid, echo)
        }
        NativeSignal.start()
        echoServer = LocalIpc.EchoServer()

        worker = HandlerThread("shm-worker").apply { start() }
        messenger = Messenger(Handler(worker.looper) { msg -> onMessage(msg); true })
    }

    override fun onDestroy() {
        echoServer?.close()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, 0)
        if (mainPid != 0) {
            // Announce ourselves over the signal channel itself: the main process
            // reads our pid out of siginfo.si_pid.
            val errno = NativeSignal.send(mainPid, Process.myPid())
            Log.i(TAG, "announced pid=${Process.myPid()} to $mainPid, errno=$errno")
        }
        return messenger.binder
    }

    private fun onMessage(msg: Message) {
        if (msg.what == ShmIpc.MSG_WRITE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            readSharedMemory(msg)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun readSharedMemory(msg: Message) {
        @Suppress("DEPRECATION")
        val shm = msg.data?.getParcelable<SharedMemory>(ShmIpc.KEY_SHM) ?: return
        try {
            shm.use {
                val buffer = it.mapReadOnly()
                val size = buffer.remaining()
                val checksum = ShmIpc.checksum(buffer)
                SharedMemory.unmap(buffer)
                Log.i(TAG, "mapped ${size}B read-only, checksum=$checksum")

                // The sender dropped PROT_WRITE before handing over the fd, so this fails.
                try {
                    SharedMemory.unmap(it.mapReadWrite())
                    Log.w(TAG, "write mapping unexpectedly succeeded")
                } catch (e: ErrnoException) {
                    Log.i(TAG, "write mapping refused: ${e.message}")
                }

                msg.replyTo?.send(Message.obtain(null, ShmIpc.MSG_RESULT, size, checksum))
            }
        } catch (e: Exception) {
            Log.w(TAG, "shared memory read failed", e)
        }
    }

    companion object {
        private const val TAG = "SignalIPC"
        const val EXTRA_MAIN_PID = "main_pid"
    }
}