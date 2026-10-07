// The place as a scene (prototype, owner 06.10): the picture of the place, monsters and people
// standing on it as figures with health, power and your chance; a tap strikes; damage flies up
// as numbers, figures shake and flash, the killed fade out; things and corpses lie on the ground
// as icons. Landscape, like the owner's reference screenshot (07.10): the picture fills the screen,
// the hero in the upper left, the minimap in the upper right, the joystick of exits in the lower
// left (Movement.cs). Text is kept to names and short notes.
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.UI;

namespace Amulet
{
    public class GameScreen : MonoBehaviour
    {
        /// <summary>Set by the server's change signal: refresh at the next frame.</summary>
        public bool Dirty;

        App app;
        RectTransform screen, scene, figures, ground, toasts, doors, others, hurtFlash;
        Image background, heroIcon;
        Text placeName, heroName, heroPower, heroLetter;
        Stick stick;
        Minimap minimap;
        UI.Bar hp, mana, exp;
        CanvasGroup sceneGroup, ghostGroup;
        Button resurrect;
        Paperdoll doll;
        GameView view;
        float fetchedAt, nextPoll, nextStep;
        bool busy;
        readonly Dictionary<string, Figure> shown = new Dictionary<string, Figure>();

        class Figure
        {
            public RectTransform Root;
            public Image Picture, Ring, Badge, Blow;
            public Text Name, Chance;
            public UI.Bar Health;
            public NpcView Npc;
            public Vector2 Target;
        }

        public void Init(App app, RectTransform screen)
        {
            this.app = app;
            this.screen = screen;
            Build();
            Refresh();
        }

        // ---- layout --------------------------------------------------------------------------------

        void Build()
        {
            // the scene: the picture of the place over the whole screen (it is 16:9 like the phone)
            scene = UI.Node("scene", screen).Place(0, 0, 1, 1);
            scene.gameObject.AddComponent<RectMask2D>();
            sceneGroup = scene.gameObject.AddComponent<CanvasGroup>();
            background = UI.Picture(scene, null, "place");
            background.preserveAspect = false;
            background.rectTransform.Place(0, 0, 1, 1);
            var fit = background.gameObject.AddComponent<AspectRatioFitter>();
            fit.aspectMode = AspectRatioFitter.AspectMode.EnvelopeParent;
            fit.aspectRatio = 16f / 9f;
            var top = UI.Panel(scene, new Color(0, 0, 0, 0.4f), "shade top", false);
            top.rectTransform.Place(0, 0.8f, 1, 1);
            top.raycastTarget = false;
            var low = UI.Panel(scene, new Color(0, 0, 0, 0.35f), "shade bottom", false);
            low.rectTransform.Place(0, 0, 1, 0.22f);
            low.raycastTarget = false;
            placeName = UI.Label(scene, "", 44, Palette.Title, TextAnchor.UpperCenter, Art.TitleFont);
            placeName.rectTransform.Place(0.3f, 1, 0.7f, 1, 0, -80, 0, 22);
            others = UI.Node("others", scene).Place(0.3f, 1, 0.7f, 1, 0, -126, 0, 80);
            figures = UI.Node("figures", scene).Place(0.18f, 0, 0.8f, 1);
            ground = UI.Node("ground", scene).Place(0, 0, 1, 0, 520, 24, 230, -144);
            var row = ground.gameObject.AddComponent<HorizontalLayoutGroup>();
            row.spacing = 16; row.childAlignment = TextAnchor.LowerCenter;
            row.childControlWidth = row.childControlHeight = false; row.childForceExpandWidth = row.childForceExpandHeight = false;

            // the ghost veil over the scene
            var veil = UI.Panel(scene, new Color(0.55f, 0.6f, 0.7f, 0.35f), "ghost", false);
            veil.rectTransform.Place(0, 0, 1, 1);
            ghostGroup = veil.gameObject.AddComponent<CanvasGroup>();
            UI.Label(veil.transform, "Вы призрак", 72, Palette.Text, TextAnchor.MiddleCenter, Art.TitleFont).rectTransform.At(0.5f, 0.6f, 900, 120);
            resurrect = UI.Button(veil.transform, "Воскреснуть", () => Act(() => app.Api.Resurrect()));
            resurrect.GetComponent<RectTransform>().At(0.5f, 0.45f, 600, 130);
            ghostGroup.alpha = 0; ghostGroup.blocksRaycasts = false;

            // the hero in the upper left: health line, name, level and power; experience along the left edge
            hp = UI.MakeBar(screen, Palette.Health, true, 24);
            ((RectTransform)hp.Fill.transform.parent).Place(0, 1, 0, 1, 50, -58, -600, 26);
            mana = UI.MakeBar(screen, Palette.Mana, false);
            ((RectTransform)mana.Fill.transform.parent).Place(0, 1, 0, 1, 50, -76, -600, 62);
            heroName = UI.Label(screen, "", 46, Palette.Title, TextAnchor.UpperLeft, Art.TitleFont);
            heroName.rectTransform.Place(0, 1, 0, 1, 50, -146, -700, 86);
            heroPower = UI.Label(screen, "", 34, Palette.Accent, TextAnchor.UpperLeft, Art.Bold);
            heroPower.rectTransform.Place(0, 1, 0, 1, 50, -196, -700, 146);
            exp = UI.MakeBar(screen, Palette.Exp, false);
            exp.Fill.fillMethod = Image.FillMethod.Vertical;
            ((RectTransform)exp.Fill.transform.parent).Place(0, 0, 0, 1, 14, 120, -28, 30);

            // notes of the fight under the hero
            toasts = UI.Node("toasts", screen).Place(0, 1, 0, 1, 50, -380, -720, 216);
            var col = toasts.gameObject.AddComponent<VerticalLayoutGroup>();
            col.childAlignment = TextAnchor.UpperLeft; col.childControlHeight = true; col.childControlWidth = true; col.childForceExpandHeight = false;

            // the minimap in the upper right, the hero's button under it
            minimap = new Minimap(screen);
            var hero = UI.Panel(screen, new Color(0.12f, 0.09f, 0.06f, 0.85f), "hero");
            hero.sprite = Art.Circle; hero.type = Image.Type.Simple;
            hero.rectTransform.Place(1, 1, 1, 1, -200, -630, 50, 480);
            var heroRing = UI.Panel(hero.transform, new Color(Palette.Accent.r, Palette.Accent.g, Palette.Accent.b, 0.6f), "ring");
            heroRing.sprite = Art.Circle; heroRing.type = Image.Type.Simple; heroRing.raycastTarget = false;
            heroRing.rectTransform.Place(0, 0, 1, 1, -5, -5, -5, -5);
            heroRing.transform.SetAsFirstSibling();
            heroIcon = UI.Picture(hero.transform, null, "icon");
            heroIcon.rectTransform.Place(0, 0, 1, 1, 26, 26, 26, 26);
            heroLetter = UI.Label(hero.transform, "", 64, Palette.Title, TextAnchor.MiddleCenter, Art.TitleFont);
            heroLetter.rectTransform.Place(0, 0, 1, 1);
            UI.Label(hero.transform, "Герой", 28, Palette.Text, TextAnchor.UpperCenter, Art.Bold).rectTransform.Place(0, 0, 1, 0, -30, -44, -30, 2);
            hero.Tap(OpenDoll);

            // the joystick of exits in the lower left, exits without a direction as doors above it
            stick = Stick.Make(screen);
            stick.Release = Go;
            doors = UI.Node("doors", screen).Place(0, 0, 0, 0, 50, 450, -470, -700);
            var stack = doors.gameObject.AddComponent<VerticalLayoutGroup>();
            stack.spacing = 12; stack.childAlignment = TextAnchor.LowerLeft;
            stack.childControlHeight = false; stack.childControlWidth = true; stack.childForceExpandHeight = false;

            hurtFlash = UI.Panel(screen, new Color(0.8f, 0, 0, 0), "hurt", false).rectTransform.Place(0, 0, 1, 1);
            hurtFlash.GetComponent<Image>().raycastTarget = false;
            LoadMap();
        }

        async void LoadMap()
        {
            try
            {
                minimap.SetPoints(await app.Api.Map());
                if (view != null) minimap.Show(view.location, Go);
            }
            catch (System.Exception) { }    // the minimap stays empty; the joystick still works
        }

        // ---- data ----------------------------------------------------------------------------------

        void Update()
        {
            if (busy) return;
            if (Dirty || Time.time > nextPoll) { Dirty = false; Refresh(); }
            if (view == null) return;
            // Holding the joystick on an exit walks on, place after place, until a fight starts.
            if (stick.Held && stick.Chosen != null && Time.time - stick.ChosenAt > 0.45f && Time.time > nextStep
                && !view.location.npcs.Any(n => n.fightingYou))
            {
                stick.Walked = true;
                nextStep = Time.time + 0.7f;
                Go(stick.Chosen);
                return;
            }
            var since = (Time.time - fetchedAt) * 1000;
            foreach (var f in shown.Values)
            {
                var left = f.Npc.nextBlowMs.HasValue ? Mathf.Max(0, f.Npc.nextBlowMs.Value - since) : 0;
                f.Blow.fillAmount = f.Npc.fightingYou ? Mathf.Clamp01(left / 2000f) : 0;
            }
        }

        async void Refresh()
        {
            busy = true;
            try { Apply(await app.Api.Game()); }
            catch (ApiError e) when (e.Code == "unauthorized") { app.SignOut(); }
            catch (ApiError e) { Toast(e.Message, Palette.Danger); }
            catch (System.Exception e) { Toast("Нет связи: " + e.Message, Palette.Danger); }
            finally { busy = false; nextPoll = Time.time + (view != null && view.location.npcs.Any(n => n.fightingYou) ? 1.5f : 6f); }
        }

        async void Act(System.Func<Task<GameView>> call)
        {
            if (busy) return;
            busy = true;
            try { Apply(await call()); }
            catch (ApiError e) { Toast(e.Message, Palette.Danger); }
            catch (System.Exception e) { Toast("Нет связи: " + e.Message, Palette.Danger); }
            finally { busy = false; }
        }

        void Apply(GameView next)
        {
            var before = view;
            view = next;
            fetchedAt = Time.time;
            var moved = before == null || before.location.id != next.location.id;
            if (moved) { foreach (var f in shown.Values) Destroy(f.Root.gameObject); shown.Clear(); StartCoroutine(Fx.Fade(sceneGroup, 0, 1, 0.35f)); }

            var ch = next.character;
            heroName.text = ch.name;
            heroPower.text = $"{ch.level} уровень · мощь {ch.power}";
            var worn = next.inventory.FirstOrDefault(i => i.equipped && i.id.StartsWith("i.w.")) ?? next.inventory.FirstOrDefault(i => i.equipped && i.id.StartsWith("i.a.b."));
            heroIcon.sprite = worn != null ? Art.Item(worn.id) : null;
            heroIcon.color = heroIcon.sprite != null ? Color.white : new Color(0, 0, 0, 0);
            heroLetter.text = heroIcon.sprite != null ? "" : ch.name.Substring(0, 1);
            hp.Set(ch.hp, ch.hpMax);
            mana.Set(ch.mana, ch.manaMax);
            exp.Set(ch.exp, ch.expNext);
            if (before != null && !moved)
            {
                if (ch.hp < before.character.hp)
                {
                    StartCoroutine(Fx.FloatText(screen, "−" + (before.character.hp - ch.hp), Palette.Danger, new Vector2(-screen.rect.width / 2 + 420, screen.rect.height / 2 - 120)));
                    StartCoroutine(Fx.Flash(hurtFlash.GetComponent<Image>(), new Color(0.8f, 0, 0, 0.35f), 0.4f));
                }
                if (ch.exp > before.character.exp && ch.level == before.character.level)
                    StartCoroutine(Fx.FloatText(screen, $"+{ch.exp - before.character.exp} опыта", Palette.Exp, new Vector2(-screen.rect.width / 2 + 420, screen.rect.height / 2 - 260), 44));
                if (ch.level > before.character.level)
                    StartCoroutine(Fx.FloatText(screen, $"Уровень {ch.level}!", Palette.Title, Vector2.zero, 96));
            }
            ghostGroup.alpha = ch.ghost ? 1 : 0;
            ghostGroup.blocksRaycasts = ch.ghost;
            resurrect.gameObject.SetActive(next.canResurrect);

            var loc = next.location;
            placeName.text = loc.name;
            var pic = Art.Get(loc.art);
            background.sprite = pic;
            background.color = pic != null ? Color.white : Palette.Raised;
            UI.Clear(others);
            if (loc.players.Count > 0)
                UI.Label(others, "рядом: " + string.Join(", ", loc.players.Take(4)) + (loc.players.Count > 4 ? $" и ещё {loc.players.Count - 4}" : ""), 30, Palette.Text, TextAnchor.UpperLeft).rectTransform.Place(0, 0, 1, 1);

            ShowFigures(before, moved);
            ShowGround();
            ShowExits();
            if (moved) minimap.Show(loc, Go);
            ShowJournal(before, moved);
            if (doll != null) doll.Show(next);
        }

        // ---- figures -------------------------------------------------------------------------------

        void ShowFigures(GameView before, bool moved)
        {
            var npcs = view.location.npcs.Where(n => !n.mine).OrderByDescending(n => n.fightingYou).ThenByDescending(n => n.hostile).Take(6).ToList();
            var gone = shown.Keys.Where(k => npcs.All(n => n.id != k)).ToList();
            foreach (var id in gone)
            {
                var f = shown[id];
                shown.Remove(id);
                var group = Group(f.Root);
                f.Root.GetComponent<Button>().interactable = false;
                StartCoroutine(Fx.Fade(group, 1, 0, 0.8f, true));
            }
            var front = Mathf.Min(npcs.Count, 3);
            for (int i = 0; i < npcs.Count; i++)
            {
                var n = npcs[i];
                bool back = i >= 3;
                int inRow = back ? npcs.Count - 3 : front, k = back ? i - 3 : i;
                float x = (k + 1f) / (inRow + 1f), y = back ? 0.5f : 0.3f, size = back ? 240 : 300;
                if (!shown.TryGetValue(n.id, out var f))
                {
                    f = MakeFigure(n, size);
                    shown[n.id] = f;
                    f.Root.At(x, y, size, size * 1.3f + 120);
                    if (!moved) StartCoroutine(Fx.Fade(Group(f.Root), 0, 1, 0.4f));
                }
                else
                {
                    var old = f.Npc;
                    if (n.hp < old.hp)
                    {
                        StartCoroutine(Fx.FloatText(f.Root, "−" + (old.hp - n.hp), Palette.Danger, new Vector2(0, 40)));
                        StartCoroutine(Fx.Shake(f.Picture.rectTransform));
                        StartCoroutine(Fx.Flash(f.Picture, new Color(1, 0.35f, 0.3f), 0.3f));
                    }
                    f.Root.sizeDelta = new Vector2(size, size * 1.3f + 120);
                    StartCoroutine(MoveTo(f.Root, new Vector2(x, y)));
                }
                f.Npc = n;
                f.Name.text = n.name + (n.level > 0 ? $" · {n.level}" : "");
                f.Name.color = n.fightingYou ? Palette.Danger : n.hostile ? Palette.Title : Palette.Text;
                f.Health.Set(n.hp, n.hpMax);
                ((RectTransform)f.Health.Fill.transform.parent).gameObject.SetActive(n.attackable && n.hpMax > 0);
                f.Ring.color = n.fightingYou ? new Color(0.9f, 0.2f, 0.15f, 0.55f) : new Color(0, 0, 0, 0);
                bool odds = n.winChance.HasValue && n.attackable && !view.character.ghost;
                f.Badge.gameObject.SetActive(odds);
                if (odds) { f.Badge.color = Palette.Odds(n.winChance); f.Chance.text = n.winChance + "%"; }
                f.Picture.color = n.undead ? new Color(0.7f, 0.85f, 0.7f) : Color.white;
            }
        }

        static CanvasGroup Group(Component c) { var g = c.GetComponent<CanvasGroup>(); return g != null ? g : c.gameObject.AddComponent<CanvasGroup>(); }

        IEnumerator MoveTo(RectTransform rt, Vector2 anchor)
        {
            var from = rt.anchorMin;
            for (float t = 0; t < 0.25f && rt != null; t += Time.deltaTime)
            {
                rt.anchorMin = rt.anchorMax = Vector2.Lerp(from, anchor, t / 0.25f);
                yield return null;
            }
            if (rt != null) rt.anchorMin = rt.anchorMax = anchor;
        }

        Figure MakeFigure(NpcView n, float size)
        {
            var f = new Figure { Npc = n };
            f.Root = UI.Node("figure " + n.id, figures);
            var hit = f.Root.gameObject.AddComponent<Image>();
            hit.color = new Color(0, 0, 0, 0);
            hit.Tap(() => OnFigure(f));
            var shadow = UI.Panel(f.Root, new Color(0, 0, 0, 0.55f), "shadow");
            shadow.sprite = Art.Circle;
            shadow.rectTransform.Place(0.12f, 0, 0.88f, 0, 0, 96, 0, -150);
            shadow.raycastTarget = false;
            f.Ring = UI.Panel(f.Root, new Color(0, 0, 0, 0), "ring");
            f.Ring.sprite = Art.Circle;
            f.Ring.rectTransform.Place(0, 0, 1, 1, -10, 110, -10, -10);
            f.Ring.raycastTarget = false;
            var sprite = Art.Get(n.art);
            f.Picture = UI.Picture(f.Root, sprite ?? Art.Circle, "picture");
            if (sprite == null) f.Picture.color = Palette.Raised;
            f.Picture.rectTransform.Place(0, 0, 1, 1, 0, 120, 0, 0);
            StartCoroutine(Fx.Breathe(f.Picture.rectTransform, Random.value * 6));
            if (sprite == null) UI.Label(f.Picture.transform, n.name.Substring(0, 1), 120, Palette.Muted, TextAnchor.MiddleCenter, Art.TitleFont).rectTransform.Place(0, 0, 1, 1);
            f.Name = UI.Label(f.Root, n.name, 32, Palette.Text, TextAnchor.UpperCenter, Art.Bold);
            f.Name.rectTransform.Place(0, 0, 1, 0, -40, 48, -40, -112);
            f.Health = UI.MakeBar(f.Root, Palette.Health, false);
            ((RectTransform)f.Health.Fill.transform.parent).Place(0.1f, 0, 0.9f, 0, 0, 22, 0, -40);
            f.Badge = UI.Panel(f.Root, Palette.Accent, "odds");
            f.Badge.sprite = Art.Circle;
            f.Badge.rectTransform.Place(1, 1, 1, 1, -96, -96, 0, 0);
            f.Badge.raycastTarget = false;
            f.Chance = UI.Label(f.Badge.transform, "", 30, Palette.Background, TextAnchor.MiddleCenter, Art.Bold);
            f.Chance.rectTransform.Place(0, 0, 1, 1);
            f.Chance.GetComponent<Shadow>().enabled = false;
            f.Blow = UI.Panel(f.Badge.transform, new Color(0.9f, 0.15f, 0.1f, 0.85f), "blow");
            f.Blow.sprite = Art.Circle;
            f.Blow.type = Image.Type.Filled;
            f.Blow.fillMethod = Image.FillMethod.Radial360;
            f.Blow.rectTransform.Place(0, 0, 1, 1, -10, -10, -10, -10);
            f.Blow.transform.SetAsFirstSibling();
            f.Blow.raycastTarget = false;
            return f;
        }

        void OnFigure(Figure f)
        {
            var n = f.Npc;
            if (view.character.ghost) { Toast("Призрак не может сражаться", Palette.Muted); return; }
            if (n.attackable && (n.fightingYou || n.attacking != null || !n.canTalk))
            {
                StartCoroutine(Lunge(f.Picture.rectTransform));
                Act(() => app.Api.Attack(n.id));
            }
            else if (n.canTalk) Toast($"{n.name}: разговоры пока в обычном приложении", Palette.Muted);
            else Toast(n.name, Palette.Muted);
        }

        IEnumerator Lunge(RectTransform rt)
        {
            for (float t = 0; t < 0.18f && rt != null; t += Time.deltaTime)
            {
                float s = 1 + 0.12f * Mathf.Sin(t / 0.18f * Mathf.PI);
                rt.localScale = new Vector3(s, s, 1);
                yield return null;
            }
        }

        // ---- the ground ----------------------------------------------------------------------------

        void ShowGround()
        {
            UI.Clear(ground);
            foreach (var c in view.location.corpses)
            {
                var icon = UI.Panel(ground, new Color(0.2f, 0.16f, 0.12f, 0.9f), "corpse");
                icon.rectTransform.sizeDelta = new Vector2(120, 120);
                UI.Label(icon.transform, "†", 72, c.looting ? Palette.Danger : Palette.Muted, TextAnchor.MiddleCenter, Art.TitleFont).rectTransform.Place(0, 0, 1, 1);
                if (c.items.Count > 0) UI.Label(icon.transform, c.items.Count.ToString(), 28, Palette.Title, TextAnchor.LowerRight, Art.Bold).rectTransform.Place(0, 0, 1, 1, 6, 4, 10, 4);
                var corpse = c;
                icon.Tap(() => LootAll(corpse));
            }
            foreach (var it in view.location.items.Where(i => i.takeable))
            {
                var icon = UI.Panel(ground, new Color(0.12f, 0.1f, 0.08f, 0.85f), "item");
                icon.rectTransform.sizeDelta = new Vector2(120, 120);
                var pic = UI.Picture(icon.transform, Art.Item(it.id));
                pic.rectTransform.Place(0, 0, 1, 1, 8, 8, 8, 8);
                if (it.count > 1) UI.Label(icon.transform, it.count.ToString(), 28, Palette.Title, TextAnchor.LowerRight, Art.Bold).rectTransform.Place(0, 0, 1, 1, 6, 4, 10, 4);
                var item = it;
                icon.Tap(() => { Toast("Взято: " + item.name, Palette.Title); Act(() => app.Api.Take(item.id)); });
            }
        }

        async void LootAll(CorpseView c)
        {
            if (busy) return;
            if (c.items.Count == 0) { Toast(c.name + ": пусто", Palette.Muted); return; }
            busy = true;
            try
            {
                GameView last = null;
                foreach (var it in c.items.ToList())
                {
                    last = await app.Api.Loot(c.id, it.id);
                    Toast("Взято: " + it.name, Palette.Title);
                }
                if (last != null) Apply(last);
            }
            catch (ApiError e) { Toast(e.Message, Palette.Danger); }
            finally { busy = false; }
        }

        // ---- exits ---------------------------------------------------------------------------------

        void ShowExits()
        {
            var byDir = new ExitView[8];
            var rest = new List<ExitView>();
            foreach (var e in view.location.exits)
            {
                var d = Compass.Of(view.location.id, e);
                if (d < 0 || byDir[d] != null) rest.Add(e); else byDir[d] = e;
            }
            stick.SetExits(byDir);
            UI.Clear(doors);
            foreach (var e in rest.Take(3))
            {
                var exit = e;
                var b = UI.Button(doors, e.label, () => Go(exit), e.occupied ? Palette.DangerFill : new Color(0.16f, 0.12f, 0.08f, 0.85f), 30);
                b.GetComponent<RectTransform>().sizeDelta = new Vector2(0, 80);
            }
        }

        void Go(ExitView exit) => Act(() => app.Api.Move(exit.target));

        // ---- notes ---------------------------------------------------------------------------------

        void ShowJournal(GameView before, bool moved)
        {
            if (before == null) return;
            var old = before.journal;
            var fresh = view.journal;
            int start = 0;
            if (old.Count > 0)
            {
                var last = old[old.Count - 1];
                var at = fresh.LastIndexOf(last);
                start = at >= 0 ? at + 1 : System.Math.Max(0, fresh.Count - 2);
            }
            foreach (var line in fresh.Skip(start).TakeLast(3)) Toast(line, Palette.Text);
        }

        public void Toast(string text, Color color)
        {
            if (string.IsNullOrEmpty(text)) return;
            var t = UI.Label(toasts, text, 32, color, TextAnchor.UpperLeft);
            var group = t.gameObject.AddComponent<CanvasGroup>();
            while (toasts.childCount > 3) DestroyImmediate(toasts.GetChild(0).gameObject);
            StartCoroutine(FadeLater(group));
        }

        IEnumerator FadeLater(CanvasGroup g)
        {
            yield return new WaitForSeconds(4f);
            yield return Fx.Fade(g, 1, 0, 1f, true);
        }

        // ---- the hero ------------------------------------------------------------------------------

        void OpenDoll()
        {
            if (doll != null || view == null) return;
            doll = new Paperdoll(screen, this, () => doll = null);
            doll.Show(view);
        }

        public void Equip(string id) => Act(() => app.Api.Equip(id));
        public void Unequip(string id) => Act(() => app.Api.Unequip(id));
        public void SignOut() => app.SignOut();
    }
}
