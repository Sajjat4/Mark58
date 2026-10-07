package com.jarvis.assistant.data.model

import com.google.gson.annotations.SerializedName

object GeminiConstants {
    const val DEFAULT_MODEL = "models/gemini-3.8-live"

    val SUPPORTED_MODELS = listOf(
        "models/gemini-3.8-live",
        "models/gemini-3.8-live-extended-thinking",
        "models/gemini-2.5-flash-native-audio-preview-12-2025"
    )
    
    val SUPPORTED_VOICES = listOf(
        "Aoede",
        "Charon",
        "Kore",
        "Fenrir",
        "Puck",
        "Leda",
        "Orus",
        "Zephyr"
    )
    
    const val PERSONALITY_GIRLFRIEND = "Girlfriend Mode"
    const val PERSONALITY_PROFESSIONAL = "Professional Mode"
    const val PERSONALITY_ASSISTANT = "Assistant Mode"
    
    val SUPPORTED_PERSONALITIES = listOf(
        PERSONALITY_ASSISTANT,
        PERSONALITY_GIRLFRIEND,
        PERSONALITY_PROFESSIONAL
    )
    
    const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val KEEPALIVE_INTERVAL_SEC = 8L
    // Silence needed before the server ends the user's turn. Lower = snappier but
    // more prone to cutting off pauses mid-sentence.
    const val SILENCE_DURATION_MS = 600
}

// Request Models
data class GeminiBidiSetup(
    @SerializedName("setup") val setup: SetupPayload
)

data class SetupPayload(
    @SerializedName("model") val model: String,
    @SerializedName("generationConfig") val generationConfig: GenerationConfigPayload,
    @SerializedName("systemInstruction") val systemInstruction: ContentPayload? = null,
    @SerializedName("realtimeInputConfig") val realtimeInputConfig: RealtimeInputConfigPayload = RealtimeInputConfigPayload(),
    @SerializedName("outputAudioTranscription") val outputAudioTranscription: Map<String, String> = emptyMap(),
    @SerializedName("inputAudioTranscription") val inputAudioTranscription: Map<String, String> = emptyMap(),
    @SerializedName("contextWindowCompression") val contextWindowCompression: ContextWindowCompressionPayload = ContextWindowCompressionPayload(),
    @SerializedName("sessionResumption") val sessionResumption: SessionResumptionPayload = SessionResumptionPayload()
)

data class RealtimeInputConfigPayload(
    @SerializedName("automaticActivityDetection") val automaticActivityDetection: ActivityDetectionPayload = ActivityDetectionPayload()
)

data class ActivityDetectionPayload(
    @SerializedName("silenceDurationMs") val silenceDurationMs: Int = GeminiConstants.SILENCE_DURATION_MS
)

data class ContextWindowCompressionPayload(
    @SerializedName("slidingWindow") val slidingWindow: Map<String, String> = emptyMap()
)

data class SessionResumptionPayload(
    @SerializedName("handle") val handle: String? = null
)

data class GenerationConfigPayload(
    @SerializedName("responseModalities") val responseModalities: List<String> = listOf("AUDIO"),
    @SerializedName("speechConfig") val speechConfig: SpeechConfigPayload
)

data class SpeechConfigPayload(
    @SerializedName("voiceConfig") val voiceConfig: VoiceConfigPayload
)

data class VoiceConfigPayload(
    @SerializedName("prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfigPayload
)

data class PrebuiltVoiceConfigPayload(
    @SerializedName("voiceName") val voiceName: String
)

data class ContentPayload(
    @SerializedName("parts") val parts: List<PartTextPayload>
)

data class PartTextPayload(
    @SerializedName("text") val text: String
)

data class GeminiRealtimeInput(
    @SerializedName("realtimeInput") val realtimeInput: RealtimeAudioInput
)

data class RealtimeAudioInput(
    @SerializedName("audio") val audio: RealtimeAudioPayload
)

data class RealtimeAudioPayload(
    @SerializedName("mimeType") val mimeType: String = "audio/pcm;rate=16000",
    @SerializedName("data") val data: String
)

// Response Models
data class GeminiBidiServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContentPayload? = null
)

data class ServerContentPayload(
    @SerializedName("modelTurn") val modelTurn: ModelTurnPayload? = null,
    @SerializedName("turnComplete") val turnComplete: Boolean = false,
    @SerializedName("interrupted") val interrupted: Boolean = false,
    @SerializedName("outputTranscription") val outputTranscription: TranscriptionPayload? = null,
    @SerializedName("inputTranscription") val inputTranscription: TranscriptionPayload? = null
)

data class TranscriptionPayload(
    @SerializedName("text") val text: String? = null
)

data class GoAwayPayload(
    @SerializedName("timeLeft") val timeLeft: String? = null
)

data class SessionResumptionUpdatePayload(
    @SerializedName("newHandle") val newHandle: String? = null,
    @SerializedName("resumable") val resumable: Boolean = false
)

data class ModelTurnPayload(
    @SerializedName("parts") val parts: List<ModelPartPayload>? = null
)

data class ModelPartPayload(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineAudioPayload? = null
)

data class InlineAudioPayload(
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("data") val data: String? = null
)
