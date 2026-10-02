package tz.android

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tz.shared.AbilityView
import tz.shared.ClanView
import tz.shared.CorpseView
import tz.shared.Design
import tz.shared.DialogOption
import tz.shared.ExitView
import tz.shared.GameView
import tz.shared.GroundItemView
import tz.shared.InventoryItemView
import tz.shared.JournalKind
import tz.shared.MapView
import tz.shared.MessagesView
import tz.shared.NpcView
import tz.shared.PersonView
import tz.shared.Rules
import tz.shared.GameScene
import tz.shared.Targets
import tz.shared.WorldView
import java.net.URL

/** The five tabs of the game screen and their sub-tabs (docs/design.md, «Раскладка экрана»). */
enum class GameTab(val title: String, val icon: String, val subs: List<String>) {
    PLACE("Локация", "tab-location", emptyList()),
    HERO("Персонаж", "tab-hero", listOf("Обзор", "Навыки", "Приёмы и магия")),
    BAG("Сумка", "tab-bag", emptyList()),
    SOCIAL("Общение", "tab-social", listOf("Здесь", "Почта", "Клан", "Онлайн", "Форум")),
    WORLD("Мир", "tab-world", listOf("Карта", "Замки", "События")),
}

/** Player's choices for the screen layout: combat buttons, belt; and the site pages reachable from the tabs. */
class LayoutActions(
    val strike: (AbilityView, String) -> Unit = { _, _ -> },
    val setSlot: (Int, String) -> Unit = { _, _ -> },
    val setBelt: (Int, String) -> Unit = { _, _ -> },
    val useBelt: (Int) -> Unit = {},
    val openForum: () -> Unit = {},
    val openNews: () -> Unit = {},
    val openPages: () -> Unit = {},
    val openAccount: () -> Unit = {},
    val openAdmin: (() -> Unit)? = null,
    val openEvents: () -> Unit = {},
    val openTopic: (tz.shared.ForumTopic) -> Unit = {},
)

// ---- pictures from the server -------------------------------------------------------------

/** Turns a path from [GameScene] into a full address; null — pictures are off (tests, previews). */
val LocalArtUrl = compositionLocalOf<((String) -> String)?> { null }

/** Seconds since the shown view came from the server: countdowns (rest, next blow, cooldowns) run on from it. */
val LocalElapsed = compositionLocalOf { 0L }

/** A ghost sees the world grey. */
val LocalGhost = compositionLocalOf { false }

private val grey = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private object ArtCache {
    val images = LruCache<String, ImageBitmap>(64)
    val missing = HashSet<String>()
}

/** A picture by its path on the server, with a quiet placeholder while it loads or if there is none. */
@Composable
fun ArtImage(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, dim: Boolean = false) {
    val c = Tz.colors
    val toUrl = LocalArtUrl.current
    val url = path?.let { toUrl?.invoke(it) }
    var image by remember(url) { mutableStateOf(url?.let { ArtCache.images.get(it) }) }
    if (url != null && image == null && url !in ArtCache.missing) LaunchedEffect(url) {
        image = withContext(Dispatchers.IO) {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
        image?.let { ArtCache.images.put(url, it) } ?: ArtCache.missing.add(url)
    }
    Box(modifier.background(c.surfaceSunken)) {
        image?.let { Image(it, null, Modifier.fillMaxSize().alpha(if (dim) 0.45f else 1f), contentScale = contentScale, colorFilter = if (LocalGhost.current) grey else null) }
    }
}

// ---- icons ----------------------------------------------------------------------------------

private fun iconRes(key: String): Int = when (key) {
    "north" -> R.drawable.ic_north; "east" -> R.drawable.ic_east; "south" -> R.drawable.ic_south; "west" -> R.drawable.ic_west
    "up" -> R.drawable.ic_up; "down" -> R.drawable.ic_down; "gallop" -> R.drawable.ic_gallop
    "attack" -> R.drawable.ic_attack; "flee" -> R.drawable.ic_flee; "talk" -> R.drawable.ic_talk; "look" -> R.drawable.ic_look
    "take" -> R.drawable.ic_take; "drop" -> R.drawable.ic_drop; "use" -> R.drawable.ic_use; "equip" -> R.drawable.ic_equip
    "trade" -> R.drawable.ic_trade; "give" -> R.drawable.ic_give; "mail" -> R.drawable.ic_mail; "clan" -> R.drawable.ic_clan
    "tab-location" -> R.drawable.ic_tab_location; "tab-hero" -> R.drawable.ic_tab_hero; "tab-bag" -> R.drawable.ic_tab_bag
    "tab-social" -> R.drawable.ic_tab_social; "tab-world" -> R.drawable.ic_tab_world
    "health" -> R.drawable.ic_health; "mana" -> R.drawable.ic_mana; "rest" -> R.drawable.ic_rest; "gold" -> R.drawable.ic_gold
    "settings" -> R.drawable.ic_settings
    else -> R.drawable.ic_enter
}

@Composable
fun TzIcon(key: String, modifier: Modifier = Modifier, tint: Color = Tz.colors.text) {
    Icon(painterResource(iconRes(key)), Design.ICONS[key], modifier.size(Design.Size.ACTION_ICON.dp), tint = tint)
}

/** A square action button of a row: an icon, or a picture of a spell; pale while resting. */
@Composable
fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit, icon: String? = null, art: String? = null, danger: Boolean = false, badge: String? = null) {
    val c = Tz.colors
    val shape = RoundedCornerShape(Design.Radius.M.dp)
    Box(
        Modifier.size(Design.Size.TOUCH.dp).clip(shape)
            .background(if (danger) c.dangerFill else c.panel)
            .border(Design.Size.BORDER.dp, if (danger) c.danger else c.border, shape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when {
            art != null -> ArtImage(art, Modifier.fillMaxSize().padding(3.dp))
            icon != null -> TzIcon(icon, tint = if (danger) c.onDanger else c.text)
        }
        badge?.let { Text(it, Modifier.align(Alignment.BottomEnd).padding(2.dp), style = Tz.type.number, color = c.title) }
    }
}

// ---- the screen -----------------------------------------------------------------------------

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
    layout: LayoutActions = LayoutActions(),
    mail: MessagesView? = null,
    clanInfo: ClanView? = null,
    worldInfo: WorldView? = null,
    mapView: MapView? = null,
    chronicle: tz.shared.ChronicleView? = null,
    news: tz.shared.ForumView? = null,
    page: (@Composable () -> Unit)? = null,
    onClosePage: () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val c = Tz.colors
    var tab by rememberSaveable { mutableStateOf(GameTab.PLACE) }
    var sub by rememberSaveable { mutableStateOf(0) }
    fun open(t: GameTab, s: Int = 0) {
        if (page != null) onClosePage()
        tab = t; sub = s
        // The map is the first thing on «Мир»: load it right away.
        if (t == GameTab.WORLD && s == 0 && mapView == null) more.openMap()
    }
    // Countdowns run on between server updates, a second at a time, for as long as one is running.
    var elapsed by remember(game) { mutableLongStateOf(0L) }
    val longest = maxOf(game.restSeconds, game.location.npcs.maxOfOrNull { it.nextBlow ?: 0 } ?: 0,
        game.abilities.filter { it.readyIn <= 600 }.maxOfOrNull { it.readyIn.toInt() } ?: 0)
    LaunchedEffect(game) { repeat(longest) { delay(1000); elapsed++ } }

    CompositionLocalProvider(LocalElapsed provides elapsed, LocalGhost provides game.character.ghost) {
    Column(Modifier.fillMaxSize().background(c.background)) {
        Header(game, onRefresh)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Design.Space.SCREEN.dp, vertical = Design.Space.S.dp),
                verticalArrangement = Arrangement.spacedBy(Design.Space.S.dp),
            ) {
                if (page != null) page()
                else if (tab.subs.isNotEmpty()) SubTabs(tab.subs, sub) { i ->
                    sub = i
                    when {
                        tab == GameTab.SOCIAL && i == 1 && mail == null -> social.openMail()
                        tab == GameTab.SOCIAL && i == 2 && clanInfo == null -> social.openClan()
                        tab == GameTab.SOCIAL && i == 3 && worldInfo == null -> more.openWorld()
                        tab == GameTab.WORLD && i == 0 && mapView == null -> more.openMap()
                        tab == GameTab.WORLD && i == 1 && worldInfo == null -> more.openWorld()
                        tab == GameTab.WORLD && i == 2 && chronicle == null -> layout.openEvents()
                        tab == GameTab.SOCIAL && i == 4 -> layout.openForum()
                    }
                }
                if (page == null) when (tab) {
                    GameTab.PLACE -> PlaceTab(game, busy, onTake, onAttack, onLoot, onButcher, onResurrect, onTalk, more, social, layout)
                    GameTab.HERO -> HeroTab(game, busy, sub, more, layout, onSignOut)
                    GameTab.BAG -> BagTab(game, busy, onDrop, onToggleEquip, more, layout)
                    GameTab.SOCIAL -> SocialTab(game, busy, sub, social, more, layout, mail, clanInfo, worldInfo)
                    GameTab.WORLD -> WorldTab(game, busy, sub, social, more, layout, worldInfo, mapView, chronicle, news)
                }
                footer()
            }
            Sheets(game, busy, onAnswer, onCloseDialog, pending, pendingAbility, more, social, Modifier.align(Alignment.BottomCenter))
        }
        if (tab == GameTab.PLACE || tab == GameTab.BAG) Belt(game, busy, layout) { open(GameTab.BAG) }
        Exits(game.location.exits, busy, onGo, more.gallop)
        TabBar(tab, game.unread + game.forumReplies) { open(it) }
    }
    }
}

@Composable
private fun Header(game: GameView, onRefresh: () -> Unit) {
    val c = Tz.colors
    val ch = game.character
    val fighting = game.location.npcs.any { it.fightingYou } || game.people.any { it.attacking == "вас" }
    val rest = GameScene.left(game.restSeconds, LocalElapsed.current)
    val low = GameScene.lowHealth(game) && !ch.ghost
    val state = when {
        ch.ghost -> "призрак"
        rest > 0 -> "отдых $rest с"
        fighting -> "в бою"
        game.location.zone == 1 -> "в безопасности"
        else -> ""
    }
    Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = Design.Space.SCREEN.dp, vertical = Design.Space.S.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(Design.Size.MEDAL.dp).clip(RoundedCornerShape(50)).background(c.primary).clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) { Text(ch.name.take(1), style = Tz.type.heading, color = c.onPrimary) }
            Column(Modifier.weight(1f).padding(start = Design.Space.S.dp)) {
                Text(ch.name, style = Tz.type.name, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(ch.rank, ch.title).filter { it.isNotBlank() }.joinToString(" · "), style = Tz.type.small, color = c.textMuted)
            }
            if (state.isNotEmpty()) Text(state, style = Tz.type.label, color = if (fighting || ch.ghost || low) c.danger else c.textMuted)
        }
        Spacer(Modifier.height(Design.Space.XS.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.S.dp), verticalAlignment = Alignment.CenterVertically) {
            TzIcon("health", Modifier.size(14.dp), c.danger)
            TzBar(ch.hp, ch.hpMax, c.health, Modifier.weight(1f))
            Text("${ch.hp}/${ch.hpMax}", style = Tz.type.number, color = c.text)
            TzIcon("mana", Modifier.size(14.dp), c.link)
            TzBar(ch.mana, ch.manaMax, c.mana, Modifier.weight(1f))
            Text("${ch.mana}/${ch.manaMax}", style = Tz.type.number, color = c.text)
        }
    }
}

@Composable
private fun SubTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Tz.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        titles.forEachIndexed { i, t ->
            val on = i == selected
            Text(
                t, style = Tz.type.tab, color = if (on) c.title else c.textMuted,
                modifier = Modifier.clip(RoundedCornerShape(Design.Radius.M.dp))
                    .background(if (on) c.surfaceRaised else Color.Transparent)
                    .border(Design.Size.BORDER.dp, if (on) c.border else Color.Transparent, RoundedCornerShape(Design.Radius.M.dp))
                    .clickable { onSelect(i) }.padding(horizontal = Design.Space.M.dp, vertical = Design.Space.S.dp),
            )
        }
    }
}

@Composable
private fun TabBar(tab: GameTab, unread: Int, onSelect: (GameTab) -> Unit) {
    val c = Tz.colors
    Row(Modifier.fillMaxWidth().height(Design.Size.TAB_BAR.dp).background(c.surface).border(Design.Size.BORDER.dp, c.borderSoft)) {
        GameTab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) }.semantics { contentDescription = t.title },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                TzIcon(t.icon, Modifier.size(Design.Size.TAB_ICON.dp), if (on) c.accent else c.textMuted)
                Text(t.title + if (t == GameTab.SOCIAL && unread > 0) " ·$unread" else "", style = Tz.type.tab, color = if (on) c.title else c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Exits(exits: List<ExitView>, busy: Boolean, onGo: (ExitView) -> Unit, onGallop: (ExitView) -> Unit) {
    val c = Tz.colors
    Row(
        Modifier.fillMaxWidth().background(c.surfaceSunken).horizontalScroll(rememberScrollState())
            .padding(horizontal = Design.Space.S.dp, vertical = Design.Space.XS.dp),
        horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp),
    ) {
        exits.forEach { e ->
            OutlinedButton(onClick = { onGo(e) }, enabled = !busy, modifier = Modifier.heightIn(min = Design.Size.TOUCH.dp)) {
                TzIcon(GameScene.exitIcon(e.label), Modifier.size(16.dp), c.accent)
                Spacer(Modifier.width(Design.Space.XS.dp))
                Text(e.label + if (e.occupied) " !" else "", style = Tz.type.button)
            }
            if (e.gallop) ActionButton("галопом ${e.label}", !busy, { onGallop(e) }, icon = "gallop")
        }
    }
}

@Composable
private fun Belt(game: GameView, busy: Boolean, layout: LayoutActions, onBag: () -> Unit) {
    val c = Tz.colors
    val low = GameScene.lowHealth(game)
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = Design.Space.SCREEN.dp, vertical = Design.Space.XS.dp),
        horizontalArrangement = Arrangement.spacedBy(Design.Space.S.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        GameScene.belt(game).forEachIndexed { i, (id, item) ->
            val glow = low && item != null && (id.startsWith("i.f.b.heal") || id.startsWith("i.f.b.health"))
            Box(Modifier.border(if (glow) 2.dp else 0.dp, if (glow) c.accent else Color.Transparent, RoundedCornerShape(Design.Radius.M.dp))) {
                ActionButton(
                    item?.name ?: "пустая ячейка пояса", !busy && item != null && !game.character.ghost, { layout.useBelt(i) },
                    art = id.takeIf { it.isNotEmpty() }?.let(GameScene::itemPath), badge = item?.count?.toString(),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ActionButton("Сумка", true, onBag, icon = "tab-bag")
    }
}

// ---- Локация ---------------------------------------------------------------------------------

@Composable
private fun PlaceTab(
    game: GameView, busy: Boolean,
    onTake: (GroundItemView) -> Unit, onAttack: (NpcView) -> Unit, onLoot: (CorpseView, GroundItemView) -> Unit,
    onButcher: (CorpseView) -> Unit, onResurrect: () -> Unit, onTalk: (NpcView) -> Unit,
    more: MoreActions, social: SocialActions, layout: LayoutActions,
) {
    val c = Tz.colors
    val ch = game.character
    val loc = game.location
    // The picture of the place with its name over it.
    Box(Modifier.fillMaxWidth().aspectRatio(Design.Size.SCENE_RATIO).clip(RoundedCornerShape(Design.Radius.L.dp)).border(Design.Size.BORDER.dp, c.border, RoundedCornerShape(Design.Radius.L.dp))) {
        ArtImage(loc.art?.let(GameScene::artPath), Modifier.fillMaxSize(), dim = ch.ghost)
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(c.shadow.copy(alpha = 0.55f)).padding(Design.Space.S.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(loc.name, Modifier.weight(1f), style = Tz.type.heading, color = c.title)
            if (loc.zone == 1) Chip("охраняется")
            val enemies = loc.npcs.count { it.fightingYou }
            if (enemies > 0) Chip("бой · $enemies", danger = true)
        }
    }
    Notices(game, busy, onResurrect, more, social)
    loc.description?.let { Text(it, style = Tz.type.body, color = c.text) }
    game.castle?.let { CastleBlock(it, busy, social) }

    val thief = (ch.skills["steal"] ?: 0) > 0 || (ch.skills["steallook"] ?: 0) > 0
    val slots = GameScene.slots(game)
    val resting = GameScene.left(game.restSeconds, LocalElapsed.current) > 0
    val elapsed = LocalElapsed.current
    val groups = GameScene.groups(game)
    @Composable fun npcRow(npc: NpcView) {
        val status = when {
            npc.fightingYou -> "бьёт вас" + (npc.nextBlow?.let { " · удар через ${GameScene.left(it, elapsed)} с" } ?: "")
            npc.attacking != null -> "бьёт ${npc.attacking}"
            npc.mine -> "ваш"
            npc.owner != null -> "хозяин: ${npc.owner}"
            npc.hostile -> "бродит, не замечает вас"
            npc.canTalk -> "можно поговорить"
            else -> null
        }
        val title = npc.name + if (npc.attackable && npc.hpMax > 0) "  ${npc.hp}/${npc.hpMax}" else ""
        ListRow(title, status, npc.art?.let(GameScene::artPath), hp = if (npc.attackable && npc.hpMax > 0) npc.hp to npc.hpMax else null, hurt = npc.fightingYou, undead = npc.undead,
            extra = {
                TextButton(onClick = { more.look(npc.id) }, enabled = !busy) { Text("осмотреть") }
                if (!ch.ghost && !npc.mine && npc.owner == null && npc.id.startsWith("n.a.") && (ch.skills["animaltaming"] ?: 0) > 0)
                    TextButton(onClick = { more.tame(npc) }, enabled = !busy) { Text("приручить") }
                if (thief && !ch.ghost) TextButton(onClick = { more.peek(npc.id) }, enabled = !busy) { Text("подглядеть") }
                if (npc.attackable && !ch.ghost && npc.canTalk) TextButton(onClick = { onAttack(npc) }, enabled = !busy) { Text("атаковать") }
            },
        ) {
            if (npc.attackable && !ch.ghost && (npc.fightingYou || npc.attacking != null || !npc.canTalk)) {
                ActionButton("удар по ${npc.name}", !busy && !resting, { onAttack(npc) }, icon = "attack", danger = npc.fightingYou)
                slots.forEach { a -> SlotButton(a, npc.id, busy || resting, layout) }
            } else {
                if (npc.canTalk) ActionButton("говорить с ${npc.name}", !busy, { onTalk(npc) }, icon = "talk")
                ActionButton("осмотреть ${npc.name}", !busy, { more.look(npc.id) }, icon = "look")
            }
        }
    }
    val fight = groups.atYou.isNotEmpty() || groups.atOthers.isNotEmpty()
    if (resting && groups.atYou.isNotEmpty()) RestBanner(GameScene.left(game.restSeconds, elapsed))
    if (groups.atYou.isNotEmpty()) ListSection("бьют вас · ${groups.atYou.size}", if (groups.atYou.size > 1) "ближайший удар — сверху" else null)
    groups.atYou.forEach { npcRow(it) }
    if (groups.atOthers.isNotEmpty()) ListSection("бьют других · ${groups.atOthers.size}")
    groups.atOthers.forEach { npcRow(it) }
    if (groups.unaware.isNotEmpty()) {
        // In a fight the monsters that have not noticed you fold away; in calm they are part of the list.
        var shown by remember(fight) { mutableStateOf(!fight) }
        if (fight) ListSection("не заметили вас · ${groups.unaware.size}", if (shown) "▾" else "▸") { shown = !shown }
        if (shown) groups.unaware.forEach { npcRow(it) }
    }
    if (fight && groups.rest.isNotEmpty()) ListSection("рядом")
    groups.rest.forEach { npcRow(it) }
    game.people.forEach { p -> PersonRow(p, game, busy, thief, more, social) }
    loc.items.forEach { item ->
        ListRow(item.name + if (item.count > 1) " ×${item.count}" else "", "лежит на земле", GameScene.itemPath(item.id),
            extra = { if (item.takeable && item.count > 1) TextButton(onClick = { more.takeOne(item) }, enabled = !busy) { Text("взять одну") } },
        ) {
            if (item.takeable) ActionButton(if (item.id.startsWith("i.s.")) "использовать ${item.name}" else "взять ${item.name}", !busy, { onTake(item) }, icon = if (item.id.startsWith("i.s.")) "use" else "take")
            ActionButton("осмотреть ${item.name}", !busy, { more.look(item.id) }, icon = "look")
        }
    }
    loc.corpses.forEach { corpse ->
        val status = when {
            corpse.mine -> "ваши вещи · ${corpse.items.sumOf { it.count }} шт., пропадут через ${corpse.minutesLeft} мин"
            corpse.looting && corpse.items.isNotEmpty() -> "взять отсюда — мародёрство"
            else -> "${corpse.items.size} вещ."
        }
        ListRow(corpse.name, status, null, hurt = corpse.mine,
            extra = {
                corpse.items.forEach { item -> TextButton(onClick = { onLoot(corpse, item) }, enabled = !busy && !ch.ghost) { Text("взять: ${item.name}" + if (item.count > 1) " ×${item.count}" else "") } }
                if (corpse.canRaise && !ch.ghost && (ch.skills["necro"] ?: 0) > 0) TextButton(onClick = { more.raise(corpse) }, enabled = !busy) { Text("поднять") }
            },
        ) {
            if (corpse.canButcher && !ch.ghost) ActionButton("разделать", !busy, { onButcher(corpse) }, icon = "use")
        }
    }
    Journal(game, 3)
}

@Composable
private fun SlotButton(a: AbilityView?, target: String, blocked: Boolean, layout: LayoutActions) {
    if (a == null) { ActionButton("пустая ячейка приёма", false, {}); return }
    val left = (a.readyIn - LocalElapsed.current).coerceAtLeast(0)
    val wait = when { left == 0L -> null; left < 60 -> "$left"; else -> "${(left + 59) / 60}м" }
    ActionButton(a.name, !blocked && left == 0L && !a.later, { layout.strike(a, target) }, art = GameScene.itemPath(a.id), badge = wait)
}

@Composable
private fun PersonRow(p: PersonView, game: GameView, busy: Boolean, thief: Boolean, more: MoreActions, social: SocialActions) {
    val ch = game.character
    val status = listOfNotNull(
        p.clan?.let { "клан $it" }, p.crime, p.faction, p.hpPercent?.let { "$it%" }, if (p.rider) "всадник" else null,
        if (p.flag) "с флагом!" else null, p.attacking?.let { "бьёт $it" }, if (p.ghost) "призрак" else null,
    ).joinToString(" · ")
    ListRow(p.name, status.ifEmpty { null }, null, hurt = p.attacking == "вас", medal = p.name.take(1),
        extra = {
            if (thief && !ch.ghost && !p.ghost) TextButton(onClick = { more.peek(p.name) }, enabled = !busy) { Text("подглядеть") }
            TextButton(onClick = { social.addContact(p.name) }, enabled = !busy) { Text("в контакты") }
            if (game.clan != null && p.clan == null) TextButton(onClick = { social.clanOp("invite", p.name, null, null) }, enabled = !busy) { Text("в клан") }
        },
    ) {
        ActionButton("осмотреть ${p.name}", !busy, { more.look(p.name) }, icon = "look")
        if (!ch.ghost && !p.ghost) ActionButton("обмен с ${p.name}", !busy, { social.startExchange(p) }, icon = "give")
        if (!ch.ghost && !p.ghost) ActionButton("удар по ${p.name}", !busy && game.restSeconds == 0, { social.attackPlayer(p) }, icon = "attack", danger = p.attacking == "вас")
    }
}

/** One line of the place: portrait, name, status, up to four buttons; a tap on the name opens the rest of its actions. */
@Composable
private fun ListRow(
    name: String, status: String?, art: String?,
    hp: Pair<Int, Int>? = null, hurt: Boolean = false, undead: Boolean = false, medal: String? = null,
    extra: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    val c = Tz.colors
    var open by remember(name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().tzPanel(c)) {
        Row(Modifier.fillMaxWidth().padding(Design.Space.XS.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hurt) Box(Modifier.width(3.dp).height(Design.Size.PORTRAIT_SMALL.dp).background(c.danger))
            Box(Modifier.size(Design.Size.PORTRAIT_SMALL.dp).clip(RoundedCornerShape(Design.Radius.M.dp)), contentAlignment = Alignment.Center) {
                if (art != null) ArtImage(art, Modifier.fillMaxSize(), dim = undead)
                else Box(Modifier.fillMaxSize().background(c.surfaceSunken), contentAlignment = Alignment.Center) {
                    Text(medal ?: name.take(1), style = Tz.type.heading, color = c.textMuted)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = Design.Space.S.dp).clickable { open = !open }) {
                Text(name, style = Tz.type.name, color = if (hurt) c.danger else c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.let { Text(it, style = Tz.type.small, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                hp?.let { (v, m) -> TzBar(v, m, c.health, Modifier.padding(top = 2.dp).height(Design.Size.BAR_THIN.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) { buttons() }
        }
        if (open) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Design.Space.S.dp)) { extra() }
    }
}

/** A heading inside the place list: «бьют вас · 3», with a note on the right; tappable when it folds. */
@Composable
private fun ListSection(title: String, note: String? = null, onClick: (() -> Unit)? = null) {
    val c = Tz.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = Tz.type.label, color = c.accent)
        note?.let { Text(it, style = Tz.type.small, color = c.textMuted) }
    }
}

/** While resting the row buttons are pale; the belt works. */
@Composable
private fun RestBanner(seconds: Int) {
    val c = Tz.colors
    Text(
        "Отдых $seconds с — удары и приёмы ждут. Зелье можно выпить сейчас.",
        style = Tz.type.small, color = c.onDanger,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Design.Radius.M.dp)).background(c.dangerFill)
            .border(Design.Size.BORDER.dp, c.danger, RoundedCornerShape(Design.Radius.M.dp)).padding(Design.Space.S.dp),
    )
}

@Composable
private fun Chip(text: String, danger: Boolean = false) {
    val c = Tz.colors
    Text(
        text, style = Tz.type.label, color = if (danger) c.onDanger else c.text,
        modifier = Modifier.padding(start = Design.Space.XS.dp).clip(RoundedCornerShape(Design.Radius.S.dp))
            .background(if (danger) c.danger else c.surfaceRaised).padding(horizontal = Design.Space.S.dp, vertical = 2.dp),
    )
}

/** Things that ask for attention: ghost, crime, poison, flag, a wounded spouse, a castle alarm, the horse, a thing's question. */
@Composable
private fun Notices(game: GameView, busy: Boolean, onResurrect: () -> Unit, more: MoreActions, social: SocialActions) {
    val c = Tz.colors
    val ch = game.character
    @Composable fun line(text: String, danger: Boolean = false, action: String? = null, onClick: () -> Unit = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = Tz.type.small, color = if (danger) c.danger else c.textMuted)
            action?.let { TextButton(onClick = onClick, enabled = !busy) { Text(it) } }
        }
    }
    if (ch.ghost) Column(Modifier.fillMaxWidth().tzPanel(c).padding(Design.Space.M.dp), verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        Text("Вы призрак", style = Tz.type.heading, color = c.title)
        Text(
            "Найдите лекаря или камень воскрешения (лекарь Джозеф — двор к северу от Переулка). Призрак не может драться и брать вещи." +
                (game.corpseAt?.let { " Ваши вещи ждут в трупе: $it." } ?: ""),
            style = Tz.type.small, color = c.text,
        )
        if (game.canResurrect) Button(onClick = onResurrect, enabled = !busy) { Text("Воскреснуть") }
    }
    ch.crime?.let { line("Вы $it — стража ищет вас ещё ${ch.crimeMinutes} мин", true) }
    if (ch.poisoned) line("Вы отравлены: здоровье убывает", true)
    if (ch.flag) line("У вас флаг лидерства", action = "бросить", onClick = more.dropFlag)
    game.stele?.let { line("${ch.spouse ?: "Супруг"} ранен(а): $it", true, if (!ch.ghost) "на помощь" else null, more.stele) }
    game.alarm?.let { line("В $it чужие!", true, if (!ch.ghost) "в замок" else null) { social.castleOp("tele", null) } }
    if (ch.mounted) line("Вы верхом", action = "спешиться", onClick = more.dismount)
    game.stance?.let { line("Стойка: $it") }
}

@Composable
private fun CastleBlock(cs: tz.shared.CastleView, busy: Boolean, social: SocialActions) {
    val c = Tz.colors
    Column(Modifier.fillMaxWidth().tzPanel(c).padding(Design.Space.S.dp), verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        Text(
            (cs.owner?.let { "Замок принадлежит клану $it" } ?: "Замок никому не принадлежит: первый член клана, вошедший в ворота, захватит его") +
                (if (cs.lockedMinutes > 0) " · ворота заперты ещё ${cs.lockedMinutes} мин." else "") + (if (cs.guest) " · вы гость" else ""),
            style = Tz.type.small, color = c.text,
        )
        if (cs.sign.isNotBlank()) Text("Надпись на воротах: ${cs.sign}", style = Tz.type.small, color = c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.S.dp)) {
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
}

@Composable
fun Journal(game: GameView, lines: Int) {
    val c = Tz.colors
    val rows = GameScene.journal(game, lines, hereOnly = true)
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(c.surfaceSunken).padding(Design.Space.S.dp)) {
        rows.forEach { (text, kind) ->
            val color = when (kind) {
                JournalKind.FIGHT -> c.logFight
                JournalKind.HURT -> c.logHurt
                JournalKind.SAY -> c.logSay
                JournalKind.GAIN -> c.logGain
                else -> c.logSystem
            }
            Text(text, style = Tz.type.log, color = color)
        }
    }
}

// ---- Персонаж --------------------------------------------------------------------------------

@Composable
private fun HeroTab(game: GameView, busy: Boolean, sub: Int, more: MoreActions, layout: LayoutActions, onSignOut: () -> Unit) {
    val c = Tz.colors
    val ch = game.character
    when (sub) {
        0 -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.rank} ${ch.title}".trim(), Modifier.weight(1f), style = Tz.type.heading, color = c.title)
                ActionButton("Аккаунт", !busy, layout.openAccount, icon = "settings")
            }
            Text("сила ${ch.str} · ловкость ${ch.dex} · интеллект ${ch.int}" + if (ch.skillPoints > 0) " · свободных очков ${ch.skillPoints}" else "", style = Tz.type.body, color = c.text)
            Text("опыт ${ch.exp}/${ch.expNext}", style = Tz.type.small, color = c.textMuted)
            TzBar(ch.exp, ch.expNext, c.exp)
            Text("удар ${ch.hit}% · урон ${ch.dmgMin}–${ch.dmgMax} · броня ${ch.armor} · уклон ${ch.dodge}", style = Tz.type.small, color = c.text)
            Text("парирование ${ch.parry} · уклон от магии ${ch.magicDodge} · защита от магии ${ch.magicParry} · сопр. магии ${ch.magicResist}", style = Tz.type.small, color = c.text)
            SectionTitle("В строке врага")
            Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton("удар", false, {}, icon = "attack")
                GameScene.slots(game).forEachIndexed { i, a ->
                    ActionButton(a?.name ?: "ячейка ${i + 1}", a != null && !busy, { layout.setSlot(i, "") }, art = a?.id?.let(GameScene::itemPath), badge = "${i + 1}")
                }
            }
            Text("Нажмите на ячейку, чтобы освободить её; приёмы и заклинания кладутся в ячейки на вкладке «Приёмы и магия».", style = Tz.type.small, color = c.textMuted)
            SectionTitle("Пояс")
            Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
                GameScene.belt(game).forEachIndexed { i, (id, item) ->
                    ActionButton(item?.name ?: "ячейка ${i + 1}", id.isNotEmpty() && !busy, { layout.setBelt(i, "") }, art = id.takeIf { it.isNotEmpty() }?.let(GameScene::itemPath), badge = item?.count?.toString())
                }
            }
            layout.openAdmin?.let { TextButton(onClick = it, enabled = !busy) { Text("Модерация") } }
            TextButton(onClick = onSignOut) { Text("Выйти") }
        }
        1 -> {
            if (ch.skillPoints > 0) Text("Свободных очков: ${ch.skillPoints}. Тратятся у учителей.", style = Tz.type.small, color = c.accent)
            GameScene.SKILL_GROUPS.forEach { (group, keys) ->
                SectionTitle(group)
                keys.forEach { k ->
                    val v = GameScene.skillLevel(ch, k)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { more.look("skill.$k") }, verticalAlignment = Alignment.CenterVertically) {
                        Text(Rules.skillTitle(k), Modifier.weight(1f), style = Tz.type.body, color = if (v > 0) c.text else c.textFaint)
                        if (k == "meditation" && v > 0 && !ch.ghost) TextButton(onClick = more.meditate, enabled = !busy) { Text("медитировать") }
                        Pips(v, Rules.SKILL_MAX)
                    }
                }
            }
        }
        else -> {
            if (game.abilities.isEmpty()) Text("Вы ещё не знаете ни приёмов, ни заклинаний. Их учат учителя и книги.", style = Tz.type.body, color = c.textMuted)
            val slots = game.slots
            game.abilities.forEach { a ->
                Row(Modifier.fillMaxWidth().tzPanel(c).padding(Design.Space.XS.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(GameScene.itemPath(a.id), Modifier.size(Design.Size.ITEM_ICON.dp).clip(RoundedCornerShape(Design.Radius.M.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = Design.Space.S.dp)) {
                        Text(a.name, style = Tz.type.name, color = c.title)
                        Text(
                            (if (a.manaCost > 0) "мана ${a.manaCost}" else "") + (if (a.readyIn > 0) " · через ${(a.readyIn + 59) / 60} мин" else "") + (if (a.later) " · позже" else ""),
                            style = Tz.type.small, color = c.textMuted,
                        )
                    }
                    val verb = when (a.kind) { "spell" -> "читать"; "stance" -> "встать"; else -> "ударить" }
                    TextButton(onClick = { more.useAbility(a) }, enabled = !busy && a.readyIn == 0L && !a.later && !ch.ghost) { Text(verb) }
                    TextButton(onClick = { more.look(a.id) }, enabled = !busy) { Text("?") }
                }
                if (a.kind != "stance") Row(Modifier.padding(start = Design.Space.L.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("в ячейку:", style = Tz.type.small, color = c.textMuted)
                    (0 until 3).forEach { i ->
                        val here = slots.getOrNull(i) == a.id
                        TextButton(onClick = { layout.setSlot(i, if (here) "" else a.id) }, enabled = !busy) { Text(if (here) "${i + 1} ✓" else "${i + 1}") }
                    }
                }
            }
        }
    }
}

/** A skill level as five marks. */
@Composable
fun Pips(value: Int, max: Int) {
    val c = Tz.colors
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(max) { i ->
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (i < value) c.accent else c.barTrack)
                .border(Design.Size.BORDER.dp, c.borderSoft, RoundedCornerShape(2.dp)))
        }
    }
}

/** The map of this part of the world: pinch to zoom, drag to move; starts on you. */
@Composable
fun ZoomMap(m: MapView, here: String, flagAt: String?) {
    val c = Tz.colors
    val me = Rules.mapPoint(here)
    val region = me?.third ?: 0
    fun regionOf(x: Int, y: Int) = if (y > 1101) 2 else if (x > 1650) 1 else 0
    val pts = remember(m, region) { m.points.filter { regionOf(it.mapX, it.mapY) == region } }
    if (pts.isEmpty()) { Text("Нет данных", style = Tz.type.small); return }
    val minX = pts.minOf { it.mapX }; val maxX = pts.maxOf { it.mapX }
    val minY = pts.minOf { it.mapY }; val maxY = pts.maxOf { it.mapY }
    var scale by remember(region) { mutableStateOf(3f) }
    var offset by remember(region) { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    val castles = listOf("c.1.gate", "c.2.gate", "c.3.gate", "c.4.gate").mapNotNull { Rules.mapPoint(it) }.filter { it.third == region }
    val flag = flagAt?.let { Rules.mapPoint(it) }?.takeIf { it.third == region }
    Text(when (region) { 1 -> "Ансалон"; 2 -> "Волчий остров"; else -> "Основная территория" }, style = Tz.type.heading, color = c.title)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(Design.Radius.L.dp)).background(c.surfaceSunken)
            .border(Design.Size.BORDER.dp, c.border, RoundedCornerShape(Design.Radius.L.dp))
            .pointerInput(region) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 12f)
                    offset = (if (offset == androidx.compose.ui.geometry.Offset.Unspecified) androidx.compose.ui.geometry.Offset.Zero else offset) + pan
                }
            },
    ) {
        val base = minOf(size.width / (maxX - minX + 1).coerceAtLeast(1), size.height / (maxY - minY + 1).coerceAtLeast(1))
        val k = base * scale
        // First frame: centre on the character.
        if (offset == androidx.compose.ui.geometry.Offset.Unspecified) {
            val cx = ((me?.first ?: (minX + maxX) / 2) - minX) * k; val cy = ((me?.second ?: (minY + maxY) / 2) - minY) * k
            offset = androidx.compose.ui.geometry.Offset(size.width / 2 - cx, size.height / 2 - cy)
        }
        fun at(x: Int, y: Int) = androidx.compose.ui.geometry.Offset((x - minX) * k + offset.x, (y - minY) * k + offset.y)
        val d = (k * 0.8f).coerceIn(2f, 14f)
        for (p in pts) drawCircle(if (p.zone == 1) c.link else c.textFaint, radius = d / 2, center = at(p.mapX, p.mapY))
        for (cs in castles) drawCircle(c.danger, radius = d * 1.4f, center = at(cs.first, cs.second))
        flag?.let { drawCircle(c.title, radius = d * 1.4f, center = at(it.first, it.second)) }
        me?.let {
            drawCircle(c.accent, radius = d * 1.8f, center = at(it.first, it.second))
            drawCircle(c.background, radius = d * 0.8f, center = at(it.first, it.second))
        }
    }
    Text("золото — вы, красное — замки, светлое — флаг лидерства, голубые точки — охраняемые улицы. Два пальца — масштаб.", style = Tz.type.small, color = c.textMuted)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Tz.type.label, color = Tz.colors.accent, modifier = Modifier.padding(top = Design.Space.S.dp))
}

// ---- Сумка -----------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BagTab(game: GameView, busy: Boolean, onDrop: (InventoryItemView) -> Unit, onToggleEquip: (InventoryItemView) -> Unit, more: MoreActions, layout: LayoutActions) {
    val c = Tz.colors
    val ch = game.character
    val money = game.inventory.firstOrNull { it.id == Rules.MONEY }?.count ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        TzIcon("gold", Modifier.size(16.dp), c.accent)
        Text(" $money", style = Tz.type.number, color = c.text)
    }
    if (game.inventory.isEmpty()) Text("пусто", style = Tz.type.body, color = c.textMuted)
    val (worn, carried) = game.inventory.filter { it.id != Rules.MONEY }.partition { it.equipped }
    SectionTitle("Экипировка")
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp), verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        GameScene.equipment(game).forEach { (slot, item) ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(Design.Size.ITEM_ICON.dp + 8.dp).clip(RoundedCornerShape(Design.Radius.M.dp))
                        .border(Design.Size.BORDER.dp, if (item != null) c.border else c.borderSoft, RoundedCornerShape(Design.Radius.M.dp))
                        .clickable(enabled = item != null && !busy) { item?.let { more.look(it.id) } },
                ) { if (item != null) ArtImage(GameScene.itemPath(item.id), Modifier.fillMaxSize()) }
                Text(item?.name ?: slot, style = Tz.type.small, color = if (item != null) c.text else c.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (worn.isNotEmpty()) SectionTitle("Надето")
    (worn + carried).forEachIndexed { i, item ->
        if (i == worn.size && carried.isNotEmpty()) SectionTitle("В сумке")
        val onBelt = item.id in game.belt
        var open by remember(item.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().tzPanel(c)) {
            Row(Modifier.fillMaxWidth().padding(Design.Space.XS.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(GameScene.itemPath(item.id), Modifier.size(Design.Size.ITEM_ICON.dp).clip(RoundedCornerShape(Design.Radius.M.dp)))
                Column(Modifier.weight(1f).padding(horizontal = Design.Space.S.dp).clickable { open = !open }) {
                    Text("${item.name}${if (item.count > 1) " ×${item.count}" else ""}${if (item.equipped) " (надето)" else ""}", style = Tz.type.name, color = c.title)
                    if (onBelt) Text("на поясе", style = Tz.type.small, color = c.accent)
                }
                if (item.equippable) TextButton(onClick = { onToggleEquip(item) }, enabled = !busy) { Text(if (item.equipped) "снять" else "надеть") }
                if (item.usable && !ch.ghost) TextButton(onClick = { more.use(item) }, enabled = !busy) { Text("исп.") }
            }
            if (open) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Design.Space.S.dp)) {
                TextButton(onClick = { more.look(item.id) }, enabled = !busy) { Text("осмотреть") }
                if (item.usable && item.id.startsWith("i.f.") && !onBelt) {
                    val free = game.belt.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 } ?: (GameScene.belt(game).size - 1).coerceAtLeast(0)
                    TextButton(onClick = { layout.setBelt(free, item.id) }, enabled = !busy) { Text("на пояс") }
                }
                if (item.count > 1) TextButton(onClick = { more.dropOne(item) }, enabled = !busy) { Text("бросить одну") }
                TextButton(onClick = { onDrop(item) }, enabled = !busy) { Text("бросить") }
            }
        }
    }
}

// ---- Общение ---------------------------------------------------------------------------------

@Composable
private fun SocialTab(
    game: GameView, busy: Boolean, sub: Int, social: SocialActions, more: MoreActions, layout: LayoutActions,
    mail: MessagesView?, clanInfo: ClanView?, worldInfo: WorldView?,
) {
    val c = Tz.colors
    when (sub) {
        0 -> {
            if (game.people.isEmpty()) Text("Рядом никого нет.", style = Tz.type.body, color = c.textMuted)
            val thief = (game.character.skills["steal"] ?: 0) > 0 || (game.character.skills["steallook"] ?: 0) > 0
            game.people.forEach { PersonRow(it, game, busy, thief, more, social) }
            val said = GameScene.journal(game, 30).filter { it.second == JournalKind.SAY }.takeLast(8)
            Column(Modifier.fillMaxWidth().background(c.surfaceSunken).padding(Design.Space.S.dp)) {
                if (said.isEmpty()) Text("Здесь пока молчат.", style = Tz.type.log, color = c.textFaint)
                said.forEach { Text(it.first, style = Tz.type.log, color = c.logSay) }
            }
            var speech by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(speech, { speech = it }, label = { Text("Сказать") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { social.say(speech, false); speech = "" }, enabled = !busy && speech.isNotBlank()) { Text("всем") }
                if (game.clan != null) TextButton(onClick = { social.say(speech, true); speech = "" }, enabled = !busy && speech.isNotBlank()) { Text("клану") }
            }
        }
        1 -> mail?.let { MailPanel(it, busy, social) } ?: OutlinedButton(onClick = social.openMail, enabled = !busy) { Text(if (game.unread > 0) "Почта (${game.unread})" else "Открыть почту") }
        2 -> clanInfo?.let { ClanPanel(it, busy, social) } ?: OutlinedButton(onClick = social.openClan, enabled = !busy) {
            Text((game.clan?.let { "Клан $it" } ?: "Клан") + if (game.clanInvites.isNotEmpty()) " (приглашение)" else "")
        }
        3 -> worldInfo?.let { w ->
            Text("Сейчас в игре ${w.online.size}", style = Tz.type.label, color = c.accent)
            w.online.forEach { o ->
                Text("${o.name} [${o.level}]" + (o.clan?.let { " *$it*" } ?: "") + (o.crime?.let { " $it" } ?: ""), style = Tz.type.body, color = if (o.crime != null) c.danger else c.text)
            }
            TextButton(onClick = more.openWorld, enabled = !busy) { Text("обновить") }
        } ?: OutlinedButton(onClick = more.openWorld, enabled = !busy) { Text("Кто в игре") }
        else -> {
            Text("Форум игры: торговля, кланы, вопросы новичков, новости.", style = Tz.type.body, color = c.text)
            Button(onClick = layout.openForum, enabled = !busy) { Text("Открыть форум") }
        }
    }
}

// ---- Мир -------------------------------------------------------------------------------------

@Composable
private fun WorldTab(
    game: GameView, busy: Boolean, sub: Int, social: SocialActions, more: MoreActions, layout: LayoutActions,
    worldInfo: WorldView?, mapView: MapView?, chronicle: tz.shared.ChronicleView?, news: tz.shared.ForumView?,
) {
    val c = Tz.colors
    when (sub) {
        0 -> mapView?.let { ZoomMap(it, game.character.location, worldInfo?.flagLocationId) }
            ?: OutlinedButton(onClick = more.openMap, enabled = !busy) { Text("Показать карту") }
        1 -> {
            game.castle?.let { CastleBlock(it, busy, social) }
            worldInfo?.let { w ->
                w.castles.forEach { cs ->
                    Row(Modifier.fillMaxWidth().tzPanel(c).padding(Design.Space.S.dp)) {
                        Text(cs.name, Modifier.weight(1f), style = Tz.type.name, color = c.title)
                        Text(cs.owner ?: "ничей", style = Tz.type.small, color = if (cs.owner == game.clan && cs.owner != null) c.accent else c.textMuted)
                    }
                }
                Text("Флаг лидерства: " + (w.flagHolder?.let { "у $it (${w.flagLocation ?: "?"})" } ?: "лежит: ${w.flagLocation ?: "неизвестно где"}"), style = Tz.type.small, color = c.text)
                SectionTitle("Кланы")
                if (w.clans.isEmpty()) Text("пока нет", style = Tz.type.small, color = c.textMuted)
                w.clans.forEach { Text("${it.name} — ${it.members}", style = Tz.type.small, color = c.text) }
            } ?: OutlinedButton(onClick = more.openWorld, enabled = !busy) { Text("Замки и кланы") }
        }
        else -> {
            SectionTitle("Новости")
            val topics = news?.topics.orEmpty().take(5)
            if (news == null) Text("…", style = Tz.type.small, color = c.textMuted)
            else if (topics.isEmpty()) Text("Новостей пока нет.", style = Tz.type.small, color = c.textMuted)
            topics.forEach { t ->
                Column(Modifier.fillMaxWidth().tzPanel(c).clickable(enabled = !busy) { layout.openTopic(t) }.padding(Design.Space.S.dp)) {
                    Text(t.title, style = Tz.type.name, color = c.title)
                    Text("${date(t.updated)} · ${t.author}", style = Tz.type.small, color = c.textMuted)
                }
            }
            TextButton(onClick = layout.openNews, enabled = !busy) { Text("Все новости") }
            SectionTitle("Летопись мира · 7 дней")
            val entries = chronicle?.entries.orEmpty()
            if (chronicle != null && entries.isEmpty()) Text("Пока тихо: ни захватов, ни свадеб.", style = Tz.type.small, color = c.textMuted)
            var day = ""
            entries.forEach { e ->
                val d = date(e.at).substringBefore(' ')
                if (d != day) { day = d; Text(d, style = Tz.type.label, color = c.textMuted, modifier = Modifier.padding(top = Design.Space.XS.dp)) }
                Row {
                    Text(date(e.at).substringAfter(' '), style = Tz.type.number, color = c.textFaint, modifier = Modifier.width(48.dp))
                    Text(e.text, style = Tz.type.log, color = if (e.clan) c.accent else c.text)
                }
            }
            TextButton(onClick = layout.openEvents, enabled = !busy) { Text("обновить") }
            TextButton(onClick = layout.openPages, enabled = !busy) { Text("Об игре и правила") }
        }
    }
}

// ---- шторки ----------------------------------------------------------------------------------

/** Dialog, trade, bank, crafting, exchange, a look, a peek and target choice: over the list, the belt, exits and tabs stay. */
@Composable
private fun Sheets(
    game: GameView, busy: Boolean, onAnswer: (DialogOption) -> Unit, onCloseDialog: () -> Unit,
    pending: InventoryItemView?, pendingAbility: AbilityView?, more: MoreActions, social: SocialActions, modifier: Modifier,
) {
    val c = Tz.colors
    val any = game.dialog != null || game.shop != null || game.bank != null || game.craft != null || game.exchange != null ||
        game.look != null || game.peek != null || game.choice != null || pending != null || pendingAbility != null
    if (!any) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Surface(
            Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.85f),
            color = c.surface, shape = RoundedCornerShape(topStart = Design.Radius.L.dp, topEnd = Design.Radius.L.dp), shadowElevation = 8.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(Design.Space.M.dp), verticalArrangement = Arrangement.spacedBy(Design.Space.S.dp)) {
                val small = MaterialTheme.typography.bodyMedium
                game.dialog?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val portrait = game.location.npcs.firstOrNull { it.id == d.npc }?.art
                        if (portrait != null) ArtImage(GameScene.artPath(portrait), Modifier.size(Design.Size.PORTRAIT_SMALL.dp).clip(RoundedCornerShape(Design.Radius.M.dp)))
                        Text(d.npcName, Modifier.padding(start = Design.Space.S.dp), style = Tz.type.heading, color = c.title)
                    }
                    Text(d.text, style = Tz.type.body, color = c.text)
                    if (d.inputTopic != null) {
                        var typed by remember(d.text) { mutableStateOf("") }
                        OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { social.answerText(typed) }, enabled = !busy && typed.isNotBlank()) { Text("ответить") }
                    }
                    d.options.forEach { o -> TextButton(onClick = { onAnswer(o) }, enabled = !busy) { Text("› " + o.label) } }
                    if (game.shop == null && game.bank == null && game.craft == null)
                        TextButton(onClick = onCloseDialog) { Text(if (d.options.isEmpty()) "[Конец диалога]" else "закончить разговор") }
                }
                game.shop?.let { shop ->
                    Panel(shop.npcName + if (shop.mode == "sell") " покупает" else " продаёт", onCloseDialog) {
                        shop.message?.let { Text(it, style = small) }
                        shop.items.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ArtImage(GameScene.itemPath(item.id), Modifier.size(32.dp))
                                Text(" ${item.name}${if (item.count > 1) " (${item.count})" else ""} — ${item.price} ${shop.currency}", Modifier.weight(1f), style = small)
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
                        craft.options.forEach { o -> TextButton(onClick = { more.craft(o) }, enabled = !busy) { Text("${o.name} — ${o.chance}% (${o.needs})") } }
                    }
                }
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
                game.choice?.let { ch ->
                    Panel(ch.title, social.closeChoice) {
                        ch.options.forEach { o -> TextButton(onClick = { social.choose(o) }, enabled = !busy) { Text(o.label) } }
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
                game.look?.let { l ->
                    Panel(l.title, more.closeLook) {
                        Text(l.text, style = small)
                        l.page?.let { pg -> TextButton(onClick = { more.openSite(pg) }, enabled = !busy) { Text(if (pg == "news") "Все новости" else "Выбрать книгу") } }
                    }
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
            }
        }
    }
}
