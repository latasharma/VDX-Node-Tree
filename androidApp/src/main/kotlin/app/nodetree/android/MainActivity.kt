package app.nodetree.android

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nodetree.demo.demoConductor
import app.nodetree.driver.Conductor
import app.nodetree.driver.DriverReply
import app.nodetree.driver.DriverRequest
import app.nodetree.model.Platform
import app.nodetree.narrate.Outline
import kotlinx.coroutines.launch

/**
 * Skeleton screen: talk (system speech recognizer) or type, hear the reply,
 * and read the run tree as an accessible outline. Uses the demo conductor
 * (fake contacts, fake dialer) until slice 1 adds the real executors.
 */
class MainActivity : ComponentActivity() {
    private val conductor: Conductor = demoConductor(Platform.ANDROID)
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) {}
        setContent { MaterialTheme { NodeTreeScreen(conductor) { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "reply") } } }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun NodeTreeScreen(conductor: Conductor, speak: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val tree by conductor.latestTree.collectAsState()
    var reply by remember { mutableStateOf<DriverReply?>(null) }
    var typed by remember { mutableStateOf("") }

    fun send(text: String) {
        scope.launch {
            val r = conductor.handle(DriverRequest(text, driverId = "android-voice", sessionId = "android"))
            reply = r
            speak(r.text)
        }
    }

    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::send)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                listen.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
                )
            },
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) { Text("Talk", fontSize = 28.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), label = { Text("Or type") })
            Button(onClick = { send(typed); typed = "" }) { Text("Send") }
        }

        reply?.let { r ->
            Text(r.text, fontSize = 22.sp)
            if (r is DriverReply.AskConfirm) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { send("yes") }, Modifier.weight(1f).height(72.dp)) { Text("Yes", fontSize = 24.sp) }
                    Button(onClick = { send("no") }, Modifier.weight(1f).height(72.dp)) { Text("No", fontSize = 24.sp) }
                }
            }
        }

        tree?.let { OutlineList(Outline.ofRun(it)) }
    }
}

@Composable
private fun OutlineList(lines: List<app.nodetree.narrate.OutlineLine>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(lines, key = { it.nodeId }) { line ->
            Text(
                line.text,
                fontSize = if (line.depth == 0) 20.sp else 17.sp,
                modifier = Modifier
                    .padding(start = (line.depth * 20).dp)
                    .semantics {
                        contentDescription = line.accessibilityLabel
                        if (line.depth == 0) heading()
                    },
            )
        }
    }
}
