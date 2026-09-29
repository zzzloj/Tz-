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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tz.shared.CorpseView
import tz.shared.ExitView
import tz.shared.GameApi
import tz.shared.GameView
import tz.shared.GroundItemView
import tz.shared.InventoryItemView
import tz.shared.NpcView
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
fun App(session: Session, live: Boolean = true) {
    val scope = rememberCoroutineScope()
    // Session is plain Kotlin; bump this counter after each call to redraw.
    var version by remember { mutableIntStateOf(0) }
    fun run(action: suspend () -> Unit) {
        scope.launch { version++; action(); version++ }
    }
    LaunchedEffect(Unit) { run { session.resume() } }
    // Blows, deaths and arrivals come from the server; redraw on each.
    if (live) LaunchedEffect(Unit) { session.listen { version++ } }

    // Read the counter so this function runs again after every session call;
    // children get plain values (GameView, busy), never the Session object:
    // Compose skips a child whose arguments are the same instances.
    val tick = version
    val game = session.game
    val busy = session.busy
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (session.screen) {
            Screen.LOADING -> if (session.error != null) Button(onClick = { run { session.refresh() } }) { Text("Повторить") }
            Screen.SIGN_IN -> SignIn(busy, onSignIn = { l, p -> run { session.signIn(l, p) } }, onRegister = { l, p -> run { session.register(l, p) } })
            Screen.CREATE_CHARACTER -> CreateCharacter(busy) { name, female -> run { session.createCharacter(name, female) } }
            Screen.PLAYING -> if (game != null) Playing(
                game,
                busy,
                tick,
                onGo = { exit -> run { session.go(exit) } },
                onTake = { item -> run { session.take(item) } },
                onDrop = { item -> run { session.drop(item) } },
                onToggleEquip = { item -> run { session.toggleEquip(item) } },
                onAttack = { npc -> run { session.attack(npc) } },
                onLoot = { corpse, item -> run { session.loot(corpse, item) } },
                onButcher = { corpse -> run { session.butcher(corpse) } },
                onResurrect = { run { session.resurrect() } },
                onRefresh = { run { session.refresh() } },
                onSignOut = { run { session.signOut() } },
            )
        }
        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
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
fun Playing(
    game: GameView,
    busy: Boolean,
    @Suppress("UNUSED_PARAMETER") version: Int,
    onGo: (ExitView) -> Unit,
    onTake: (GroundItemView) -> Unit,
    onDrop: (InventoryItemView) -> Unit,
    onToggleEquip: (InventoryItemView) -> Unit,
    onAttack: (NpcView) -> Unit,
    onLoot: (CorpseView, GroundItemView) -> Unit,
    onButcher: (CorpseView) -> Unit,
    onResurrect: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = game.character
    val loc = game.location
    val small = MaterialTheme.typography.bodyMedium
    Text("${c.name} · HP ${c.hp}/${c.hpMax} · мана ${c.mana}/${c.manaMax}", style = MaterialTheme.typography.labelLarge)
    Text(
        "удар ${c.hit}% · урон ${c.dmgMin}–${c.dmgMax} · броня ${c.armor} · уклон ${c.dodge} · опыт ${c.exp}/${c.expNext}" +
            (if (c.skillPoints > 0) " · очков ${c.skillPoints}" else "") +
            (if (game.restSeconds > 0) " · отдых ${game.restSeconds} с" else ""),
        style = MaterialTheme.typography.labelMedium,
    )
    if (c.ghost) {
        Text(
            "Вы призрак. Воскреснуть можно у камня воскрешения или у лекаря Джозефа (двор к северу от Переулка).",
            color = MaterialTheme.colorScheme.error, style = small,
        )
        if (game.canResurrect) Button(onClick = onResurrect, enabled = !busy) { Text("Воскреснуть") }
    }
    Text(loc.name, style = MaterialTheme.typography.headlineSmall)
    loc.description?.let { Text(it, style = small) }
    loc.npcs.forEach { npc ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                npc.name + (if (npc.attackable) " · HP ${npc.hp}/${npc.hpMax}" else "") + (if (npc.fightingYou) " · бьёт вас" else ""),
                Modifier.weight(1f), style = small,
                color = if (npc.fightingYou) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (npc.attackable && !c.ghost) TextButton(onClick = { onAttack(npc) }, enabled = !busy) { Text("атаковать") }
        }
    }
    if (loc.players.isNotEmpty()) Text("Игроки: " + loc.players.joinToString(), style = small)
    loc.items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.name}${if (item.count > 1) " ×${item.count}" else ""}", Modifier.weight(1f))
            if (item.takeable) TextButton(onClick = { onTake(item) }, enabled = !busy) { Text("взять") }
        }
    }
    loc.corpses.forEach { corpse ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(corpse.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (corpse.canButcher && !c.ghost) TextButton(onClick = { onButcher(corpse) }, enabled = !busy) { Text("разделать") }
        }
        corpse.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("  ${item.name}${if (item.count > 1) " ×${item.count}" else ""}", Modifier.weight(1f), style = small)
                if (!c.ghost) TextButton(onClick = { onLoot(corpse, item) }, enabled = !busy) { Text("взять") }
            }
        }
    }
    loc.exits.forEach { exit ->
        OutlinedButton(onClick = { onGo(exit) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(exit.label) }
    }
    TextButton(onClick = onRefresh, enabled = !busy) { Text("осмотреться") }

    if (game.journal.isNotEmpty()) {
        Text("Журнал", style = MaterialTheme.typography.titleMedium)
        game.journal.takeLast(10).forEach { Text(it, style = small) }
    }

    Text("Инвентарь", style = MaterialTheme.typography.titleMedium)
    if (game.inventory.isEmpty()) Text("пусто", style = MaterialTheme.typography.bodyMedium)
    game.inventory.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${item.name}${if (item.count > 1) " ×${item.count}" else ""}${if (item.equipped) " (надето)" else ""}",
                Modifier.weight(1f),
            )
            if (item.equippable) TextButton(onClick = { onToggleEquip(item) }, enabled = !busy) {
                Text(if (item.equipped) "снять" else "надеть")
            }
            TextButton(onClick = { onDrop(item) }, enabled = !busy) { Text("бросить") }
        }
    }
    TextButton(onClick = onSignOut) { Text("Выйти") }
}
