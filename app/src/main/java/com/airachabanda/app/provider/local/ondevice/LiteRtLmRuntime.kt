package com.airachabanda.app.provider.local.ondevice

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * CPU-only LiteRT-LM session. Tool calling stays off. A conversation is closed after every
 * generation, including cancellation, and the next request creates a new one.
 */
object LiteRtLmRuntime {
    private val scheduler = OnDeviceScheduler()
    private val lock = Any()
    private var engine: Engine? = null
    private var residentModelId: String? = null
    private var active: Conversation? = null

    fun probe(): String =
        try {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            "LiteRT-LM ${OnDeviceRuntime.PINNED_VERSION} CPU library loaded. Backend ${OnDeviceRuntime.BACKEND}. No model file is required for this check."
        } catch (error: UnsatisfiedLinkError) {
            "LiteRT-LM ${OnDeviceRuntime.PINNED_VERSION} is packaged. This device did not load the CPU library. ${error.message}"
        } catch (error: Throwable) {
            "LiteRT-LM ${OnDeviceRuntime.PINNED_VERSION} is packaged. The CPU library check failed. ${error.javaClass.simpleName}: ${error.message}"
        }

    suspend fun generate(
        directory: File,
        modelId: String,
        prompt: String,
        task: LocalTask,
        availableRamBytes: Long,
        thermal: ThermalLevel,
        cacheDir: File,
        accept: Boolean,
    ): LocalGenerationResult {
        val model = OnDeviceModelCatalog.find(modelId)
        val file = model?.let { OnDevicePreflight.verifiedFile(directory, it) }
        val blocked = OnDevicePreflight.refusal(model, file, prompt, task, availableRamBytes, thermal, accept, scheduler)
        if (blocked != null || model == null || file == null) {
            return blocked ?: LocalGenerationResult(modelId, "", fixture = false, cancelled = false, failure = "The model was not loaded.")
        }
        val bounds = scheduler.limitsFor(model.id, task, OnDevicePreflight.estimatedPromptTokens(prompt))
        val permit = scheduler.begin(model.id)
        return try {
            withContext(Dispatchers.IO) {
                runNative(model, file, prompt, bounds.outputTokens, bounds.timeoutMs, cacheDir, permit)
            }
        } catch (error: Throwable) {
            scheduler.unload(model.id)
            unload()
            LocalGenerationResult(
                model.id,
                "",
                fixture = false,
                cancelled = false,
                failure = "The model did not finish. ${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    fun cancel() {
        scheduler.cancelCurrent()
        val conversation = synchronized(lock) { active }
        runCatching { conversation?.cancelProcess() }
    }

    fun unload() {
        synchronized(lock) {
            runCatching { active?.close() }
            active = null
            runCatching { engine?.takeIf { it.isInitialized() }?.close() }
            engine = null
            val resident = residentModelId
            residentModelId = null
            if (resident != null) scheduler.unload(resident)
        }
    }

    private suspend fun runNative(
        model: OnDeviceModel,
        file: File,
        prompt: String,
        outputTokens: Int,
        timeoutMs: Long,
        cacheDir: File,
        permit: GenerationPermit,
    ): LocalGenerationResult {
        val loaded = synchronized(lock) {
            val current = engine
            if (current != null && residentModelId != model.id) {
                return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = "One model stays resident. Unload it before loading another.")
            }
            if (current == null || !current.isInitialized()) {
                val created = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(threadCount = 4),
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = model.contextTokens,
                        cacheDir = cacheDir.absolutePath,
                    ),
                )
                created.initialize()
                engine = created
                residentModelId = model.id
            }
            val conversation = engine!!.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("Answer the user. Do not request tools, files, or network access."),
                    tools = emptyList(),
                    automaticToolCalling = false,
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = outputTokens,
                ),
            )
            active = conversation
            conversation
        }
        val text = StringBuilder()
        var incomplete = false
        var cancelled = false
        var failure: String? = null
        try {
            withTimeout(timeoutMs) {
                loaded.sendMessageAsync(prompt, maxOutputToken = outputTokens).collect { message ->
                    if (message.toolCalls.isNotEmpty()) {
                        loaded.cancelProcess()
                        throw CancellationException("The model requested a tool. The tool was not run.")
                    }
                    if (!scheduler.accept(permit)) {
                        loaded.cancelProcess()
                        throw CancellationException("Generation was cancelled. The late response was rejected.")
                    }
                    text.append(message.toString())
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { loaded.cancelProcess() }
            incomplete = true
            failure = "Generation reached the ${timeoutMs / 1000}-second limit. The response is incomplete."
        } catch (cancel: CancellationException) {
            runCatching { loaded.cancelProcess() }
            cancelled = true
            failure = cancel.message ?: "Generation was cancelled. The late response was rejected."
            text.clear()
        } finally {
            synchronized(lock) {
                if (active === loaded) active = null
            }
            runCatching { loaded.close() }
        }
        if (cancelled) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = true, failure = failure)
        }
        val disclosure = if (incomplete) failure else null
        return LocalGenerationResult(
            modelId = model.id,
            text = text.toString(),
            fixture = false,
            cancelled = false,
            failure = disclosure,
        )
    }
}
