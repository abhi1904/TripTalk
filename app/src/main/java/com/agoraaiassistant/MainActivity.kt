package com.agoraaiassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agoraaiassistant.ui.theme.AgoraAiAssistantTheme
import io.agora.rtc2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AssistantState { IDLE, CONNECTING, WAITING_FOR_AGENT, LISTENING, ERROR }

class MainActivity : ComponentActivity() {
    private var agoraEngine: RtcEngine? = null
    private var agentId: String? = null
    private var agentUid = 100
    private val transcriptParser = TranscriptParser()
    private val TAG = "TripTalk"
    private val BACKEND_URL = BuildConfig.BACKEND_URL

    private var state by mutableStateOf(AssistantState.IDLE)
    private var errorMessage by mutableStateOf<String?>(null)
    private var agentSpeaking by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private val transcript = mutableStateListOf<TranscriptLine>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AgoraAiAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AiAssistantScreen(
                        modifier = Modifier.padding(innerPadding),
                        state = state,
                        errorMessage = errorMessage,
                        agentSpeaking = agentSpeaking,
                        muted = muted,
                        transcript = transcript,
                        onStartAssistant = { startAssistant() },
                        onStopAssistant = { stopAssistant() },
                        onToggleMute = { toggleMute() }
                    )
                }
            }
        }

        checkPermissions()
    }

    private fun startAssistant() {
        if (!hasMicPermission()) {
            checkPermissions()
            return
        }
        state = AssistantState.CONNECTING
        errorMessage = null
        muted = false
        transcript.clear()
        lifecycleScope.launch {
            try {
                // 1. Get Config from Backend (Token, Channel, UID)
                val config = withContext(Dispatchers.IO) { httpGet("/get_config") }
                val channel = config.getString("channel")
                val uid = config.getInt("uid")
                agentUid = config.optInt("agentUid", 100)

                setupAgora(config.getString("appId"))
                joinChannel(config.getString("rtcToken"), channel, uid)
                state = AssistantState.WAITING_FOR_AGENT

                // 2. Tell Backend to Start the AI Agent in this channel
                val body = JSONObject().apply {
                    put("channelName", channel)
                    put("userUid", uid)
                }
                val agent = withContext(Dispatchers.IO) { httpPost("/start_agent", body) }
                agentId = agent.optString("agentId").ifEmpty { null }
                Log.d(TAG, "Agent started: $agentId")
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                fail(e.message ?: "Could not start the assistant")
            }
        }
    }

    private fun stopAssistant() {
        val id = agentId
        agentId = null
        if (id != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    httpPost("/stop_agent", JSONObject().put("agentId", id))
                } catch (e: Exception) {
                    Log.e(TAG, "Stop agent failed", e)
                }
            }
        }
        releaseEngine()
        state = AssistantState.IDLE
    }

    private fun toggleMute() {
        muted = !muted
        agoraEngine?.muteLocalAudioStream(muted)
    }

    private fun fail(message: String) {
        releaseEngine()
        errorMessage = message
        state = AssistantState.ERROR
    }

    private fun onTranscript(line: TranscriptLine) {
        val index = transcript.indexOfFirst { it.key == line.key }
        if (index >= 0) transcript[index] = line else transcript.add(line)
    }

    private fun httpGet(path: String): JSONObject {
        val conn = URL("$BACKEND_URL$path").openConnection() as HttpURLConnection
        return readResponse(conn)
    }

    private fun httpPost(path: String, body: JSONObject): JSONObject {
        val conn = URL("$BACKEND_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): JSONObject {
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Backend error $code: $text")
        return JSONObject(text)
    }

    private fun setupAgora(appId: String) {
        if (agoraEngine != null) return
        val config = RtcEngineConfig()
        config.mContext = applicationContext
        config.mAppId = appId
        config.mEventHandler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                Log.d(TAG, "Joined channel: $channel")
            }

            override fun onUserJoined(uid: Int, elapsed: Int) {
                Log.d(TAG, "AI agent joined: $uid")
                runOnUiThread { if (agoraEngine != null) state = AssistantState.LISTENING }
            }

            override fun onUserOffline(uid: Int, reason: Int) {
                Log.d(TAG, "AI agent left: $uid")
                runOnUiThread {
                    if (state == AssistantState.LISTENING) fail("The assistant left the call. Tap start to talk again.")
                }
            }

            override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
                val agent = speakers?.firstOrNull { it.uid == agentUid } ?: return
                val speaking = agent.volume > 20
                runOnUiThread { agentSpeaking = speaking }
            }

            override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
                val line = data?.let { transcriptParser.onChunk(String(it)) } ?: return
                runOnUiThread { onTranscript(line) }
            }

            override fun onError(err: Int) {
                Log.e(TAG, "Agora error: $err")
            }
        }
        agoraEngine = RtcEngine.create(config)
        agoraEngine?.enableAudio()
        agoraEngine?.setDefaultAudioRoutetoSpeakerphone(true)
        agoraEngine?.enableAudioVolumeIndication(200, 3, false)
    }

    private fun joinChannel(token: String, channel: String, uid: Int) {
        val options = ChannelMediaOptions()
        options.autoSubscribeAudio = true
        options.publishMicrophoneTrack = true
        options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
        agoraEngine?.joinChannel(token, channel, uid, options)
    }

    private fun releaseEngine() {
        agoraEngine?.leaveChannel()
        agoraEngine = null
        agentSpeaking = false
        RtcEngine.destroy()
    }

    override fun onDestroy() {
        stopAssistant()
        super.onDestroy()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}

private val examplePrompts = listOf(
    "\"Plan a 3-day trip to Goa on a budget\"",
    "\"Do I need a visa for Japan?\"",
    "\"What should I eat in Lisbon?\""
)

@Composable
fun AiAssistantScreen(
    modifier: Modifier = Modifier,
    state: AssistantState,
    errorMessage: String?,
    agentSpeaking: Boolean,
    muted: Boolean,
    transcript: SnapshotStateList<TranscriptLine>,
    onStartAssistant: () -> Unit,
    onStopAssistant: () -> Unit,
    onToggleMute: () -> Unit
) {
    val isActive = state == AssistantState.CONNECTING ||
        state == AssistantState.WAITING_FOR_AGENT ||
        state == AssistantState.LISTENING

    val status = when (state) {
        AssistantState.IDLE -> "Tap to start planning your trip"
        AssistantState.CONNECTING -> "Connecting…"
        AssistantState.WAITING_FOR_AGENT -> "Calling your travel planner…"
        AssistantState.LISTENING -> when {
            agentSpeaking -> "TripTalk is speaking. Interrupt any time"
            muted -> "You're muted"
            else -> "Listening. Just talk!"
        }
        AssistantState.ERROR -> errorMessage ?: "Something went wrong"
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TripTalk", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Your voice travel planner",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state == AssistantState.LISTENING) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (agentSpeaking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (state == AssistantState.CONNECTING || state == AssistantState.WAITING_FOR_AGENT) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = if (state == AssistantState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Try saying", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    examplePrompts.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                TranscriptList(transcript)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state == AssistantState.LISTENING) {
                OutlinedButton(onClick = onToggleMute, modifier = Modifier.height(64.dp)) {
                    Text(if (muted) "Unmute" else "Mute")
                }
            }
            Button(
                onClick = { if (isActive) onStopAssistant() else onStartAssistant() },
                modifier = Modifier.height(64.dp).widthIn(min = 200.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isActive) "End Call" else "Start Talking", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun TranscriptList(transcript: SnapshotStateList<TranscriptLine>) {
    val listState = rememberLazyListState()
    val lastText = transcript.lastOrNull()?.text
    LaunchedEffect(transcript.size, lastText) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transcript, key = { it.key }) { line ->
            TranscriptBubble(line)
        }
    }
}

@Composable
private fun TranscriptBubble(line: TranscriptLine) {
    val bubbleColor = if (line.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (line.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(if (line.fromUser) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                if (line.fromUser) "You" else "TripTalk",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(line.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
        }
    }
}
