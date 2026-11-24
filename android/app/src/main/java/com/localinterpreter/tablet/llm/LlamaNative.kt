package com.localinterpreter.tablet.llm

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thin JNI bridge to llama.cpp. Each instance owns one model + KV cache.
 * Calls are serialized inside the native layer; [cancel] is safe from another thread
 * and [close] waits for any in-flight native call to exit before freeing the handle.
 */
class LlamaNative private constructor(@Volatile private var handle: Long) {

    @Volatile private var closed = false
    private val rwLock = ReentrantReadWriteLock()

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    fun generate(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback?,
    ): String = rwLock.read {
        val h = handle
        if (closed || h == 0L) return ""
        return generate(h, system, user, maxTokens, temperature, callback)
    }

    fun cancel() {
        rwLock.read {
            val h = handle
            if (h != 0L) cancel(h)
        }
    }

    fun close() {
        closed = true
        // 先发取消信号唤醒阻塞中的 generate，再获取写锁等待所有在途 JNI 调用退出后销毁句柄。
        cancel()
        rwLock.write {
            val h = handle
            handle = 0L
            if (h != 0L) destroy(h)
        }
    }

    companion object {
        init {
            System.loadLibrary("llama-jni")
        }

        @Volatile
        private var backendReady = false

        @Synchronized
        fun ensureBackend(nativeLibDir: String) {
            if (backendReady) return
            init(nativeLibDir)
            backendReady = true
        }

        fun hasGpuBackend(nativeLibDir: String): Boolean {
            return runCatching {
                ensureBackend(nativeLibDir)
                nativeHasGpuBackend()
            }.getOrDefault(false)
        }

        fun backendSummary(nativeLibDir: String): String {
            return runCatching {
                ensureBackend(nativeLibDir)
                nativeBackendSummary()
            }.getOrDefault("CPU")
        }

        fun load(modelPath: String, contextSize: Int, threads: Int, gpuLayers: Int = 0): LlamaNative? {
            val h = create(modelPath, contextSize, threads, gpuLayers)
            return if (h == 0L) null else LlamaNative(h)
        }

        @JvmStatic
        private external fun init(nativeLibDir: String)

        @JvmStatic
        private external fun nativeHasGpuBackend(): Boolean

        @JvmStatic
        private external fun nativeBackendSummary(): String

        @JvmStatic
        private external fun create(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

        @JvmStatic
        private external fun generate(
            handle: Long,
            system: String,
            user: String,
            maxTokens: Int,
            temperature: Float,
            callback: TokenCallback?,
        ): String

        @JvmStatic
        private external fun cancel(handle: Long)

        @JvmStatic
        private external fun destroy(handle: Long)
    }
}
