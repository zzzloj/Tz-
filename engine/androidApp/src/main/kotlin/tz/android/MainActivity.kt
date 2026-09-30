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
import androidx.compose.foundation.layout.height
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
import tz.shared.CraftOptionView
import tz.shared.ClanView
import tz.shared.MessagesView
import tz.shared.PersonView
import tz.shared.AbilityView
import tz.shared.Targets
import tz.shared.ChoiceOption
import tz.shared.PeekItem
import tz.shared.WorldView
import tz.shared.MapView
import tz.shared.ShopItemView
import tz.shared.DialogOption
import tz.shared.Rules
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
                onTalk = { npc -> run { session.talk(npc) } },
                onAnswer = { option -> run { session.answer(option) } },
                onCloseDialog = { session.closeDialog(); version++ },
                pending = session.pendingUse,
                more = MoreActions(
                    trade = { item, n -> run { session.trade(item, n) } },
                    bankPut = { item, n -> run { session.bankPut(item, n) } },
                    bankTake = { item, n -> run { session.bankTake(item, n) } },
                    use = { item -> run { session.use(item) } },
                    useOn = { target -> run { session.useOn(target) } },
                    cancelUse = { session.cancelUse(); version++ },
                    craft = { option -> run { session.craft(option) } },
                    useAbility = { a -> run { session.useAbility(a) } },
                    aimAbility = { t -> run { session.aimAbility(t) } },
                    cancelAbility = { session.cancelAbility(); version++ },
                    meditate = { run { session.meditate() } },
                    peek = { t -> run { session.peek(t) } },
                    steal = { i -> run { session.steal(i) } },
                    closePeek = { session.closePeek(); version++ },
                    look = { t -> run { session.look(t) } },
                    closeLook = { session.closeLook(); version++ },
                    dropOne = { i -> run { session.drop(i, 1) } },
                    takeOne = { i -> run { session.take(i, 1) } },
                    dismount = { run { session.dismount() } },
                    tame = { n -> run { session.tame(n) } },
                    raise = { c -> run { session.raise(c) } },
                    gallop = { e -> run { session.gallop(e) } },
                    openWorld = { run { session.openWorld() } },
                    closeWorld = { session.closeWorld(); version++ },
                    openMap = { run { session.openMap() } },
                    closeMap = { session.closeMap(); version++ },
                    stele = { run { session.stele() } },
                    dropFlag = { run { session.dropFlag() } },
                ),
                pendingAbility = session.pendingAbility,
                social = SocialActions(
                    answerText = { t -> run { session.answerText(t) } },
                    say = { t, clan -> run { session.say(t, clan) } },
                    openMail = { run { session.openMail() } },
                    closeMail = { session.closeMail(); version++ },
                    write = { to, t -> run { session.write(to, t) } },
                    addContact = { n -> run { session.addContact(n) } },
                    removeContact = { n -> run { session.removeContact(n) } },
                    startExchange = { person -> run { session.startExchange(person) } },
                    attackPlayer = { person -> run { session.attackPlayer(person) } },
                    offer = { item, n -> run { session.offer(item, n) } },
                    withdraw = { item -> run { session.withdraw(item) } },
                    agree = { run { session.agree() } },
                    cancelExchange = { run { session.cancelExchange() } },
                    openClan = { run { session.openClan() } },
                    closeClan = { session.closeClan(); version++ },
                    clanOp = { op, name, rank, clan -> run { session.clanOp(op, name, rank, clan) } },
                    castleOp = { op, text -> run { session.castleOp(op, text) } },
                    choose = { o -> run { session.choose(o) } },
                    writeAll = { t -> run { session.writeAll(t) } },
                    closeChoice = { session.closeChoice(); version++ },
                ),
                mail = session.mail,
                clanInfo = session.clanInfo,
                worldInfo = session.world,
                mapView = session.map.takeIf { session.mapOpen },
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
    onTalk: (NpcView) -> Unit,
    onAnswer: (DialogOption) -> Unit,
    onCloseDialog: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    pending: InventoryItemView? = null,
    pendingAbility: AbilityView? = null,
    more: MoreActions = MoreActions(),
    social: SocialActions = SocialActions(),
    mail: MessagesView? = null,
    clanInfo: ClanView? = null,
    worldInfo: WorldView? = null,
    mapView: MapView? = null,
) {
    val c = game.character
    val loc = game.location
    val small = MaterialTheme.typography.bodyMedium
    Text("${c.name} · HP ${c.hp}/${c.hpMax} · мана ${c.mana}/${c.manaMax}", style = MaterialTheme.typography.labelLarge)
    c.crime?.let { Text("Вы $it — стража ищет вас ещё ${c.crimeMinutes} мин", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
    if (c.poisoned) Text("Вы отравлены: здоровье убывает", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    if (c.flag) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("У вас флаг лидерства", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = more.dropFlag, enabled = !busy) { Text("бросить") }
    }
    game.stele?.let { place ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${c.spouse ?: "Супруг"} ранен(а): $place", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = more.stele, enabled = !busy && !c.ghost) { Text("на помощь") }
        }
    }
    game.alarm?.let { castle ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("В $castle чужие!", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { social.castleOp("tele", null) }, enabled = !busy && !c.ghost) { Text("в замок") }
        }
    }
    Row {
        TextButton(onClick = more.openWorld, enabled = !busy) { Text("Мир") }
        TextButton(onClick = more.openMap, enabled = !busy) { Text("Карта") }
    }
    worldInfo?.let { WorldPanel(it, more.closeWorld) }
    mapView?.let { MapPanel(it, c.location, worldInfo?.flagLocationId, more.closeMap) }
    if (c.mounted) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Вы верхом", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = more.dismount, enabled = !busy) { Text("спешиться") }
    }
    game.choice?.let { ch ->
        Panel(ch.title, social.closeChoice) {
            ch.options.forEach { o -> TextButton(onClick = { social.choose(o) }, enabled = !busy) { Text(o.label) } }
        }
    }
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
    game.dialog?.let { d ->
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.npcName, style = MaterialTheme.typography.titleMedium)
                Text(d.text, style = small)
                if (d.inputTopic != null) {
                    var typed by remember(d.text) { mutableStateOf("") }
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { social.answerText(typed) }, enabled = !busy && typed.isNotBlank()) { Text("ответить") }
                }
                d.options.forEach { o ->
                    TextButton(onClick = { onAnswer(o) }, enabled = !busy) { Text(o.label) }
                }
                TextButton(onClick = onCloseDialog) { Text(if (d.options.isEmpty()) "[Конец диалога]" else "закончить разговор") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = social.openMail, enabled = !busy) { Text(if (game.unread > 0) "Почта (${game.unread})" else "Почта") }
        OutlinedButton(onClick = social.openClan, enabled = !busy) {
            Text((game.clan?.let { "Клан $it" } ?: "Клан") + if (game.clanInvites.isNotEmpty()) " (приглашение)" else "")
        }
    }
    mail?.let { m -> MailPanel(m, busy, social) }
    clanInfo?.let { cl -> ClanPanel(cl, busy, social) }
    game.exchange?.let { ex ->
        Panel("Обмен с ${ex.partner}" + if (ex.waiting) " (ждём его)" else "", social.cancelExchange) {
            Text("Вы отдаёте:" + if (ex.iAgree) " ✓ согласны" else "", style = small)
            ex.mine.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.name} ×${item.count}", Modifier.weight(1f), style = small)
                    TextButton(onClick = { social.withdraw(item) }, enabled = !busy) { Text("убрать") }
                }
            }
            Text("${ex.partner} отдаёт:" + if (ex.theyAgree) " ✓ согласен" else "", style = small)
            ex.theirs.forEach { item -> Text("${item.name} ×${item.count}", style = small) }
            Text("Добавить из рюкзака:", style = MaterialTheme.typography.labelMedium)
            game.inventory.filter { !it.equipped && ex.mine.none { m -> m.id == it.id } }.forEach { item ->
                TextButton(onClick = { social.offer(item, item.count) }, enabled = !busy) { Text("+ ${item.name} ×${item.count}") }
            }
            Button(onClick = social.agree, enabled = !busy && !ex.iAgree && !ex.waiting) { Text("Согласен") }
        }
    }
    game.shop?.let { shop ->
        Panel(shop.npcName + if (shop.mode == "sell") " покупает" else " продаёт", onCloseDialog) {
            shop.message?.let { Text(it, style = small) }
            shop.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.name}${if (item.count > 1) " (${item.count})" else ""} — ${item.price} ${shop.currency}", Modifier.weight(1f), style = small)
                    val verb = if (shop.mode == "sell") "продать" else "купить"
                    TextButton(onClick = { more.trade(item, 1) }, enabled = !busy) { Text(verb) }
                    if (item.count > 1) TextButton(onClick = { more.trade(item, item.count) }, enabled = !busy) { Text("все") }
                }
            }
        }
    }
    game.bank?.let { bank ->
        Panel("Банк · ${bank.npcName}" + if (bank.fee > 0) " (плата ${bank.fee})" else "", onCloseDialog) {
            bank.message?.let { Text(it, style = small) }
            if (bank.items.isEmpty()) Text("в ячейке пусто", style = small)
            bank.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.name}${if (item.count > 1) " ×${item.count}" else ""}", Modifier.weight(1f), style = small)
                    TextButton(onClick = { more.bankTake(item, 1) }, enabled = !busy) { Text("забрать") }
                    if (item.count > 1) TextButton(onClick = { more.bankTake(item, item.count) }, enabled = !busy) { Text("все") }
                }
            }
            Text("Положить из рюкзака:", style = MaterialTheme.typography.labelMedium)
            game.inventory.filter { !it.equipped }.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.name}${if (item.count > 1) " ×${item.count}" else ""}", Modifier.weight(1f), style = small)
                    TextButton(onClick = { more.bankPut(item, item.count) }, enabled = !busy) { Text("в банк") }
                }
            }
        }
    }
    game.craft?.let { craft ->
        Panel(craft.title, onCloseDialog) {
            craft.options.forEach { o ->
                TextButton(onClick = { more.craft(o) }, enabled = !busy) { Text("${o.name} — ${o.chance}% (${o.needs})") }
            }
        }
    }
    pending?.let { p ->
        Panel("Применить «${p.name}» к…", more.cancelUse) {
            val choices = Targets.choices(p.target, game, exceptItem = p.id)
            if (choices.isEmpty()) Text("здесь не на кого", style = small)
            choices.forEach { t -> TextButton(onClick = { more.useOn(t.value) }, enabled = !busy) { Text(t.label) } }
        }
    }
    pendingAbility?.let { a ->
        Panel("«${a.name}» — на кого?", more.cancelAbility) {
            val choices = Targets.choices(a.target, game)
            if (choices.isEmpty()) Text("здесь не на кого", style = small)
            choices.forEach { t -> TextButton(onClick = { more.aimAbility(t.value) }, enabled = !busy) { Text(t.label) } }
        }
    }
    game.stance?.let { Text("Стойка: $it", style = MaterialTheme.typography.labelMedium) }
    if (game.abilities.isNotEmpty() && !c.ghost) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }) { Text(if (open) "Магия и приёмы ▲" else "Магия и приёмы ▼") }
        if (open) game.abilities.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.name + (if (a.manaCost > 0) " · мана ${a.manaCost}" else "") +
                        (if (a.readyIn > 0) " · через ${(a.readyIn + 59) / 60} мин" else "") + (if (a.later) " · позже" else ""),
                    Modifier.weight(1f), style = small,
                )
                val verb = when (a.kind) { "spell" -> "читать"; "stance" -> "встать"; else -> "ударить" }
                TextButton(onClick = { more.useAbility(a) }, enabled = !busy && a.readyIn == 0L && !a.later) { Text(verb) }
                TextButton(onClick = { more.look(a.id) }, enabled = !busy) { Text("?") }
            }
        }
    }
    game.look?.let { l ->
        Panel(l.title, more.closeLook) { Text(l.text, style = small) }
    }
    game.peek?.let { pk ->
        Panel("Рюкзак: ${pk.targetName}", more.closePeek) {
            pk.items.forEach { pi ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pi.name + (if (pi.count > 1) " ×${pi.count}" else "") + (if (pi.equipped) " (надето)" else ""), Modifier.weight(1f), style = small)
                    TextButton(onClick = { more.steal(pi) }, enabled = !busy) { Text("украсть") }
                }
            }
        }
    }
    run {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }) { Text(if (open) "Персонаж ▲" else "Персонаж ▼") }
        if (open) {
            Text("${c.rank} ${c.title}", style = small)
            Text("парирование ${c.parry} · уклон от магии ${c.magicDodge} · защита от магии ${c.magicParry} · сопр. магии ${c.magicResist}", style = small)
            c.skills.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Rules.skillTitle(k)} $v", Modifier.weight(1f), style = small)
                    if (k == "meditation" && !c.ghost) TextButton(onClick = more.meditate, enabled = !busy) { Text("медитировать") }
                    TextButton(onClick = { more.look("skill.$k") }, enabled = !busy) { Text("?") }
                }
            }
        }
    }
    Text(loc.name, style = MaterialTheme.typography.headlineSmall)
    game.castle?.let { cs ->
        Text(
            (cs.owner?.let { "Замок принадлежит клану $it" } ?: "Замок никому не принадлежит: первый член клана, вошедший в ворота, захватит его") +
                (if (cs.lockedMinutes > 0) " · ворота заперты ещё ${cs.lockedMinutes} мин." else "") +
                (if (cs.guest) " · вы гость" else ""),
            style = small,
        )
        if (cs.sign.isNotBlank()) Text("Надпись на воротах: ${cs.sign}", style = small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cs.canKnock) OutlinedButton(onClick = { social.castleOp("knock", null) }, enabled = !busy) { Text("Постучать") }
            if (cs.canOpen) OutlinedButton(onClick = { social.castleOp("open", null) }, enabled = !busy) { Text("Открыть ворота") }
        }
        if (cs.member) {
            var sign by remember(cs.id) { mutableStateOf(cs.sign) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(sign, { sign = it }, label = { Text("Вывеска") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { social.castleOp("sign", sign) }, enabled = !busy) { Text("сохранить") }
            }
        }
    }
    loc.description?.let { Text(it, style = small) }
    val thief = (c.skills["steal"] ?: 0) > 0 || (c.skills["steallook"] ?: 0) > 0
    loc.npcs.forEach { npc ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                npc.name + (npc.owner?.let { if (npc.mine) " (ваш)" else " ($it)" } ?: "") + (if (npc.attackable) " · HP ${npc.hp}/${npc.hpMax}" else "") + (npc.attacking?.let { " · атакует $it" } ?: ""),
                Modifier.weight(1f), style = small,
                color = if (npc.fightingYou) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (npc.canTalk) TextButton(onClick = { onTalk(npc) }, enabled = !busy) { Text("говорить") }
            TextButton(onClick = { more.look(npc.id) }, enabled = !busy) { Text("?") }
            if (!c.ghost && !npc.mine && npc.owner == null && npc.id.startsWith("n.a.") && (c.skills["animaltaming"] ?: 0) > 0)
                TextButton(onClick = { more.tame(npc) }, enabled = !busy) { Text("приручить") }
            if (thief && !c.ghost) TextButton(onClick = { more.peek(npc.id) }, enabled = !busy) { Text("подглядеть") }
            if (npc.attackable && !c.ghost) TextButton(onClick = { onAttack(npc) }, enabled = !busy) { Text("атаковать") }
        }
    }
    game.people.forEach { person ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                person.name + (person.clan?.let { " *$it*" } ?: "") + (person.crime?.let { " [$it]" } ?: "") +
                    (person.faction?.let { " $it" } ?: "") + (person.hpPercent?.let { " $it%" } ?: "") + (if (person.rider) " (всадник)" else "") +
                    (if (person.flag) " с флагом!" else "") +
                    (person.attacking?.let { " · атакует $it" } ?: "") + if (person.ghost) " (призрак)" else "",
                Modifier.weight(1f), style = small,
                color = if (person.crime != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (!c.ghost && !person.ghost) TextButton(onClick = { social.attackPlayer(person) }, enabled = !busy) { Text("атаковать") }
            TextButton(onClick = { more.look(person.name) }, enabled = !busy) { Text("?") }
            if (thief && !c.ghost && !person.ghost) TextButton(onClick = { more.peek(person.name) }, enabled = !busy) { Text("подглядеть") }
            if (!c.ghost && !person.ghost) TextButton(onClick = { social.startExchange(person) }, enabled = !busy) { Text("обмен") }
            TextButton(onClick = { social.addContact(person.name) }, enabled = !busy) { Text("в контакты") }
            if (game.clan != null && person.clan == null) TextButton(onClick = { social.clanOp("invite", person.name, null, null) }, enabled = !busy) { Text("в клан") }
        }
    }
    loc.items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.name}${if (item.count > 1) " ×${item.count}" else ""}", Modifier.weight(1f))
            if (item.takeable) TextButton(onClick = { onTake(item) }, enabled = !busy) { Text(if (item.id.startsWith("i.s.")) "использовать" else "взять") }
            if (item.takeable && item.count > 1) TextButton(onClick = { more.takeOne(item) }, enabled = !busy) { Text("1") }
            TextButton(onClick = { more.look(item.id) }, enabled = !busy) { Text("?") }
        }
    }
    loc.corpses.forEach { corpse ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(corpse.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (corpse.canButcher && !c.ghost) TextButton(onClick = { onButcher(corpse) }, enabled = !busy) { Text("разделать") }
            if (corpse.canRaise && !c.ghost && (c.skills["necro"] ?: 0) > 0) TextButton(onClick = { more.raise(corpse) }, enabled = !busy) { Text("поднять") }
        }
        if (corpse.looting && corpse.items.isNotEmpty()) Text("  взять отсюда — мародёрство", style = small, color = MaterialTheme.colorScheme.error)
        corpse.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("  ${item.name}${if (item.count > 1) " ×${item.count}" else ""}", Modifier.weight(1f), style = small)
                if (!c.ghost) TextButton(onClick = { onLoot(corpse, item) }, enabled = !busy) { Text("взять") }
            }
        }
    }
    loc.exits.forEach { exit ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onGo(exit) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(exit.label + if (exit.occupied) " !" else "") }
            if (exit.gallop) TextButton(onClick = { more.gallop(exit) }, enabled = !busy) { Text("галопом") }
        }
    }
    TextButton(onClick = onRefresh, enabled = !busy) { Text("осмотреться") }

    if (game.journal.isNotEmpty()) {
        Text("Журнал", style = MaterialTheme.typography.titleMedium)
        game.journal.takeLast(10).forEach { Text(it, style = small) }
    }
    var speech by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(speech, { speech = it }, label = { Text("Сказать") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { social.say(speech, false); speech = "" }, enabled = !busy && speech.isNotBlank()) { Text("всем") }
        if (game.clan != null) TextButton(onClick = { social.say(speech, true); speech = "" }, enabled = !busy && speech.isNotBlank()) { Text("клану") }
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
            if (item.usable && !c.ghost) TextButton(onClick = { more.use(item) }, enabled = !busy) { Text("исп.") }
            TextButton(onClick = { more.look(item.id) }, enabled = !busy) { Text("?") }
            if (item.count > 1) TextButton(onClick = { more.dropOne(item) }, enabled = !busy) { Text("−1") }
            TextButton(onClick = { onDrop(item) }, enabled = !busy) { Text("бросить") }
        }
    }
    TextButton(onClick = onSignOut) { Text("Выйти") }
}

class SocialActions(
    val answerText: (String) -> Unit = {},
    val say: (String, Boolean) -> Unit = { _, _ -> },
    val openMail: () -> Unit = {},
    val closeMail: () -> Unit = {},
    val write: (String, String) -> Unit = { _, _ -> },
    val addContact: (String) -> Unit = {},
    val removeContact: (String) -> Unit = {},
    val startExchange: (PersonView) -> Unit = {},
    val attackPlayer: (PersonView) -> Unit = {},
    val offer: (InventoryItemView, Int) -> Unit = { _, _ -> },
    val withdraw: (ShopItemView) -> Unit = {},
    val agree: () -> Unit = {},
    val cancelExchange: () -> Unit = {},
    val openClan: () -> Unit = {},
    val closeClan: () -> Unit = {},
    val clanOp: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> },
    val castleOp: (String, String?) -> Unit = { _, _ -> },
    val choose: (ChoiceOption) -> Unit = {},
    val writeAll: (String) -> Unit = {},
    val closeChoice: () -> Unit = {},
)

@Composable
fun WorldPanel(w: WorldView, onClose: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Panel("Мир", onClose) {
        Text("Флаг лидерства: " + (w.flagHolder?.let { "у $it (${w.flagLocation ?: "?"})" } ?: "лежит: ${w.flagLocation ?: "неизвестно где"}"), style = small)
        Text("Замки:", style = MaterialTheme.typography.labelMedium)
        w.castles.forEach { Text("${it.name}: ${it.owner ?: "ничей"}", style = small) }
        Text("Кланы:", style = MaterialTheme.typography.labelMedium)
        if (w.clans.isEmpty()) Text("пока нет", style = small)
        w.clans.forEach { Text("${it.name} — ${it.members}", style = small) }
        Text("Сейчас в игре ${w.online.size}:", style = MaterialTheme.typography.labelMedium)
        w.online.forEach { o -> Text("${o.name} [${o.level}]" + (o.clan?.let { " *$it*" } ?: "") + (o.crime?.let { " $it" } ?: ""), style = small) }
    }
}

/** The map (m.php): the locations of this part of the world as dots, you, the flag and the castles. */
@Composable
fun MapPanel(m: MapView, here: String, flagAt: String?, onClose: () -> Unit) {
    val me = Rules.mapPoint(here)
    val region = me?.third ?: 0
    fun regionOf(x: Int, y: Int) = if (y > 1101) 2 else if (x > 1650) 1 else 0
    val pts = m.points.filter { regionOf(it.x, it.y) == region }
    val title = when (region) { 1 -> "Карта: Ансалон"; 2 -> "Карта: Волчий остров"; else -> "Карта: основная территория" }
    Panel(title, onClose) {
        if (pts.isEmpty()) { Text("Нет данных"); return@Panel }
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val guarded = MaterialTheme.colorScheme.primary
        val plain = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        val mine = MaterialTheme.colorScheme.error
        val flagColor = androidx.compose.ui.graphics.Color(0xFFE0B000)
        val castle = androidx.compose.ui.graphics.Color(0xFFB03030)
        val castles = listOf("c.1.gate", "c.2.gate", "c.3.gate", "c.4.gate").mapNotNull { Rules.mapPoint(it) }.filter { it.third == region }
        val flag = flagAt?.let { Rules.mapPoint(it) }?.takeIf { it.third == region }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(260.dp)) {
            val w = size.width; val h = size.height
            val sx = w / (maxX - minX + 1).coerceAtLeast(1); val sy = h / (maxY - minY + 1).coerceAtLeast(1)
            val k = minOf(sx, sy)
            fun at(x: Int, y: Int) = androidx.compose.ui.geometry.Offset((x - minX) * k, (y - minY) * k)
            val d = (k * 6).coerceIn(2f, 6f)
            for (p in pts) drawCircle(if (p.zone == 1) guarded else plain, radius = d / 2, center = at(p.x, p.y))
            for (c in castles) drawCircle(castle, radius = d * 1.5f, center = at(c.first, c.second))
            flag?.let { drawCircle(flagColor, radius = d * 1.5f, center = at(it.first, it.second)) }
            me?.let { drawCircle(mine, radius = d * 2, center = at(it.first, it.second)) }
        }
        Text("красное — вы, жёлтое — флаг лидерства, бордовое — замки, яркие точки — охраняемые улицы", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MailPanel(m: MessagesView, busy: Boolean, social: SocialActions) {
    var to by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    Panel("Почта", social.closeMail) {
        Text("Контакты (добавить можно того, кто рядом; писать — тем, у кого вы в контактах):", style = MaterialTheme.typography.labelMedium)
        if (m.contacts.isEmpty()) Text("пока никого", style = MaterialTheme.typography.bodySmall)
        m.contacts.forEach { ct ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ct.name + (if (ct.online) " • в игре" else "") + (if (ct.mutual) "" else " (вы не у него в контактах)"),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { to = ct.name }, enabled = !busy) { Text("написать") }
                TextButton(onClick = { social.removeContact(ct.name) }, enabled = !busy) { Text("убрать") }
            }
        }
        if (m.contacts.any { it.mutual }) TextButton(onClick = { to = "*" }, enabled = !busy) { Text("написать всем") }
        to?.let { name ->
            OutlinedTextField(text, { text = it }, label = { Text(if (name == "*") "Сообщение всем контактам" else "Сообщение для $name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if (name == "*") social.writeAll(text) else social.write(name, text); text = ""; to = null }, enabled = !busy && text.isNotBlank()) { Text("Отправить") }
        }
        Text("Сообщения:", style = MaterialTheme.typography.labelMedium)
        if (m.messages.isEmpty()) Text("нет сообщений", style = MaterialTheme.typography.bodySmall)
        m.messages.forEach { msg ->
            Text((if (msg.clan) "[клан] " else "") + "${msg.from}: ${msg.text}", style = MaterialTheme.typography.bodySmall,
                color = if (msg.read) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ClanPanel(cl: ClanView, busy: Boolean, social: SocialActions) {
    Panel(cl.name?.let { "Клан $it" } ?: "Клан", social.closeClan) {
        cl.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (cl.name == null) {
            Text("Вы не в клане. Создать клан можно у Мирандера на центральной площади.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Ваш ранг: ${Rules.CLAN_RANKS[cl.rank] ?: cl.rank}", style = MaterialTheme.typography.bodySmall)
            if (cl.info.isNotBlank()) Text(cl.info, style = MaterialTheme.typography.bodySmall)
            cl.members.forEach { mem ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${mem.name} — ${Rules.CLAN_RANKS[mem.rank] ?: mem.rank}" + if (mem.online) " • в игре" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (cl.canManage && cl.rank == "head" && mem.rank != "head") {
                        val next = when (mem.rank) { "neophyte" -> "vassal"; "vassal" -> "seneschal"; else -> "neophyte" }
                        TextButton(onClick = { social.clanOp("rank", mem.name, next, null) }, enabled = !busy) { Text("→ ${Rules.CLAN_RANKS[next]}") }
                        TextButton(onClick = { social.clanOp("kick", mem.name, null, null) }, enabled = !busy) { Text("выгнать") }
                    }
                }
            }
            TextButton(onClick = { social.clanOp("leave", null, null, null) }, enabled = !busy) {
                Text(if (cl.rank == "head") "Распустить клан" else "Выйти из клана")
            }
        }
        cl.invites.forEach { inv ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Приглашение в клан $inv", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { social.clanOp("accept", null, null, inv) }, enabled = !busy) { Text("вступить") }
                TextButton(onClick = { social.clanOp("decline", null, null, inv) }, enabled = !busy) { Text("отказать") }
            }
        }
    }
}

/** Trade, bank, crafting and item use; grouped so Playing keeps a readable signature. */
class MoreActions(
    val trade: (ShopItemView, Int) -> Unit = { _, _ -> },
    val bankPut: (InventoryItemView, Int) -> Unit = { _, _ -> },
    val bankTake: (InventoryItemView, Int) -> Unit = { _, _ -> },
    val use: (InventoryItemView) -> Unit = {},
    val useOn: (String) -> Unit = {},
    val cancelUse: () -> Unit = {},
    val craft: (CraftOptionView) -> Unit = {},
    val useAbility: (AbilityView) -> Unit = {},
    val aimAbility: (String) -> Unit = {},
    val cancelAbility: () -> Unit = {},
    val meditate: () -> Unit = {},
    val peek: (String) -> Unit = {},
    val steal: (PeekItem) -> Unit = {},
    val closePeek: () -> Unit = {},
    val look: (String) -> Unit = {},
    val closeLook: () -> Unit = {},
    val dropOne: (InventoryItemView) -> Unit = {},
    val takeOne: (GroundItemView) -> Unit = {},
    val dismount: () -> Unit = {},
    val tame: (NpcView) -> Unit = {},
    val raise: (CorpseView) -> Unit = {},
    val gallop: (ExitView) -> Unit = {},
    val openWorld: () -> Unit = {},
    val closeWorld: () -> Unit = {},
    val openMap: () -> Unit = {},
    val closeMap: () -> Unit = {},
    val stele: () -> Unit = {},
    val dropFlag: () -> Unit = {},
)

@Composable
fun Panel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
            TextButton(onClick = onClose) { Text("закрыть") }
        }
    }
}
