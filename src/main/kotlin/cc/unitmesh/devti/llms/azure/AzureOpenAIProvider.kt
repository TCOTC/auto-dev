package cc.unitmesh.devti.llms.azure

import cc.unitmesh.devti.custom.action.CustomPromptConfig
import cc.unitmesh.devti.gui.chat.ChatRole
import cc.unitmesh.devti.llms.LLMProvider
import cc.unitmesh.devti.coder.recording.EmptyRecording
import cc.unitmesh.devti.coder.recording.JsonlRecording
import cc.unitmesh.devti.coder.recording.Recording
import cc.unitmesh.devti.coder.recording.RecordingInstruction
import cc.unitmesh.devti.settings.AutoDevSettingsState
import cc.unitmesh.devti.settings.coder.coderSetting
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.theokanning.openai.completion.chat.ChatCompletionResult
import com.theokanning.openai.completion.chat.ChatMessage
import com.theokanning.openai.completion.chat.ChatMessageRole
import com.theokanning.openai.service.SSE
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.time.Duration


@Serializable
data class SimpleOpenAIFormat(val role: String, val content: String) {
    companion object {
        fun fromChatMessage(message: ChatMessage): SimpleOpenAIFormat {
            return SimpleOpenAIFormat(message.role, message.content)
        }
    }
}

@Serializable
data class SimpleOpenAIBody(val messages: List<SimpleOpenAIFormat>, val temperature: Double, val stream: Boolean)

@Service(Service.Level.PROJECT)
class AzureOpenAIProvider(val project: Project) : LLMProvider {
    private val logger = logger<AzureOpenAIProvider>()

    private val autoDevSettingsState = AutoDevSettingsState.getInstance()
    private val url: String
        get() {
            val customOpenAiHost = autoDevSettingsState.customOpenAiHost
            if (!customOpenAiHost.endsWith("/")) {
                return "$customOpenAiHost/"
            }
            return customOpenAiHost
        }
    private var customPromptConfig: CustomPromptConfig? = null
    private val timeout = Duration.ofSeconds(600)
    private var client = OkHttpClient().newBuilder().readTimeout(timeout).build()
    private val openAiVersion: String
    private val maxTokenLength: Int
        get() = AutoDevSettingsState.getInstance().fetchMaxTokenLength()

    private val recording: Recording
        get() {
            if (project.coderSetting.state.recordingInLocal) {
                return project.service<JsonlRecording>()
            }
            return EmptyRecording()
        }


    init {
        val prompts = autoDevSettingsState.customPrompts
        openAiVersion = AutoDevSettingsState.getInstance().openAiModel
        customPromptConfig = CustomPromptConfig.tryParse(prompts)
    }

    override fun prompt(promptText: String): String {
        return this.prompt(promptText, "")
    }

    private val messages: MutableList<SimpleOpenAIFormat> = ArrayList()
    private var historyMessageLength: Int = 0

    override fun clearMessage() {
        messages.clear()
        historyMessageLength = 0
    }

    override fun appendLocalMessage(msg: String, role: ChatRole) {
        val message = SimpleOpenAIFormat(role.roleName(), msg)
        messages.add(message)
        historyMessageLength += msg.length
    }

    fun prompt(instruction: String, input: String): String {
        val promptText = "$instruction\n$input"
        val systemMessage = ChatMessage(ChatMessageRole.USER.value(), promptText)
        if (historyMessageLength > maxTokenLength) {
            messages.clear()
        }
        if (project.coderSetting.state.noChatHistory) {
            messages.clear()
        }

        messages.add(SimpleOpenAIFormat.fromChatMessage(systemMessage))
        val requestText = Json.encodeToString<SimpleOpenAIBody>(
            SimpleOpenAIBody(
                messages,
                0.0,
                false
            )
        )

        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            requestText
        )

        val builder = Request.Builder()
        val request = builder
            .url(url)
            .post(body)
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            logger.error("$response")
            return ""
        }

        val completion: ChatCompletionResult =
            ObjectMapper().readValue(response.body?.string(), ChatCompletionResult::class.java)

        return completion.choices[0].message.content
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun stream(promptText: String, systemPrompt: String, keepHistory: Boolean): Flow<String> {
        val promptText1 = "$promptText\n${""}"
        val systemMessage = ChatMessage(ChatMessageRole.USER.value(), promptText1)
        if (historyMessageLength > 8192 || !keepHistory) {
            messages.clear()
        }

        messages.add(SimpleOpenAIFormat.fromChatMessage(systemMessage))
        val openAIBody = SimpleOpenAIBody(
            messages,
            0.0,
            true
        )

        val requestText = Json.encodeToString<SimpleOpenAIBody>(openAIBody)
        val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), requestText)

        val builder = Request.Builder()
        val request = builder
            .url(url)
            .post(body)
            .build()

        val call = client.newCall(request)
        val emitDone = false

        val sseFlowable = Flowable
            .create({ emitter: FlowableEmitter<SSE> ->
                call.enqueue(cc.unitmesh.devti.llms.azure.ResponseBodyCallback(emitter, emitDone))
            }, BackpressureStrategy.BUFFER)

        var output = ""

        return callbackFlow {
            sseFlowable
                .doOnError(Throwable::printStackTrace)
                .blockingForEach { sse ->
                    val result: ChatCompletionResult =
                        ObjectMapper().readValue(sse!!.data, ChatCompletionResult::class.java)
                    val completion = result.choices[0].message
                    if (completion != null && completion.content != null) {
                        output += completion.content
                        trySend(completion.content)
                    }
                }

            recording.write(RecordingInstruction(promptText, output))

            close()
        }
    }
}
