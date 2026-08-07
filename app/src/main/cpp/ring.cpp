#include <jni.h>

#include <android/log.h>
#include <android/sharedmem.h>
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <unistd.h>

// Single-producer / single-consumer ring buffer living in shared memory. The two
// eventfds are counting semaphores (EFD_SEMAPHORE) that both signal readiness and
// provide the memory barriers, so the shared region itself needs no atomics or locks:
// each side only ever touches slots it holds a permit for.

namespace {

constexpr int kTimeoutMs = 5000;
constexpr size_t kFrameIdSize = sizeof(uint64_t);

struct Ring {
    int shm_fd = -1;
    int space_fd = -1;  // permits for empty slots, starts at slot count
    int data_fd = -1;   // permits for filled slots, starts at 0
    uint8_t* base = nullptr;
    size_t slot_size = 0;
    uint32_t slots = 0;
    uint32_t cursor = 0;  // private to whichever side owns this Ring
    int waits = 0;
};

Ring* AsRing(jlong handle) {
    return reinterpret_cast<Ring*>(handle);
}

bool Readable(int fd) {
    pollfd poll_fd{fd, POLLIN, 0};
    return poll(&poll_fd, 1, 0) == 1;
}

bool SemWait(int fd) {
    pollfd poll_fd{fd, POLLIN, 0};
    int ready;
    do {
        ready = poll(&poll_fd, 1, kTimeoutMs);
    } while (ready < 0 && errno == EINTR);
    if (ready != 1) return false;

    eventfd_t permits;
    return eventfd_read(fd, &permits) == 0;
}

bool SemPost(int fd) {
    return eventfd_write(fd, 1) == 0;
}

void Release(Ring* ring) {
    if (ring->base != nullptr) munmap(ring->base, ring->slots * ring->slot_size);
    if (ring->shm_fd >= 0) close(ring->shm_fd);
    if (ring->space_fd >= 0) close(ring->space_fd);
    if (ring->data_fd >= 0) close(ring->data_fd);
    delete ring;
}

jlong Map(Ring* ring) {
    void* mapped = mmap(nullptr, ring->slots * ring->slot_size, PROT_READ | PROT_WRITE,
                        MAP_SHARED, ring->shm_fd, 0);
    if (mapped == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_WARN, "RingIPC", "mmap failed: %s", strerror(errno));
        Release(ring);
        return 0;
    }
    ring->base = static_cast<uint8_t*>(mapped);
    return reinterpret_cast<jlong>(ring);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ipcdemo_NativeRing_create(JNIEnv*, jobject, jint slots, jint slot_size) {
    auto* ring = new Ring();
    ring->slots = slots;
    ring->slot_size = slot_size;
    // minSdk is 24 while ASharedMemory_create arrived in 26, so the call needs a guard
    // even though the Kotlin side already gates the feature.
    if (__builtin_available(android 26, *)) {
        ring->shm_fd = ASharedMemory_create("ipcdemo-ring", ring->slots * ring->slot_size);
    }
    ring->space_fd = eventfd(slots, EFD_CLOEXEC | EFD_SEMAPHORE);
    ring->data_fd = eventfd(0, EFD_CLOEXEC | EFD_SEMAPHORE);
    if (ring->shm_fd < 0 || ring->space_fd < 0 || ring->data_fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, "RingIPC", "create failed: %s", strerror(errno));
        Release(ring);
        return 0;
    }
    return Map(ring);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ipcdemo_NativeRing_attach(JNIEnv*, jobject, jint shm_fd, jint space_fd,
                                           jint data_fd, jint slots, jint slot_size) {
    auto* ring = new Ring();
    ring->shm_fd = shm_fd;
    ring->space_fd = space_fd;
    ring->data_fd = data_fd;
    ring->slots = slots;
    ring->slot_size = slot_size;
    return Map(ring);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_ipcdemo_NativeRing_exportFds(JNIEnv* env, jobject, jlong handle) {
    Ring* ring = AsRing(handle);
    jint fds[3] = {dup(ring->shm_fd), dup(ring->space_fd), dup(ring->data_fd)};
    jintArray result = env->NewIntArray(3);
    env->SetIntArrayRegion(result, 0, 3, fds);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ipcdemo_NativeRing_produce(JNIEnv*, jobject, jlong handle, jlong frame_id) {
    Ring* ring = AsRing(handle);
    if (!Readable(ring->space_fd)) ring->waits++;
    if (!SemWait(ring->space_fd)) return -1;

    // In a real pipeline the camera or codec would write straight into this slot;
    // nothing is copied between the processes either way.
    uint8_t* slot = ring->base + static_cast<size_t>(ring->cursor) * ring->slot_size;
    memcpy(slot, &frame_id, kFrameIdSize);
    memset(slot + kFrameIdSize, static_cast<int>(frame_id & 0xff), ring->slot_size - kFrameIdSize);

    ring->cursor = (ring->cursor + 1) % ring->slots;
    return SemPost(ring->data_fd) ? 0 : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ipcdemo_NativeRing_consume(JNIEnv*, jobject, jlong handle) {
    Ring* ring = AsRing(handle);
    if (!SemWait(ring->data_fd)) return -1;

    uint8_t* slot = ring->base + static_cast<size_t>(ring->cursor) * ring->slot_size;
    uint64_t frame_id;
    memcpy(&frame_id, slot, kFrameIdSize);

    auto expected = static_cast<uint8_t>(frame_id & 0xff);
    bool intact = true;
    for (size_t i = kFrameIdSize; i < ring->slot_size; ++i) {
        if (slot[i] != expected) {
            intact = false;
            break;
        }
    }

    ring->cursor = (ring->cursor + 1) % ring->slots;
    if (!SemPost(ring->space_fd)) return -1;
    return intact ? static_cast<jlong>(frame_id) : -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ipcdemo_NativeRing_waits(JNIEnv*, jobject, jlong handle) {
    return AsRing(handle)->waits;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ipcdemo_NativeRing_destroy(JNIEnv*, jobject, jlong handle) {
    Release(AsRing(handle));
}