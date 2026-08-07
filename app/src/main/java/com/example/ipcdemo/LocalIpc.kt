package com.example.ipcdemo

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.IOException

/**
 * Unix domain socket channel, for comparison with [NativeSignal]: unlike a signal it
 * carries arbitrary payloads, is ordered and gives the peer's credentials.
 */
object LocalIpc {

    private const val SOCKET_NAME = "com.example.ipcdemo.echo"
    private const val TAG = "SocketIPC"

    class EchoServer : Closeable {

        private val server = LocalServerSocket(SOCKET_NAME)

        init {
            Thread(::acceptLoop, "socket-accept").apply {
                isDaemon = true
                start()
            }
        }

        private fun acceptLoop() {
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    Log.i(TAG, "accept loop stopped: ${e.message}")
                    return
                }
                Thread({ serve(client) }, "socket-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun serve(client: LocalSocket) {
            client.use { socket ->
                // Abstract-namespace sockets have no filesystem permissions, so any app
                // that guesses the name can connect. The peer's uid is the only real gate.
                val peer = socket.peerCredentials
                if (peer.uid != Process.myUid()) {
                    Log.w(TAG, "rejecting uid=${peer.uid} pid=${peer.pid}")
                    return
                }
                Log.i(TAG, "accepted pid=${peer.pid} uid=${peer.uid}")

                val reader = socket.inputStream.bufferedReader()
                val writer = socket.outputStream.bufferedWriter()
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.i(TAG, "got ${line.length} chars: $line")
                    writer.write("echo(pid=${Process.myPid()}, ${line.toByteArray().size}B): $line\n")
                    writer.flush()
                }
            }
        }

        override fun close() = server.close()
    }

    class Client : Closeable {

        private val socket = LocalSocket().apply {
            connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        }
        private val reader = socket.inputStream.bufferedReader()
        private val writer = socket.outputStream.bufferedWriter()

        val peerPid: Int get() = socket.peerCredentials.pid

        fun request(text: String): String {
            writer.write(text)
            writer.write("\n")
            writer.flush()
            return reader.readLine() ?: throw IOException("peer closed the connection")
        }

        override fun close() = socket.close()
    }
}
