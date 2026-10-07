// Moving between places (owner 07.10: the compass arrows looked like a relic, «примерно как на
// скриншоте» — a joystick in the lower left and a minimap in the upper right):
//   - the joystick: pull the knob towards an exit and let go to walk there; keep holding to walk on
//     in that direction place after place. Notches around the ring light up where there are exits,
//     the name of the chosen exit shows above it;
//   - the minimap: the places around you as a little graph by the directions of their exits, you in
//     gold, the places you can walk to lit and tappable.
// The world is still a graph of places: the joystick picks one of up to eight exits by direction.
using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Amulet
{
    /// <summary>Directions of exits: 0 — east, then counter-clockwise every 45° (2 — north, 4 — west, 6 — south).</summary>
    public static class Compass
    {
        static readonly (string word, int dir)[] Words =
        {
            ("северо-восток", 1), ("северо-запад", 3), ("юго-запад", 5), ("юго-восток", 7),
            ("север", 2), ("запад", 4), ("юг", 6), ("восток", 0),
        };
        static readonly Regex Street = new Regex("^x(\\d+)x(\\d+)$");

        /// <summary>Coordinates of a street location from its id ("x1039x50"), or null.</summary>
        public static Vector2? Point(string id)
        {
            if (string.IsNullOrEmpty(id)) return null;
            var m = Street.Match(id);
            if (!m.Success) return null;
            return new Vector2(int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value));
        }

        /// <summary>The direction of an exit: by its words («на север в рощу»), else by the map coordinates; −1 if neither tells.</summary>
        public static int Of(string from, ExitView exit)
        {
            var label = (exit.label ?? "").ToLowerInvariant();
            foreach (var (word, dir) in Words) if (label.Contains(word)) return dir;
            var a = Point(from); var b = Point(exit.target);
            if (a == null || b == null) return -1;
            float dx = b.Value.x - a.Value.x, dy = a.Value.y - b.Value.y;   // y on the map grows south
            if (dx == 0 && dy == 0) return -1;
            var deg = Mathf.Atan2(dy, dx) * Mathf.Rad2Deg;
            return ((int)Mathf.Round(deg / 45f) + 8) % 8;
        }

        public static Vector2 Vector(int dir)
        {
            var rad = dir * 45f * Mathf.Deg2Rad;
            return new Vector2(Mathf.Cos(rad), Mathf.Sin(rad));
        }
    }

    /// <summary>The joystick of exits.</summary>
    public class Stick : MonoBehaviour, IPointerDownHandler, IDragHandler, IPointerUpHandler
    {
        const float Size = 400, Reach = 130, Dead = 45;

        /// <summary>The exit under the knob, or null.</summary>
        public ExitView Chosen => dir >= 0 ? exits[dir] : null;
        public bool Held { get; private set; }
        /// <summary>When the knob came to rest on the current exit (for walking on while held).</summary>
        public float ChosenAt { get; private set; }
        /// <summary>Set by the screen once it walked while the stick was held, so letting go does not walk again.</summary>
        public bool Walked;
        public Action<ExitView> Release;
        /// <summary>The view from above: the knob is the way to walk, not a choice of exit.</summary>
        public bool Free { get; private set; }
        /// <summary>How far and where the knob is pulled, −1..1 (free mode).</summary>
        public Vector2 Pull => Held ? knob.anchoredPosition * (1f / Reach) : Vector2.zero;

        public void SetFree(bool free)
        {
            Free = free;
            foreach (var n in notches) n.gameObject.SetActive(!free);
            hint.text = "";
        }

        RectTransform area, knob;
        Text hint;
        readonly Image[] notches = new Image[8];
        readonly ExitView[] exits = new ExitView[8];
        int dir = -1;

        public static Stick Make(RectTransform parent)
        {
            var root = UI.Node("stick", parent);
            root.anchorMin = root.anchorMax = Vector2.zero;
            root.pivot = new Vector2(0.5f, 0.5f);
            root.sizeDelta = new Vector2(Size, Size);
            root.anchoredPosition = new Vector2(Size / 2 + 50, Size / 2 + 30);
            var s = root.gameObject.AddComponent<Stick>();
            s.Build(root);
            return s;
        }

        void Build(RectTransform root)
        {
            area = root;
            var touch = root.gameObject.AddComponent<Image>();     // the whole square catches the finger
            touch.color = new Color(0, 0, 0, 0);
            var ring = UI.Panel(root, new Color(Palette.Accent.r, Palette.Accent.g, Palette.Accent.b, 0.45f), "ring");
            ring.sprite = Art.Circle; ring.type = Image.Type.Simple; ring.raycastTarget = false;
            ring.rectTransform.Place(0, 0, 1, 1, 40, 40, 40, 40);
            var inner = UI.Panel(ring.transform, new Color(0.05f, 0.04f, 0.03f, 0.55f), "inner");
            inner.sprite = Art.Circle; inner.type = Image.Type.Simple; inner.raycastTarget = false;
            inner.rectTransform.Place(0, 0, 1, 1, 6, 6, 6, 6);
            for (int i = 0; i < 8; i++)
            {
                var n = UI.Picture(root, Art.Arrow, "notch " + i);
                var at = Compass.Vector(i) * (Size / 2 - 24);
                n.rectTransform.At(0.5f, 0.5f, 52, 52, at.x, at.y);
                n.rectTransform.localRotation = Quaternion.Euler(0, 0, i * 45 - 90);
                notches[i] = n;
            }
            var k = UI.Panel(root, new Color(Palette.Accent.r, Palette.Accent.g, Palette.Accent.b, 0.85f), "knob");
            k.sprite = Art.Circle; k.type = Image.Type.Simple; k.raycastTarget = false;
            knob = k.rectTransform.At(0.5f, 0.5f, 140, 140);
            var dot = UI.Panel(knob, new Color(0.1f, 0.07f, 0.04f, 0.6f), "dot");
            dot.sprite = Art.Circle; dot.type = Image.Type.Simple; dot.raycastTarget = false;
            dot.rectTransform.Place(0, 0, 1, 1, 34, 34, 34, 34);
            hint = UI.Label(root, "", 34, Palette.Title, TextAnchor.LowerCenter, Art.Bold);
            hint.rectTransform.Place(0, 1, 1, 1, -120, 0, -120, -70);
        }

        /// <summary>The exits of the place by direction (index as in <see cref="Compass"/>).</summary>
        public void SetExits(ExitView[] byDir)
        {
            if (Free) return;
            for (int i = 0; i < 8; i++)
            {
                exits[i] = byDir[i];
                notches[i].color = byDir[i] == null ? new Color(1, 1, 1, 0.12f)
                    : byDir[i].occupied ? Palette.Danger : Palette.Title;
            }
            Choose(dir >= 0 && exits[dir] != null ? dir : -1);
        }

        public void OnPointerDown(PointerEventData e) { Held = true; Walked = false; OnDrag(e); }

        public void OnDrag(PointerEventData e)
        {
            if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(area, e.position, null, out var local)) return;
            var pull = Vector2.ClampMagnitude(local, Reach);
            knob.anchoredPosition = pull;
            if (Free) return;
            if (local.magnitude < Dead) { Choose(-1); return; }
            // The nearest direction that has an exit, within 60° of the pull.
            var deg = Mathf.Atan2(local.y, local.x) * Mathf.Rad2Deg;
            int best = -1; float bestGap = 61;
            for (int i = 0; i < 8; i++)
            {
                if (exits[i] == null) continue;
                var gap = Mathf.Abs(Mathf.DeltaAngle(deg, i * 45));
                if (gap < bestGap) { bestGap = gap; best = i; }
            }
            Choose(best);
        }

        public void OnPointerUp(PointerEventData e)
        {
            Held = false;
            knob.anchoredPosition = Vector2.zero;
            if (Free) return;
            var go = Chosen;
            Choose(-1);
            if (go != null && !Walked) Release?.Invoke(go);
        }

        void Choose(int next)
        {
            if (next == dir) return;
            if (dir >= 0) notches[dir].rectTransform.localScale = Vector3.one;
            dir = next;
            ChosenAt = Time.time;
            if (dir >= 0) notches[dir].rectTransform.localScale = Vector3.one * 1.5f;
            hint.text = dir >= 0 ? exits[dir].label : "";
        }
    }

    /// <summary>
    /// The minimap: the places around the hero as a little graph, laid out by the directions of the
    /// exits (Data/world.json from content/locations, so it works in houses and dungeons too). You are
    /// the gold ring, the places you can walk to are lit and tappable.
    /// (The first try placed dots by the street coordinates of /api/map; those are map pixels about
    /// 25 apart, so the neighbours fell outside the frame and the map stood empty — owner 07.10.)
    /// </summary>
    public class Minimap
    {
        public class Place { public string n; public List<List<string>> e = new List<List<string>>(); }

        const float Cell = 64;
        const int SpanX = 3, SpanY = 2, Depth = 4;

        static Dictionary<string, Place> world;
        readonly RectTransform lines, dots;
        readonly Text caption;

        public Minimap(RectTransform parent)
        {
            var frame = UI.Panel(parent, new Color(0.13f, 0.1f, 0.07f, 0.82f), "minimap");
            frame.rectTransform.Place(1, 1, 1, 1, -((SpanX * 2 + 1) * Cell + 70), -((SpanY * 2 + 1) * Cell + 80), 30, 30);
            var border = UI.Panel(frame.transform, new Color(Palette.Accent.r, Palette.Accent.g, Palette.Accent.b, 0.55f), "border");
            border.rectTransform.Place(0, 0, 1, 1, -4, -4, -4, -4);
            border.transform.SetAsFirstSibling();
            var view = UI.Node("view", frame.transform).Place(0, 0, 1, 1, 6, 40, 6, 6);
            view.gameObject.AddComponent<RectMask2D>();
            lines = UI.Node("lines", view).Place(0, 0, 1, 1);
            dots = UI.Node("dots", view).Place(0, 0, 1, 1);
            caption = UI.Label(frame.transform, "", 26, Palette.Title, TextAnchor.MiddleCenter, Art.Bold);
            caption.rectTransform.Place(0, 0, 1, 0, 8, 4, 8, -40);
            if (world == null)
            {
                var json = Resources.Load<TextAsset>("Data/world");
                world = json != null ? Newtonsoft.Json.JsonConvert.DeserializeObject<Dictionary<string, Place>>(json.text) : new Dictionary<string, Place>();
            }
        }

        static ExitView Exit(List<string> e) => new ExitView { label = e[0], target = e[1] };

        static (int, int) Step(int dir)
        {
            var v = Compass.Vector(dir);
            return ((int)Mathf.Round(v.x), (int)Mathf.Round(v.y));
        }

        /// <summary>Redraws around [here]; [go] walks through one of its exits.</summary>
        public void Show(LocationView here, Action<ExitView> go)
        {
            UI.Clear(lines);
            UI.Clear(dots);
            caption.text = here.name;
            // Lay the places out on a grid, breadth first: each exit one cell away in its direction;
            // a place whose cell is taken (or has no direction) is left off.
            var at = new Dictionary<string, (int x, int y)> { [here.id] = (0, 0) };
            var taken = new HashSet<(int, int)> { (0, 0) };
            var queue = new Queue<(string id, int depth)>();
            queue.Enqueue((here.id, 0));
            while (queue.Count > 0)
            {
                var (id, depth) = queue.Dequeue();
                if (depth >= Depth) continue;
                var exits = id == here.id ? here.exits : world.TryGetValue(id, out var p) ? p.e.ConvertAll(Exit) : new List<ExitView>();
                var (x, y) = at[id];
                foreach (var e in exits)
                {
                    if (at.ContainsKey(e.target)) continue;
                    var d = Compass.Of(id, e);
                    if (d < 0) continue;
                    var (dx, dy) = Step(d);
                    var cell = (x + dx, y + dy);
                    if (Math.Abs(cell.Item1 + cell.Item2) > 4 || Math.Abs(cell.Item2 - cell.Item1) > 5 || taken.Contains(cell)) continue;   // what fits the frame, turned like the world
                    taken.Add(cell);
                    at[e.target] = cell;
                    queue.Enqueue((e.target, depth + 1));
                }
            }
            // Roads between the places that are both on the map.
            var drawn = new HashSet<string>();
            foreach (var kv in at)
            {
                var exits = kv.Key == here.id ? here.exits : world.TryGetValue(kv.Key, out var p) ? p.e.ConvertAll(Exit) : new List<ExitView>();
                foreach (var e in exits)
                {
                    if (!at.TryGetValue(e.target, out var to)) continue;
                    var pair = string.CompareOrdinal(kv.Key, e.target) < 0 ? kv.Key + "|" + e.target : e.target + "|" + kv.Key;
                    if (!drawn.Add(pair)) continue;
                    bool mine = kv.Key == here.id || e.target == here.id;
                    Road(kv.Value, to, mine ? 0.7f : 0.3f);
                }
            }
            foreach (var kv in at)
                if (kv.Key != here.id) Dot(kv.Value, 16, new Color(0.85f, 0.75f, 0.55f, 0.6f));
            foreach (var e in here.exits)
            {
                if (!at.TryGetValue(e.target, out var cell)) continue;
                var d = Dot(cell, 34, e.occupied ? Palette.Danger : Palette.Accent);
                var exit = e;
                d.Tap(() => go(exit));
            }
            var me = Dot((0, 0), 40, Palette.Title);
            var hole = UI.Panel(me.transform, new Color(0.1f, 0.07f, 0.04f, 1), "hole");
            hole.sprite = Art.Circle; hole.type = Image.Type.Simple; hole.raycastTarget = false;
            hole.rectTransform.Place(0, 0, 1, 1, 12, 12, 12, 12);
        }

        /// <summary>A cell of the scheme on the minimap, turned like the world (north up-right).</summary>
        static Vector2 P((int x, int y) cell) => World.Iso(new Vector2(cell.x, cell.y)) * (Cell * 1.3f);

        Image Dot((int x, int y) cell, float size, Color color)
        {
            var d = UI.Panel(dots, color, "dot");
            d.sprite = Art.Circle; d.type = Image.Type.Simple; d.raycastTarget = false;
            var at = P(cell);
            d.rectTransform.At(0.5f, 0.5f, size, size, at.x, at.y);
            return d;
        }

        void Road((int x, int y) a, (int x, int y) b, float alpha)
        {
            var line = UI.Panel(lines, new Color(0.9f, 0.8f, 0.6f, alpha), "road", false);
            line.raycastTarget = false;
            Vector2 pa = P(a), pb = P(b);
            float dx = pb.x - pa.x, dy = pb.y - pa.y;
            var rt = line.rectTransform.At(0.5f, 0.5f, Mathf.Sqrt(dx * dx + dy * dy), 5, (pa.x + pb.x) / 2, (pa.y + pb.y) / 2);
            rt.localRotation = Quaternion.Euler(0, 0, Mathf.Atan2(dy, dx) * Mathf.Rad2Deg);
        }
    }
}
