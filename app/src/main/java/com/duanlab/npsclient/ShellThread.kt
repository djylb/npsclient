package com.duanlab.npsclient

import java.io.File

internal data class ProcessResult(val exitCode: Int?, val error: Exception?, val stopped: Boolean)

internal class ShellThread(
    private val command: List<String>,
    private val dir: File,
    private val envp: Map<String, String> = emptyMap(),
    private val onFinished: (ShellThread, ProcessResult) -> Unit = { _, _ -> },
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
    private val outputCallback: (String) -> Unit
) : Thread("npc-process") {
    private val lock = Any()
    private var process: Process? = null
    private var stopRequested = false

    override fun run() {
        var child: Process? = null
        var exitCode: Int? = null
        var error: Exception? = null
        try {
            if (synchronized(lock) { stopRequested }) return
            val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
            builder.environment().putAll(envp)
            val started = startProcess(builder)
            child = started
            val shouldStop = synchronized(lock) {
                process = started
                stopRequested
            }
            if (shouldStop) started.destroy()
            started.outputStream.close()
            started.inputStream.bufferedReader().use { reader ->
                // Bound even a child output line without a newline.
                val buffer = CharArray(4096)
                val line = StringBuilder()
                var count: Int
                while (reader.read(buffer).also { count = it } != -1) {
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (char == '\n') {
                            outputCallback(line.toString().removeSuffix("\r"))
                            line.clear()
                        } else {
                            line.append(char)
                            if (line.length >= 8192) {
                                outputCallback(line.toString())
                                line.clear()
                            }
                        }
                    }
                }
                if (line.isNotEmpty()) outputCallback(line.toString())
            }
            exitCode = started.waitFor()
        } catch (e: Exception) {
            error = e
        } finally {
            child?.let { running ->
                runCatching { running.destroy() }
                runCatching { running.waitFor() }
                runCatching { running.inputStream.close() }
                runCatching { running.errorStream.close() }
                runCatching { running.outputStream.close() }
            }
            val stopped = synchronized(lock) {
                process = null
                stopRequested
            }
            onFinished(this, ProcessResult(exitCode, error, stopped))
        }
    }

    fun stopProcess() {
        val child = synchronized(lock) {
            stopRequested = true
            process
        }
        child?.destroy()
    }
}
