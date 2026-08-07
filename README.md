# ipcdemo

Android 上四种进程间通信机制的可运行对比：**POSIX 信号**、**Unix domain socket**、**共享内存单次传递**、**共享内存环形队列 + eventfd**。

同一个 app 内跑两个进程 —— 主进程（UI）和 `:remote`（`RemoteService`）—— 四条通道并存，点按钮即可观察每条通道的实际行为，结果同时输出到界面日志和 logcat。

## 运行

```bash
./gradlew installDebug          # 装到设备
adb logcat -s SignalIPC SocketIPC RingIPC
```

界面上四个按钮分别对应四种机制。`:remote` 进程在 Activity 绑定服务时启动。

## 进程结构

```
com.example.ipcdemo            主进程：UI、生产者、socket 客户端
com.example.ipcdemo:remote     RemoteService：信号回声、socket 服务端、消费者
```

两个进程共享同一个 UID，这是信号通道能工作的前提。`MainActivity` 用 `bindService` 而不是 `startService` 拉起 `:remote`（后者在 `onCreate` 里会撞上后台启动限制 `BackgroundServiceStartNotAllowedException`）。

## 四种机制

### 1. POSIX 信号

`app/src/main/cpp/native-lib.cpp` + `NativeSignal.kt`

`sigqueue()` 发送，`sigaction` 接收，实时信号可以携带一个 `int`。

- 信号号取 bionic 的 `SIGRTMIN`（本机实测 41），**不要写死数字**。bionic 把 `__SIGRTMIN`(32) 到 `__SIGRTMIN+8`(40) 预留给了 POSIX timer、debuggerd、heapprofd、ART profiler、fdtrack，而公开的 `SIGRTMIN` 宏是 `__libc_current_sigrtmin()`，已经跳过这些。`SIGQUIT`、`SIGUSR1`、`SIGSEGV`、`SIGBUS` 也被 ART 占用。
- 信号处理函数运行在内核随机挑的线程上，**只能调 async-signal-safe 函数**，绝不能 JNI 回调 Java。这里用经典的 self-pipe：handler 只 `write()` 一个 8 字节结构（小于 `PIPE_BUF`，单次写原子），真正的逻辑在普通线程 `read()` 之后跑。
- 对端 pid 无法靠信号自己发现。这里主进程通过 bind Intent 把自己 pid 告诉 `:remote`，`:remote` 再用 `sigqueue` 反向报到，主进程从 `siginfo.si_pid` 拿到对端 pid。

### 2. Unix domain socket

`LocalIpc.kt`

`LocalServerSocket` / `LocalSocket`，抽象命名空间，按行分隔的 UTF-8 文本。

- 能传任意长度数据，面向连接、有序不丢，是传控制命令的合理选择。
- `getPeerCredentials()` 返回的 pid/uid/gid 由内核填充（`SO_PEERCRED`），**不可伪造** —— 这是相比信号的一大优势。
- **安全要点**：抽象命名空间的 socket 不落文件系统，因此**没有文件权限保护**，任何 app 猜到名字都能连上来。服务端必须校验 `peerCredentials.uid`。如果要对外提供服务，正确做法是用文件系统命名空间的 socket 放在 app 私有目录，或者直接用 Binder（自带 `Binder.getCallingUid()`）。

### 3. 共享内存单次传递

`ShmIpc.kt` + `RemoteService.readSharedMemory`

`SharedMemory` 创建 1 MiB 缓冲区，装进 `Bundle` 经 `Messenger` 传给 `:remote`，两侧各自算校验和比对。

- **Binder 只传 fd，不传数据。** 1 MiB 直接塞 Bundle 会撞 Binder 事务上限（约 1 MB，`TransactionTooLargeException`）；共享内存下事务里只有一个 fd，内核做 fd 复制（`binder_translate_fd`），数据一次都没被拷贝。
- 用 `SharedMemory`（API 27）而不是 `MemoryFile`：后者**没有公开的方法拿到 fd**，`getFileDescriptor()` 是 hidden API，Android 9 起非 SDK 接口访问被拦，靠反射的老写法已经不能用了。
- `setProtect(PROT_READ)` 的调用顺序有讲究：必须先 `unmap` 掉自己的可写映射再收权限，否则失败。生效后接收方只能只读映射，且不可逆。`readSharedMemory` 里做了自验证，实测接收方 `mapReadWrite()` 报 `EPERM`。
- 每个映射都要 `SharedMemory.unmap()`，两个进程各自 `close()` 自己那份实例。

### 4. 共享内存环形队列 + eventfd

`app/src/main/cpp/ring.cpp` + `NativeRing.kt`

4 个 256 KiB slot 组成跨进程 SPSC 环，两个 `eventfd(EFD_SEMAPHORE)` 当计数信号量：`space_fd` 初值 4（空槽许可），`data_fd` 初值 0（满槽许可）。

```
生产者: sem_wait(space) → 写 slot[head] → head++ → sem_post(data)
消费者: sem_wait(data)  → 读 slot[tail] → tail++ → sem_post(space)
```

- **共享区里不需要任何锁和原子变量。** 两个计数信号量同时提供了流控和内存屏障：每一侧只会碰自己持有许可的 slot，物理上不可能同时访问同一块内存；`eventfd` 的 read/write 是系统调用，本身就是完整的内存屏障。所以 `head`/`tail` 游标是**各进程私有的普通变量**，压根不用放进共享内存（`ring.cpp` 里就是普通 `uint32_t Ring::cursor`）。
- `ASharedMemory_create` 是 API 26 而 `minSdk` 是 24，只加 `__builtin_available(android 26, *)` 守卫**不够** —— 默认模式下 NDK 把高版本符号标成硬不可用，守卫都编译不过。必须在 `CMakeLists.txt` 里定义 `__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__` 让符号变弱。
- fd 以 `ParcelFileDescriptor` 装在 `Bundle` 里传递。接收方必须**立刻** `detachFd()`：`ParcelFileDescriptor` 的 finalizer 会关掉描述符，把 PFD 对象传给工作线程再取就会丢 fd。
- `frameId` 0 是 EOF 哨兵。两处 `SemWait` 都带 5 秒 `poll` 超时，避免对端死掉时永久挂起。

## 对比

| | signal | LocalSocket | SharedMemory 单次 | ring (shm + eventfd) |
|---|---|---|---|---|
| 载荷 | 4 字节 | 任意长度 | 任意长度 | 任意长度 |
| 拷贝次数 | — | 2（用户→内核→用户） | 0 | 0 |
| 流控/背压 | 无 | 内核缓冲区 | 无 | **信号量背压** |
| 同步语义 | 无 | 连接内有序 | 无（靠一次性交接） | eventfd 计数 |
| 顺序保证 | 实时信号排队；标准信号会合并丢失 | 有序 | — | 有序 |
| 对端身份 | `si_pid`，同 UID 内可伪造 | 内核填充，可信 | Binder 调用方可信 | 同左 |
| 跨 app | 不行（`EPERM`） | 可以（务必校验 uid） | 可以 | 可以 |
| 需要 Binder | 否 | 否 | 是（传 fd） | 是（传 fd） |
| 实测吞吐 | — | — | — | ~400 MiB/s |
| 适合场景 | 通知、崩溃捕获、ANR 监控 | 命令、控制消息 | 单张大图/大 buffer | 持续帧流 |

最后一列就是 Android 平台自己在用的形态：`AImageReader`、`Surface`/BufferQueue、AudioTrack 的共享内存环，本质都是"共享内存放数据 + fd 经 Binder 传句柄 + 某种信号量做同步"。

## 实测数据

设备 SM-N9760 / Android 12：

```
:remote 上线 pid=15124
-> sigqueue(pid=15124, value=1)
<- pid=15124 回包 value=2
-> hello #1 from pid=15090
<- echo(pid=15124, 23B): hello #1 from pid=15090
-> shm 1024KiB seed=1 checksum=-1573088224，binder 只过了一个 fd
<- :remote 读到 1048576B checksum=-1573088224 (一致)
-> ring 写完 240 帧 / 60MiB，147ms，408MiB/s，因满而阻塞 231 次
<- :remote 收到 240 帧，损坏 0 帧
```

环形队列部分：240 帧 × 256 KiB = 60 MiB，约 150 ms，337–425 MiB/s，0 帧损坏。生产者只是 `memset`，消费者要逐字节校验，所以消费端是瓶颈 —— 240 帧里有 230 次左右生产者撞到"环满"而挂起。这正是**背压生效**的证明：数据一帧不丢不覆盖，生产速率自动被拉到消费速率。

## 不要这么做

- **别用信号当 IPC。** 每个 app 独立 UID，`kill()`/`sigqueue()` 打不到别人的进程（`EPERM`），SELinux 也禁止跨 app 域发信号；Android 7/9 之后 `/proc` 有 hidepid 限制，连对方 pid 都拿不到。信号在 Android 上的真实用途是：native crash 捕获（hook `SIGSEGV/SIGABRT/SIGBUS`）、ANR 监控（hook `SIGQUIT`）、自家多进程 watchdog、`SIGCHLD` 监听子进程退出。
- **别写死实时信号编号**，会撞上 bionic/ART 预留的那些，后果是 GC、debuggerd、profiler 行为异常。
- **别反射 `MemoryFile.getFileDescriptor()`**，非 SDK 接口已被拦，且没有替代的反射路径。
- **别假设抽象命名空间 socket 是私有的**，它没有文件权限，必须自己校验 uid。
- **别假设共享内存自带同步。** 单次交接（机制 3）安全是因为"写完才发 fd"；只要双向反复读写，就必须配信号量、futex 或额外的控制通道。

## 构建说明

见 `CLAUDE.md`。要点：Gradle Kotlin DSL，版本集中在 `gradle/libs.versions.toml`，`compileSdk` 37 / `minSdk` 24，native 部分走 CMake（`app/src/main/cpp/CMakeLists.txt`），gradle wrapper 指向腾讯镜像。