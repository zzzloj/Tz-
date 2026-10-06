// Building blocks of the interface, made from code (no prefabs or scenes to lay out by hand):
// the palette of the apps (tz.shared.Design, «Ночь»), fonts, pictures from Resources/Art, and
// helpers for panels, labels, buttons, bars, input fields and small animations.
using System;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

namespace Amulet
{
    public static class Palette
    {
        static Color C(uint argb) => new Color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >> 24) & 255) / 255f);
        public static readonly Color Background = C(0xFF0B0907);
        public static readonly Color Surface = C(0xFF15110E);
        public static readonly Color Raised = C(0xFF1C1612);
        public static readonly Color Sunken = C(0xFF0F0C09);
        public static readonly Color Border = C(0xFF5A4430);
        public static readonly Color Accent = C(0xFFC9A45C);
        public static readonly Color Title = C(0xFFE9CF8E);
        public static readonly Color Primary = C(0xFF6E4F22);
        public static readonly Color OnPrimary = C(0xFFF5E6C0);
        public static readonly Color Text = C(0xFFE8DCC2);
        public static readonly Color Muted = C(0xFFA8987C);
        public static readonly Color Danger = C(0xFFE0604F);
        public static readonly Color DangerFill = C(0xFF7D1C14);
        public static readonly Color Health = C(0xFFD0412F);
        public static readonly Color Mana = C(0xFF4F86D6);
        public static readonly Color Exp = C(0xFFE0BD6A);
        public static readonly Color Good = C(0xFF7FBF6A);
        public static readonly Color Shade = new Color(0, 0, 0, 0.6f);

        /// <summary>Colour of a win chance: green from 70 %, gold 40–69 %, red below (GameScene.oddsTone).</summary>
        public static Color Odds(int? chance) => chance == null ? Accent : chance >= 70 ? Good : chance >= 40 ? Accent : Danger;
    }

    public static class Art
    {
        static readonly Dictionary<string, Sprite> Cache = new Dictionary<string, Sprite>();
        static Font body, bold, title;
        public static Font Body => body ? body : body = Resources.Load<Font>("Fonts/alegreya_sans_regular") ?? Font.CreateDynamicFontFromOSFont("Arial", 32);
        public static Font Bold => bold ? bold : bold = Resources.Load<Font>("Fonts/alegreya_sans_bold") ?? Body;
        public static Font TitleFont => title ? title : title = Resources.Load<Font>("Fonts/cormorant_sc_bold") ?? Bold;

        /// <summary>A picture under Resources/Art by its key ("mobs/mob-wolf", "locations/loc-bank"); null if there is none.</summary>
        public static Sprite Get(string key)
        {
            if (string.IsNullOrEmpty(key)) return null;
            if (Cache.TryGetValue(key, out var s)) return s;
            var tex = Resources.Load<Texture2D>("Art/" + key);
            s = tex == null ? null : Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(0.5f, 0.5f), 100f);
            Cache[key] = s;
            return s;
        }

        /// <summary>An item's picture: the id itself, then its base before «..», «_», «-» (GameScene.itemArtCandidates).</summary>
        public static Sprite Item(string id)
        {
            var bases = new[] { id, Before(id, ".."), Before(id, "_"), Before(Before(Before(id, "_"), "-"), "..") };
            foreach (var b in bases) { var s = Get("items/" + b.Replace('.', '_')); if (s != null) return s; }
            return null;
        }

        static string Before(string s, string sep) { var i = s.IndexOf(sep, StringComparison.Ordinal); return i < 0 ? s : s.Substring(0, i); }

        static Sprite rounded, circle, arrow;
        /// <summary>A plain arrow pointing up (the bundled fonts have no diagonal arrows).</summary>
        public static Sprite Arrow => arrow ? arrow : arrow = MakeArrow(96);

        static Sprite MakeArrow(int size)
        {
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { filterMode = FilterMode.Bilinear, wrapMode = TextureWrapMode.Clamp };
            var px = new Color32[size * size];
            float cx = size / 2f;
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                {
                    float fy = (y + 0.5f) / size, fx = Mathf.Abs(x + 0.5f - cx) / size;
                    // a head (top 55 %) and a shaft (bottom 45 %)
                    bool head = fy > 0.45f && fx < (1 - fy) * 0.9f + 0.001f;
                    bool shaft = fy <= 0.47f && fy > 0.08f && fx < 0.11f;
                    px[y * size + x] = new Color32(255, 255, 255, (byte)(head || shaft ? 255 : 0));
                }
            tex.SetPixels32(px);
            tex.Apply();
            return Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f);
        }
        /// <summary>A rounded rectangle for 9-slicing.</summary>
        public static Sprite Rounded => rounded ? rounded : rounded = MakeRounded(48, 14);
        public static Sprite Circle => circle ? circle : circle = MakeRounded(128, 64);

        static Sprite MakeRounded(int size, int radius)
        {
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { filterMode = FilterMode.Bilinear, wrapMode = TextureWrapMode.Clamp };
            var px = new Color32[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                {
                    float dx = Mathf.Max(0, Mathf.Max(radius - x - 0.5f, x + 0.5f - (size - radius)));
                    float dy = Mathf.Max(0, Mathf.Max(radius - y - 0.5f, y + 0.5f - (size - radius)));
                    float d = Mathf.Sqrt(dx * dx + dy * dy);
                    byte a = (byte)(255 * Mathf.Clamp01(radius - d + 0.5f));
                    px[y * size + x] = new Color32(255, 255, 255, a);
                }
            tex.SetPixels32(px);
            tex.Apply();
            var border = radius < size / 2 ? new Vector4(radius, radius, radius, radius) : Vector4.zero;
            return Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect, border);
        }
    }

    public static class UI
    {
        public static RectTransform Node(string name, Transform parent)
        {
            var go = new GameObject(name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            return (RectTransform)go.transform;
        }

        /// <summary>Anchors [rt] to a part of its parent: min and max in 0..1, then insets in reference pixels.</summary>
        public static RectTransform Place(this RectTransform rt, float x0, float y0, float x1, float y1, float l = 0, float b = 0, float r = 0, float t = 0)
        {
            rt.anchorMin = new Vector2(x0, y0);
            rt.anchorMax = new Vector2(x1, y1);
            rt.offsetMin = new Vector2(l, b);
            rt.offsetMax = new Vector2(-r, -t);
            return rt;
        }

        /// <summary>A fixed-size box centred on a point of the parent (anchor in 0..1).</summary>
        public static RectTransform At(this RectTransform rt, float ax, float ay, float w, float h, float dx = 0, float dy = 0)
        {
            rt.anchorMin = rt.anchorMax = new Vector2(ax, ay);
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = new Vector2(w, h);
            rt.anchoredPosition = new Vector2(dx, dy);
            return rt;
        }

        public static Image Panel(Transform parent, Color color, string name = "panel", bool rounded = true)
        {
            var img = Node(name, parent).gameObject.AddComponent<Image>();
            img.color = color;
            if (rounded) { img.sprite = Art.Rounded; img.type = Image.Type.Sliced; }
            return img;
        }

        public static Image Picture(Transform parent, Sprite sprite, string name = "picture")
        {
            var img = Node(name, parent).gameObject.AddComponent<Image>();
            img.sprite = sprite;
            img.preserveAspect = true;
            img.raycastTarget = false;
            if (sprite == null) img.color = new Color(0, 0, 0, 0);
            return img;
        }

        public static Text Label(Transform parent, string text, int size, Color color, TextAnchor align = TextAnchor.MiddleCenter, Font font = null)
        {
            var t = Node("label", parent).gameObject.AddComponent<Text>();
            t.font = font ?? Art.Body;
            t.fontSize = size;
            t.color = color;
            t.alignment = align;
            t.text = text;
            t.horizontalOverflow = HorizontalWrapMode.Wrap;
            t.verticalOverflow = VerticalWrapMode.Overflow;
            t.raycastTarget = false;
            var sh = t.gameObject.AddComponent<Shadow>();
            sh.effectColor = new Color(0, 0, 0, 0.8f);
            sh.effectDistance = new Vector2(2, -2);
            return t;
        }

        public static Button Button(Transform parent, string text, Action onClick, Color? fill = null, int size = 40)
        {
            var img = Panel(parent, fill ?? Palette.Primary, "button");
            var b = img.gameObject.AddComponent<Button>();
            b.targetGraphic = img;
            var colors = b.colors;
            colors.pressedColor = new Color(0.75f, 0.75f, 0.75f);
            colors.disabledColor = new Color(0.5f, 0.5f, 0.5f, 0.6f);
            b.colors = colors;
            b.onClick.AddListener(() => onClick());
            if (text != null) Label(img.transform, text, size, Palette.OnPrimary, TextAnchor.MiddleCenter, Art.Bold).rectTransform.Place(0, 0, 1, 1, 12, 4, 12, 4);
            return b;
        }

        /// <summary>Makes any graphic tappable.</summary>
        public static Button Tap(this Graphic g, Action onClick)
        {
            g.raycastTarget = true;
            var b = g.gameObject.AddComponent<Button>();
            b.targetGraphic = g;
            b.transition = Selectable.Transition.None;
            b.onClick.AddListener(() => onClick());
            return b;
        }

        public class Bar
        {
            public Image Fill;
            public Text Value;
            public void Set(float value, float max, string text = null)
            {
                Fill.fillAmount = max <= 0 ? 0 : Mathf.Clamp01(value / max);
                if (Value != null) Value.text = text ?? $"{Mathf.Max(0, value):0} / {max:0}";
            }
        }

        public static Bar MakeBar(Transform parent, Color color, bool withText = true, int textSize = 28)
        {
            var track = Panel(parent, Palette.Sunken, "bar");
            var fill = Panel(track.transform, color, "fill");
            fill.rectTransform.Place(0, 0, 1, 1, 2, 2, 2, 2);
            fill.type = Image.Type.Filled;
            fill.fillMethod = Image.FillMethod.Horizontal;
            var bar = new Bar { Fill = fill };
            if (withText) bar.Value = Label(track.transform, "", textSize, Palette.Text);
            if (bar.Value != null) bar.Value.rectTransform.Place(0, 0, 1, 1);
            return bar;
        }

        public static InputField Input(Transform parent, string placeholder, bool password = false)
        {
            var bg = Panel(parent, Palette.Sunken, "input");
            var field = bg.gameObject.AddComponent<InputField>();
            var text = Label(bg.transform, "", 40, Palette.Text, TextAnchor.MiddleLeft);
            text.rectTransform.Place(0, 0, 1, 1, 24, 6, 24, 6);
            text.supportRichText = false;
            var hint = Label(bg.transform, placeholder, 40, Palette.Muted, TextAnchor.MiddleLeft);
            hint.rectTransform.Place(0, 0, 1, 1, 24, 6, 24, 6);
            field.textComponent = text;
            field.placeholder = hint;
            field.targetGraphic = bg;
            field.lineType = InputField.LineType.SingleLine;
            if (password) field.contentType = InputField.ContentType.Password;
            return field;
        }

        public static void Clear(Transform t)
        {
            for (int i = t.childCount - 1; i >= 0; i--) UnityEngine.Object.Destroy(t.GetChild(i).gameObject);
        }
    }

    /// <summary>Small effects for the fight: floating numbers, shaking, flashing, fading.</summary>
    public static class Fx
    {
        public static IEnumerator FloatText(Transform parent, string text, Color color, Vector2 from, int size = 64)
        {
            var t = UI.Label(parent, text, size, color, TextAnchor.MiddleCenter, Art.Bold);
            var rt = t.rectTransform.At(0.5f, 0.5f, 400, 120, from.x, from.y);
            float time = 0, life = 1.1f;
            while (time < life && t != null)
            {
                time += Time.deltaTime;
                float k = time / life;
                rt.anchoredPosition = from + new Vector2(0, 160 * k);
                rt.localScale = Vector3.one * (k < 0.15f ? Mathf.Lerp(0.6f, 1.3f, k / 0.15f) : Mathf.Lerp(1.3f, 1f, (k - 0.15f) / 0.85f));
                t.color = new Color(color.r, color.g, color.b, 1 - Mathf.Max(0, k - 0.6f) / 0.4f);
                yield return null;
            }
            if (t != null) UnityEngine.Object.Destroy(t.gameObject);
        }

        public static IEnumerator Shake(RectTransform rt, float strength = 18, float duration = 0.3f)
        {
            if (rt == null) yield break;
            var home = rt.anchoredPosition;
            float time = 0;
            while (time < duration && rt != null)
            {
                time += Time.deltaTime;
                float k = 1 - time / duration;
                rt.anchoredPosition = home + new Vector2(UnityEngine.Random.Range(-1f, 1f), UnityEngine.Random.Range(-1f, 1f)) * strength * k;
                yield return null;
            }
            if (rt != null) rt.anchoredPosition = home;
        }

        public static IEnumerator Flash(Graphic g, Color to, float duration = 0.35f)
        {
            if (g == null) yield break;
            var from = g.color;
            float time = 0;
            while (time < duration && g != null)
            {
                time += Time.deltaTime;
                float k = time / duration;
                g.color = Color.Lerp(to, from, k);
                yield return null;
            }
            if (g != null) g.color = from;
        }

        public static IEnumerator Fade(CanvasGroup group, float from, float to, float duration, bool destroy = false)
        {
            float time = 0;
            while (time < duration && group != null)
            {
                time += Time.deltaTime;
                group.alpha = Mathf.Lerp(from, to, time / duration);
                yield return null;
            }
            if (group != null) { group.alpha = to; if (destroy) UnityEngine.Object.Destroy(group.gameObject); }
        }

        /// <summary>Breathing: a gentle scale pulse while the object lives (idle figures).</summary>
        public static IEnumerator Breathe(RectTransform rt, float phase)
        {
            while (rt != null)
            {
                float s = 1 + 0.015f * Mathf.Sin(Time.time * 2.2f + phase);
                rt.localScale = new Vector3(s, s, 1);
                yield return null;
            }
        }
    }
}
