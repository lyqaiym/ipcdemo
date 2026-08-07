#include <jni.h>
#include <string>

#include <android/log.h>
#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ipcdemo_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

namespace {

struct Packet {
    int32_t sender_pid;
    int32_t value;
};

int g_pipe[2] = {-1, -1};

// Runs on whichever thread the kernel picks, so only async-signal-safe calls are
// allowed here. A single write of sizeof(Packet) bytes is atomic (< PIPE_BUF).
void OnSignal(int /* sig */, siginfo_t* info, void* /* ucontext */) {
    Packet packet{info->si_pid, info->si_value.sival_int};
    ssize_t written = write(g_pipe[1], &packet, sizeof(packet));
    (void) written;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ipcdemo_NativeSignal_nativeSignalNumber(JNIEnv*, jobject) {
    // bionic's SIGRTMIN already skips the realtime signals reserved by the
    // platform (POSIX timers, debuggerd, profilers, ART, fdtrack).
    return SIGRTMIN;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ipcdemo_NativeSignal_nativeInstall(JNIEnv*, jobject) {
    if (g_pipe[0] == -1 && pipe2(g_pipe, O_CLOEXEC) != 0) {
        return errno;
    }

    struct sigaction action = {};
    action.sa_sigaction = OnSignal;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigfillset(&action.sa_mask);
    if (sigaction(SIGRTMIN, &action, nullptr) != 0) {
        return errno;
    }

    __android_log_print(ANDROID_LOG_INFO, "SignalIPC",
                        "pid=%d installed handler for signal %d", getpid(), SIGRTMIN);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ipcdemo_NativeSignal_nativeSend(JNIEnv*, jobject, jint target_pid, jint value) {
    union sigval payload;
    payload.sival_int = value;
    if (sigqueue(target_pid, SIGRTMIN, payload) != 0) {
        return errno;
    }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ipcdemo_NativeSignal_nativeWaitOne(JNIEnv*, jobject) {
    Packet packet = {};
    ssize_t read_bytes;
    do {
        read_bytes = read(g_pipe[0], &packet, sizeof(packet));
    } while (read_bytes < 0 && errno == EINTR);

    if (read_bytes != sizeof(packet)) {
        return -1;
    }
    return (static_cast<jlong>(static_cast<uint32_t>(packet.sender_pid)) << 32) |
           static_cast<uint32_t>(packet.value);
}