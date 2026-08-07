package com.example.ipcdemo

import androidx.appcompat.app.AppCompatActivity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import android.view.View
import androidx.annotation.RequiresApi
import com.example.ipcdemo.databinding.ActivityMainBinding
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @Volatile
    private var remotePid = 0
    private var counter = 0

    private lateinit var ioThread: HandlerThread
    private lateinit var io: Handler
    private var client: LocalIpc.Client? = null
    private var socketCounter = 0

    @Volatile
    private var remote: Messenger? = null
    private var shmCounter = 0

    @Volatile
    private var lastChecksum = 0

    private val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == ShmIpc.MSG_RESULT) {
            val verdict = if (msg.arg2 == lastChecksum) "一致" else "不一致"
            log("<- :remote 读到 ${msg.arg1}B checksum=${msg.arg2} ($verdict)")
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()

        NativeSignal.onPacket = { packet -> runOnUiThread { onPacket(packet) } }
        NativeSignal.start()

        binding.status.text = "main pid=${Process.myPid()}  SIGRTMIN=${NativeSignal.signalNumber}"
        bindService(
            Intent(this, RemoteService::class.java)
                .putExtra(RemoteService.EXTRA_MAIN_PID, Process.myPid()),
            connection,
            Context.BIND_AUTO_CREATE
        )

        ioThread = HandlerThread("socket-client").apply { start() }
        io = Handler(ioThread.looper)

        binding.send.setOnClickListener { send() }
        binding.sendSocket.setOnClickListener { io.post { sendOverSocket() } }
        binding.sendShm.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        binding.sendShm.setOnClickListener {
            io.post {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) shareMemory()
            }
        }
    }

    override fun onDestroy() {
        io.post { client?.close() }
        ioThread.quitSafely()
        unbindService(connection)
        super.onDestroy()
    }

    private fun sendOverSocket() {
        try {
            val connected = client ?: LocalIpc.Client().also {
                client = it
                log("socket 已连上 :remote pid=${it.peerPid}")
            }
            val text = "hello #${++socketCounter} from pid=${Process.myPid()}"
            log("-> $text")
            log("<- " + connected.request(text))
        } catch (e: IOException) {
            client = null
            log("socket 出错: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun shareMemory() {
        val target = remote
        if (target == null) {
            log(":remote 还没绑定上")
            return
        }
        try {
            val seed = ++shmCounter
            val shm = SharedMemory.create("ipcdemo-shm", ShmIpc.SIZE)
            val buffer = shm.mapReadWrite()
            ShmIpc.fill(buffer, seed)
            buffer.rewind()
            lastChecksum = ShmIpc.checksum(buffer)
            SharedMemory.unmap(buffer)
            // Drop write permission before handing the fd over, so :remote can only read.
            shm.setProtect(OsConstants.PROT_READ)

            val message = Message.obtain(null, ShmIpc.MSG_WRITE)
            message.data = Bundle().apply { putParcelable(ShmIpc.KEY_SHM, shm) }
            message.replyTo = replyTo
            target.send(message)
            shm.close()
            log("-> shm ${ShmIpc.SIZE / 1024}KiB seed=$seed checksum=$lastChecksum，binder 只过了一个 fd")
        } catch (e: Exception) {
            log("shm 出错: $e")
        }
    }

    private fun send() {
        val target = remotePid
        if (target == 0) {
            log(":remote 还没上线")
            return
        }
        val value = ++counter
        val errno = NativeSignal.send(target, value)
        if (errno == 0) {
            log("-> sigqueue(pid=$target, value=$value)")
        } else {
            log("-> 发送失败 errno=$errno")
        }
    }

    private fun onPacket(packet: NativeSignal.Packet) {
        if (remotePid == 0) {
            remotePid = packet.senderPid
            log(":remote 上线 pid=${packet.senderPid}")
        } else {
            log("<- pid=${packet.senderPid} 回包 value=${packet.value}")
        }
    }

    private fun log(line: String) = runOnUiThread {
        binding.log.append(line + "\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * A native method that is implemented by the 'ipcdemo' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'ipcdemo' library on application startup.
        init {
            System.loadLibrary("ipcdemo")
        }
    }
}