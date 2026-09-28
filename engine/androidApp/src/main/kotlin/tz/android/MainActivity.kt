package tz.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tz.shared.Explorer
import tz.shared.GameApi
import tz.shared.LocationView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val explorer = Explorer(GameApi(BuildConfig.SERVER_URL))
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ExploreScreen(explorer) }
            }
        }
    }
}

@Composable
fun ExploreScreen(explorer: Explorer) {
    val scope = rememberCoroutineScope()
    var location by remember { mutableStateOf<LocationView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun run(action: suspend () -> Unit) {
        scope.launch {
            loading = true
            action()
            location = explorer.location
            error = explorer.error
            loading = false
        }
    }

    LaunchedEffect(Unit) { run { explorer.start() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val loc = location
        if (loc != null) {
            Text(loc.name, style = MaterialTheme.typography.headlineSmall)
            loc.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (loc.npcs.isNotEmpty()) {
                Text("Здесь: " + loc.npcs.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium)
            }
            loc.exits.forEach { exit ->
                OutlinedButton(onClick = { run { explorer.go(exit) } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(exit.label)
                }
            }
        }
        error?.let {
            Text("Нет связи с сервером: $it", color = MaterialTheme.colorScheme.error)
            Button(onClick = { run { explorer.start() } }) { Text("Повторить") }
        }
        if (loading) CircularProgressIndicator()
    }
}
