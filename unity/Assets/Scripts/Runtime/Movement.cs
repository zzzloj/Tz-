// Moving between places (owner 07.10: the compass arrows looked like a relic, «примерно как на
// скриншоте» — a joystick in the lower left and a minimap in the upper right):
//   - the joystick: pull the knob towards an exit and let go to walk there; keep holding to walk on
//     in that direction place after place. Notches around the ring light up where there are exits,
//     the name of the chosen exit shows above it;
//   - the minimap: the streets around you as dots, you in gold, the places you can walk to lit and
//     tappable.
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
            var go = Chosen;
            knob.anchoredPosition = Vector2.zero;
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

    /// <summary>The minimap: the streets around the hero.</summary>
    public class Minimap
    {
        const float Cell = 26;
        const int SpanX = 9, SpanY = 6;

        readonly RectTransform dots;
        readonly Text caption;
        Dictionary<string, MapPoint> points;
        Vector2? last;

        public Minimap(RectTransform parent)
        {
            var frame = UI.Panel(parent, new Color(0.23f, 0.18f, 0.12f, 0.92f), "minimap");
            frame.rectTransform.Place(1, 1, 1, 1, -(SpanX * 2 + 1) * Cell - 60, -(SpanY * 2 + 1) * Cell - 70, 30, 30);
            var border = UI.Panel(frame.transform, new Color(Palette.Accent.r, Palette.Accent.g, Palette.Accent.b, 0.5f), "border");
            border.rectTransform.Place(0, 0, 1, 1, -4, -4, -4, -4);
            border.transform.SetAsFirstSibling();
            var view = UI.Node("view", frame.transform).Place(0, 0, 1, 1, 6, 6, 6, 6);
            view.gameObject.AddComponent<RectMask2D>();
            dots = UI.Node("dots", view).Place(0, 0, 1, 1);
            caption = UI.Label(frame.transform, "", 26, Palette.Muted, TextAnchor.LowerCenter);
            caption.rectTransform.Place(0, 0, 1, 0, 0, 6, 0, -40);
        }

        public void SetPoints(MapView map)
        {
            points = new Dictionary<string, MapPoint>();
            foreach (var p in map.points) points[p.id] = p;
        }

        /// <summary>Redraws around [here]; exits to street places become tappable dots.</summary>
        public void Show(LocationView here, Action<ExitView> go)
        {
            UI.Clear(dots);
            var at = Compass.Point(here.id);
            if (at != null) last = at;
            caption.text = at == null ? "вы не на улице" : "";
            if (points == null || last == null) return;
            var c = last.Value;
            foreach (var p in points.Values)
            {
                float dx = p.mapX - c.x, dy = p.mapY - c.y;
                if (Mathf.Abs(dx) > SpanX + 1 || Mathf.Abs(dy) > SpanY + 1) continue;
                Dot(dx, dy, 12, p.zone == 1 ? new Color(0.45f, 0.62f, 0.85f, 0.8f) : new Color(0.75f, 0.68f, 0.55f, 0.55f));
            }
            if (at != null)
                foreach (var e in here.exits)
                {
                    if (!points.TryGetValue(e.target, out var p)) continue;
                    var d = Dot(p.mapX - c.x, p.mapY - c.y, 30, e.occupied ? Palette.Danger : Palette.Accent);
                    var exit = e;
                    d.Tap(() => go(exit));
                }
            var me = Dot(0, 0, 34, Palette.Title);
            if (at == null) me.color = new Color(1, 1, 1, 0.35f);
            var hole = UI.Panel(me.transform, new Color(0.1f, 0.07f, 0.04f, 1), "hole");
            hole.sprite = Art.Circle; hole.type = Image.Type.Simple; hole.raycastTarget = false;
            hole.rectTransform.Place(0, 0, 1, 1, 10, 10, 10, 10);
        }

        Image Dot(float dx, float dy, float size, Color color)
        {
            var d = UI.Panel(dots, color, "dot");
            d.sprite = Art.Circle; d.type = Image.Type.Simple; d.raycastTarget = false;
            d.rectTransform.At(0.5f, 0.5f, size, size, dx * Cell, -dy * Cell);
            return d;
        }
    }
}
