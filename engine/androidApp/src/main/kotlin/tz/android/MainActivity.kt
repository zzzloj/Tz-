package tz.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tz.shared.GameApi
import tz.shared.Screen
import tz.shared.Session
import tz.shared.TokenStore

/** Session token in the app's private storage. TODO: Android Keystore before release. */
class PrefsTokens(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString("token", null)
    override fun save(token: String?) {
        prefs.edit().apply { if (token == null) remove("token") else putString("token", token) }.apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(GameApi(BuildConfig.SERVER_URL), PrefsTokens(applicationContext))
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App(session) }
            }
        }
    }
}

@Composable
fun App(session: Session) {
    val scope = rememberCoroutineScope()
    // Session is plain Kotlin; bump this counter after each call to redraw.
    var version by remember { mutableIntStateOf(0) }
    fun run(action: suspend () -> Unit) {
        scope.launch { version++; action(); version++ }
    }
    LaunchedEffect(Unit) { run { session.resume() } }

    @Suppress("UNUSED_EXPRESSION") version
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (session.screen) {
            Screen.LOADING -> if (session.error != null) Button(onClick = { run { session.refresh() } }) { Text("Повторить") }
            Screen.SIGN_IN -> SignIn(session.busy, onSignIn = { l, p -> run { session.signIn(l, p) } }, onRegister = { l, p -> run { session.register(l, p) } })
            Screen.CREATE_CHARACTER -> CreateCharacter(session.busy) { name, female -> run { session.createCharacter(name, female) } }
            Screen.PLAYING -> Playing(session, onGo = { exit -> run { session.go(exit) } }, onSignOut = { run { session.signOut() } })
        }
        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (session.busy) CircularProgressIndicator()
    }
}

@Composable
fun SignIn(busy: Boolean, onSignIn: (String, String) -> Unit, onRegister: (String, String) -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text("Территория Зла", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(login, { login = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password, { password = it }, label = { Text("Пароль") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSignIn(login, password) }, enabled = !busy) { Text("Войти") }
        OutlinedButton(onClick = { onRegister(login, password) }, enabled = !busy) { Text("Регистрация") }
    }
}

@Composable
fun CreateCharacter(busy: Boolean, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var female by remember { mutableStateOf(false) }
    Text("Новый персонаж", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !female, onClick = { female = false }, label = { Text("Мужской") })
        FilterChip(selected = female, onClick = { female = true }, label = { Text("Женский") })
    }
    Button(onClick = { onCreate(name, female) }, enabled = !busy) { Text("Создать") }
}

@Composable
fun Playing(session: Session, onGo: (tz.shared.ExitView) -> Unit, onSignOut: () -> Unit) {
    val game = session.game ?: return
    val c = game.character
    val loc = game.location
    Text("${c.name} · HP ${c.hp}/${c.hpMax} · мана ${c.mana}/${c.manaMax}", style = MaterialTheme.typography.labelLarge)
    Text(loc.name, style = MaterialTheme.typography.headlineSmall)
    loc.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (loc.npcs.isNotEmpty()) Text("Здесь: " + loc.npcs.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium)
    loc.exits.forEach { exit ->
        OutlinedButton(onClick = { onGo(exit) }, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text(exit.label) }
    }
    TextButton(onClick = onSignOut) { Text("Выйти") }
}
