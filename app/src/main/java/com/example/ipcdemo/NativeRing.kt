package com.example.ipcdemo

/**
 * Cross-process SPSC ring buffer: payload in shared memory, flow control over two
 * eventfd semaphores. Handles are native pointers, so every one must be [destroy]ed.
 */
object NativeRing {

    init {
        System.loadLibrary("ipcdemo")
    }

    const val SLOTS = 4
    const val SLOT_SIZE = 256 * 1024
    const val FRAMES = 240

    const val MSG_START = 3
    const val MSG_DONE = 4
    const val KEY_SHM = "ring_shm"
    const val KEY_SPACE = "ring_space"
    const val KEY_DATA = "ring_data"

    external fun create(slots: Int, slotSize: Int): Long
    external fun attach(shmFd: Int, spaceFd: Int, dataFd: Int, slots: Int, slotSize: Int): Long
    external fun exportFds(handle: Long): IntArray
    external fun produce(handle: Long, frameId: Long): Int
    external fun consume(handle: Long): Long
    external fun waits(handle: Long): Int
    external fun destroy(handle: Long)
}