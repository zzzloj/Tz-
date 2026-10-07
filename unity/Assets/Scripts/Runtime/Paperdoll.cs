// The hero as a doll (prototype): worn things in their slots around the figure, the backpack as
// a grid of pictures. Tap a slot to take a thing off, tap a thing in the backpack to put it on.
using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;
using UnityEngine.UI;

namespace Amulet
{
    public class Paperdoll
    {
        // Slots by the item id prefix (Rules.equipSlot): left and right of the figure, top to bottom.
        static readonly (string slot, string title)[] Left =
        {
            ("i.a.h.", "голова"), ("i.a.m.", "шея"), ("i.a.e.", "плащ"), ("i.a.b.", "тело"), ("i.a.p.", "руки"), ("i.a.l.", "ноги"), ("i.a.c.", "обувь"),
        };
        static readonly (string slot, string title)[] Right =
        {
            ("weapon", "оружие"), ("i.a.s.", "щит"), ("i.a.r.", "одежда"), ("i.a.w.", "штаны"), ("i.a.k.", "кольцо"), ("i.a.n.", "браслет"), ("i.a.o.", "очки"),
        };

        readonly RectTransform root, slots, grid;
        readonly Text stats, title;
        readonly GameScreen game;
        readonly Action closed;

        public Paperdoll(RectTransform screen, GameScreen game, Action closed)
        {
            this.game = game;
            this.closed = closed;
            var bg = UI.Panel(screen, new Color(0.04f, 0.03f, 0.02f, 0.97f), "doll", false);
            root = bg.rectTransform.Place(0, 0, 1, 1);
            bg.raycastTarget = true;
            title = UI.Label(root, "", 56, Palette.Title, TextAnchor.UpperLeft, Art.TitleFont);
            title.rectTransform.Place(0, 1, 1, 1, 32, -100, 260, 24);
            UI.Button(root, "Закрыть", Close, Palette.Raised, 34).GetComponent<RectTransform>().Place(1, 1, 1, 1, -240, -100, 24, 20);
            stats = UI.Label(root, "", 32, Palette.Text, TextAnchor.UpperLeft);
            stats.rectTransform.Place(0, 1, 0.55f, 1, 32, -230, 0, 110);
            // landscape: the doll on the left, the backpack on the right
            slots = UI.Node("slots", root).Place(0, 0, 0.55f, 1, 24, 130, 0, 240);
            UI.Label(root, "Рюкзак", 40, Palette.Accent, TextAnchor.UpperLeft, Art.TitleFont).rectTransform.Place(0.58f, 1, 1, 1, 0, -170, 24, 110);
            var scroll = UI.Node("scroll", root).Place(0.58f, 0, 1, 1, 0, 30, 24, 180);
            scroll.gameObject.AddComponent<RectMask2D>();
            var sr = scroll.gameObject.AddComponent<ScrollRect>();
            sr.horizontal = false;
            grid = UI.Node("grid", scroll);
            grid.anchorMin = new Vector2(0, 1); grid.anchorMax = new Vector2(1, 1); grid.pivot = new Vector2(0.5f, 1);
            grid.offsetMin = grid.offsetMax = Vector2.zero;
            var layout = grid.gameObject.AddComponent<GridLayoutGroup>();
            layout.cellSize = new Vector2(140, 140);
            layout.spacing = new Vector2(16, 16);
            var fit = grid.gameObject.AddComponent<ContentSizeFitter>();
            fit.verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            sr.content = grid;
            sr.viewport = scroll;
            UI.Button(root, "Выйти из аккаунта", () => { Close(); game.SignOut(); }, Palette.DangerFill, 32)
                .GetComponent<RectTransform>().Place(0, 0, 0, 0, 24, 24, -520, -110);
        }

        void Close()
        {
            UnityEngine.Object.Destroy(root.gameObject);
            closed();
        }

        public void Show(GameView view)
        {
            if (root == null) return;
            var ch = view.character;
            title.text = ch.name;
            stats.text = $"Уровень {ch.level} · мощь {ch.power}\n" +
                         $"Сила {ch.str} · Ловкость {ch.dex} · Интеллект {ch.intel}\n" +
                         $"Удар {ch.dmgMin}–{ch.dmgMax} · Броня {ch.armor} · Уклон {ch.dodge}";

            UI.Clear(slots);
            var worn = view.inventory.Where(i => i.equipped).ToList();
            var figure = UI.Panel(slots, Palette.Raised, "figure");
            figure.sprite = Art.Circle;
            figure.rectTransform.Place(0.3f, 0.2f, 0.7f, 0.8f);
            UI.Label(figure.transform, ch.name.Substring(0, 1), 200, Palette.Muted, TextAnchor.MiddleCenter, Art.TitleFont).rectTransform.Place(0, 0, 1, 1);
            Column(Left, worn, 0f);
            Column(Right, worn, 0.78f);

            UI.Clear(grid);
            foreach (var it in view.inventory.Where(i => !i.equipped))
            {
                var cell = UI.Panel(grid, Palette.Raised, "item");
                UI.Picture(cell.transform, Art.Item(it.id)).rectTransform.Place(0, 0, 1, 1, 10, 10, 10, 10);
                if (it.count > 1) UI.Label(cell.transform, it.count.ToString(), 28, Palette.Title, TextAnchor.LowerRight, Art.Bold).rectTransform.Place(0, 0, 1, 1, 6, 4, 10, 4);
                if (Art.Item(it.id) == null) UI.Label(cell.transform, it.name, 22, Palette.Text).rectTransform.Place(0, 0, 1, 1, 6, 6, 6, 6);
                var item = it;
                cell.Tap(() =>
                {
                    if (item.equippable) game.Equip(item.id);
                    else game.Toast(item.name + (item.usable ? ": использовать — в обычном приложении" : ""), Palette.Muted);
                });
            }
        }

        void Column((string slot, string title)[] list, List<InventoryItemView> worn, float x)
        {
            for (int i = 0; i < list.Length; i++)
            {
                var (slot, name) = list[i];
                var on = worn.FirstOrDefault(w => slot == "weapon" ? w.id.StartsWith("i.w.") : w.id.StartsWith(slot));
                var cell = UI.Panel(slots, on != null ? Palette.Primary : Palette.Sunken, "slot");
                float top = 1 - i / (float)list.Length, bottom = 1 - (i + 1) / (float)list.Length;
                cell.rectTransform.Place(x, bottom, x + 0.22f, top, 4, 4, 4, 4);
                if (on != null)
                {
                    UI.Picture(cell.transform, Art.Item(on.id)).rectTransform.Place(0, 0, 0.45f, 1, 6, 6, 0, 6);
                    UI.Label(cell.transform, on.name, 22, Palette.OnPrimary, TextAnchor.MiddleLeft).rectTransform.Place(0.45f, 0, 1, 1, 4, 2, 4, 2);
                    var item = on;
                    cell.Tap(() => game.Unequip(item.id));
                }
                else UI.Label(cell.transform, name, 24, Palette.Muted).rectTransform.Place(0, 0, 1, 1);
            }
        }
    }
}
