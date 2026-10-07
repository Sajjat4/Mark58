package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jarvis.assistant.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(GeminiConstants.KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var reconnectJob: Job? = null
    // Handle returned by the server so a reconnect can resume the same
    // conversation context instead of starting over.
    private var resumptionHandle: String? = null

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            listener.onError("Gemini API Key is missing. Please configure it in Settings.")
            return
        }

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to Gemini Live")
                    isConnected.set(true)
                    listener.onConnectionStateChanged("LIVE")

                    // Send setup message
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    if (code == 1008) {
                        listener.onError("Model not supported or invalid key: $reason")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: $errorMsg", t)
                    isConnected.set(false)
                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupPayload = SetupPayload(
            model = model,
            generationConfig = GenerationConfigPayload(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfigPayload(
                    voiceConfig = VoiceConfigPayload(
                        prebuiltVoiceConfig = PrebuiltVoiceConfigPayload(voiceName = voiceName)
                    )
                )
            ),
            systemInstruction = ContentPayload(
                parts = listOf(PartTextPayload(text = systemPrompt))
            ),
            // Native-audio models don't return text unless transcription is
            // explicitly requested, so chat history would otherwise be empty.
            outputAudioTranscription = emptyMap(),
            inputAudioTranscription = emptyMap(),
            // Keeps long conversations alive past the ~15-minute audio-only cap.
            contextWindowCompression = ContextWindowCompressionPayload(),
            sessionResumption = SessionResumptionPayload(handle = resumptionHandle)
        )

        val bidiSetup = GeminiBidiSetup(setup = setupPayload)
        val json = gson.toJson(bidiSetup)
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live (resuming=${resumptionHandle != null})")
        ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val jsonObject = JsonParser.parseString(jsonText).asJsonObject

            // Server is about to close the connection (e.g. max session length).
            // Reconnecting with the saved resumption handle continues the same context.
            if (jsonObject.has("goAway")) {
                Log.d(TAG, "Server sent goAway, reconnecting with resumption handle")
                webSocket?.close(1000, "goAway")
                return
            }

            if (jsonObject.has("sessionResumptionUpdate")) {
                val update = jsonObject.getAsJsonObject("sessionResumptionUpdate")
                if (update.has("resumable") && update.get("resumable").asBoolean &&
                    update.has("newHandle") && !update.get("newHandle").isJsonNull
                ) {
                    resumptionHandle = update.get("newHandle").asString
                }
            }

            if (jsonObject.has("serverContent")) {
                val serverContent = jsonObject.getAsJsonObject("serverContent")

                // Interrupted (Barge-in)
                if (serverContent.has("interrupted") && serverContent.get("interrupted").asBoolean) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                // Spoken transcript of the model's own audio reply (native-audio
                // models emit audio only, so this is the only source of text).
                if (serverContent.has("outputTranscription")) {
                    val transcription = serverContent.getAsJsonObject("outputTranscription")
                    if (transcription.has("text") && !transcription.get("text").isJsonNull) {
                        val text = transcription.get("text").asString
                        if (!text.isNullOrBlank()) {
                            listener.onAssistantTextReceived(text)
                        }
                    }
                }

                // Model turn parts (Audio, and text for older models)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getAsJsonObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getAsJsonArray("parts")
                        for (i in 0 until parts.size()) {
                            val part = parts.get(i).asJsonObject

                            // Text transcript
                            if (part.has("text")) {
                                val text = part.get("text").asString
                                if (!text.isNullOrBlank()) {
                                    listener.onAssistantTextReceived(text)
                                }
                            }

                            // Inline audio (24kHz Mono PCM Base64)
                            if (part.has("inlineData")) {
                                val inlineData = part.getAsJsonObject("inlineData")
                                if (inlineData.has("data")) {
                                    val base64Data = inlineData.get("data").asString
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    listener.onAudioDataReceived(pcmBytes)
                                }
                            }
                        }
                    }
                }

                // Turn completed
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").asBoolean) {
                    Log.d(TAG, "Server signaled turnComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val realtimeInput = GeminiRealtimeInput(
                realtimeInput = RealtimeAudioInput(
                    audio = RealtimeAudioPayload(
                        mimeType = "audio/pcm;rate=16000",
                        data = base64Audio
                    )
                )
            )
            val json = gson.toJson(realtimeInput)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    // Hybrid VAD: tells the server the user has stopped talking as soon as our
    // own local silence check trips, rather than waiting for the server's own
    // (slightly slower) detector — shaves noticeable latency off replies.
    fun sendAudioStreamEnd() {
        if (!isConnected.get()) return
        try {
            webSocket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audioStreamEnd: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(3000)
            if (!isManuallyStopped.get()) {
                Log.d(TAG, "Attempting auto-reconnect...")
                initiateConnection()
            }
        }
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        resumptionHandle = null
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
