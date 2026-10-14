package com.duanlab.npsclient

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ShellThreadTest {
    private class FakeProcess(output: String = "", private val code: Int = 0, private val mustBeStopped: Boolean = false) : Process() {
        val destroyed = AtomicInteger()
        private val stdout = ByteArrayInputStream(output.toByteArray())
        override fun getInputStream(): ByteArrayInputStream {
            check(!mustBeStopped || destroyed.get() > 0) { "Cancellation was lost during start" }
            return stdout
        }
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() { destroyed.incrementAndGet() }
    }

    @Test fun stopBeforeRunDoesNotStartAChild() {
        val calls = AtomicInteger()
        val result = AtomicReference<ProcessResult>()
        val thread = ShellThread(listOf("npc"), File("."),
            onFinished = { _, value -> result.set(value) },
            startProcess = { calls.incrementAndGet(); FakeProcess() }) {}
        thread.stopProcess()
        thread.start()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertEquals(0, calls.get())
        assertTrue(result.get().stopped)
    }

    @Test fun stopWhileStartIsInFlightDestroysTheNewChild() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = FakeProcess(mustBeStopped = true)
        val result = AtomicReference<ProcessResult>()
        val thread = ShellThread(listOf("npc"), File("."),
            onFinished = { _, value -> result.set(value) },
            startProcess = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                child
            }) {}
        thread.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            thread.stopProcess()
            thread.stopProcess()
        } finally { release.countDown() }
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertTrue(child.destroyed.get() > 0)
        assertNull(result.get().error)
        assertTrue(result.get().stopped)
    }

    @Test fun reportsStartupFailureExactlyOnce() {
        val callbacks = AtomicInteger()
        val result = AtomicReference<ProcessResult>()
        val thread = ShellThread(listOf("npc"), File("."),
            onFinished = { _, value -> callbacks.incrementAndGet(); result.set(value) },
            startProcess = { throw IOException("failed") }) {}
        thread.start()
        thread.join(2000)
        assertEquals(1, callbacks.get())
        assertTrue(result.get().error is IOException)
        assertFalse(result.get().stopped)
    }

    @Test fun preservesExitCodeAndBoundsLinesWithoutDroppingOutput() {
        val output = "x".repeat(20000) + "\nlast line"
        val lines = mutableListOf<String>()
        val result = AtomicReference<ProcessResult>()
        val thread = ShellThread(listOf("npc"), File("."),
            onFinished = { _, value -> result.set(value) },
            startProcess = { FakeProcess(output, 139) }) { lines.add(it) }
        thread.start()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertEquals(139, result.get().exitCode)
        assertNull(result.get().error)
        assertTrue(lines.all { it.length <= 8192 })
        assertEquals("x".repeat(20000) + "last line", lines.joinToString(""))
    }
}
