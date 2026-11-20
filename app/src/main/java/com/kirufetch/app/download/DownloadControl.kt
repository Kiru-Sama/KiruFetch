package com.kirufetch.app.download

import kotlinx.coroutines.CancellationException

/** 用户主动暂停，不是失败，也不是取消。 */
class PausedException : Exception("下载已暂停")

class DownloadControl {
    @Volatile
    var paused: Boolean = false

    @Volatile
    var canceled: Boolean = false

    fun checkCanceled() {
        if (canceled) throw CancellationException("下载已取消")
    }

    /**
     * 下载循环里的检查点。取消抛 CancellationException，暂停抛 PausedException。
     * 暂停不再靠挂起等待：串行队列里挂住的任务会把后面的任务全堵死。
     */
    fun checkpoint() {
        if (canceled) throw CancellationException("下载已取消")
        if (paused) throw PausedException()
    }
}

object DownloadControls {
    private val map = java.util.concurrent.ConcurrentHashMap<String, DownloadControl>()

    fun of(id: String): DownloadControl = map.getOrPut(id) { DownloadControl() }

    fun remove(id: String) {
        map.remove(id)
    }
}

/**
 * runCatching 会把 CancellationException 一起吞掉，导致协程取消失效。
 * 这个版本让取消异常原样向上抛。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
