// The place seen from above (owner 07.10: «как Grim Soul»; pictures by unity/PROMPTS-TOPDOWN.md).
// A place is a square of ground Area × Area metres. The camera looks at it in isometry: the ground
// is drawn straight from above and then turned 45° and squashed 2:1, so north is up-right on the
// screen, east down-right, south down-left, west up-left. Houses and figures are upright pictures
// standing on it, drawn nearer the bottom of the screen in front.
//
// The server still knows only the graph of places, so everything here is made up on the client,
// the same way every time for the same place (seeded by its id):
//   - the ground: grass, dirt, a street or a road to every exit that has a direction, water on a
//     free side by the shore, a pier in the harbour — baked into one texture when you arrive;
//   - houses along the streets in towns; barrels, crates, carts and stalls by the streets, trees,
//     bushes and rocks in the wild, moorings along the pier and a boat by it (content/art/topdown/objects.json);
//   - exits: a lit ring where a road leaves the square; walk into it to go on;
//   - where the monsters and people of the place stand; those that have a figure drawn
//     (content/art/topdown/figures/fig-<portrait key>) stand there as figures, turned to the hero.
// The hero walks freely with the joystick; houses and water stop him.
using System;
using System.Collections.Generic;
using UnityEngine;

namespace Amulet
{
    public class World : MonoBehaviour
    {
        public const float Area = 30f;
        const float Speed = 4.2f, HeroRadius = 0.45f, Reach = 1.7f, Half = Area / 2;
        const float HouseGap = 0.75f;       // how close one comes to a house wall (porches and eaves stick out of the picture's walls)
        const int Px = 32;                  // ground texture pixels per metre
        const float Margin = 8;             // ground drawn beyond the square on every side, fading into the dark (owner 07.10: no void at the edge)
        const float HeroHeight = 2.4f;      // figures are drawn larger than life, as in Grim Soul

        /// <summary>A direction pulled on the joystick, in screen terms (−1..1); set every frame.</summary>
        public Vector2 Pull;
        /// <summary>Walking into an exit's ring.</summary>
        public Action<ExitView> Leave;
        public Vector2 Hero => hero;
        public Camera Cam => cam;
        public readonly List<(ExitView exit, Vector2 at)> Exits = new List<(ExitView, Vector2)>();

        Camera cam;
        Transform root, plane;
        SpriteRenderer heroPicture;
        Vector2 hero, look;
        float walk;
        bool leaving;
        string sex = "m";
        Biome biome;
        readonly List<Rect> blocks = new List<Rect>();
        readonly List<(Vector2 at, float r)> rounds = new List<(Vector2, float)>();
        readonly List<(Vector2 at, int order)> things = new List<(Vector2, int)>();
        readonly Dictionary<string, SpriteRenderer> people = new Dictionary<string, SpriteRenderer>();
        readonly List<(Vector2 a, Vector2 b)> roads = new List<(Vector2, Vector2)>();
        int waterSide = -1;
        float shoreAt;
        Vector2 pierA, pierB;
        readonly Dictionary<string, Vector2> spots = new Dictionary<string, Vector2>();
        System.Random rnd;

        enum Biome { Town, Harbour, Shore, Wild, Desert, Under }

        // ---- projection ---------------------------------------------------------------------------

        const float K = 0.70710678f;
        /// <summary>Ground metres (x east, y north) → the flat picture plane the camera sees.</summary>
        public static Vector2 Iso(Vector2 w) => new Vector2((w.x + w.y) * K, (w.y - w.x) * K * 0.5f);
        /// <summary>A direction on the screen → the ground direction that looks like it.</summary>
        public static Vector2 Unproject(Vector2 s)
        {
            float a = s.x / K, b = 2 * s.y / K;    // a = x + y, b = y − x
            return new Vector2((a - b) / 2, (a + b) / 2);
        }
        public Vector2 ToScreen(Vector2 w, float lift = 0)
        {
            var p = Iso(w);
            var s = cam.WorldToScreenPoint(new Vector3(p.x, p.y + lift, 0));
            return new Vector2(s.x, s.y);
        }
        static int Order(Vector2 w) => Mathf.RoundToInt(-Iso(w).y * 100);

        /// <summary>
        /// The drawing order of something standing at [p] among the houses: in front of a house when it
        /// stands south or east of it (the walls we see), behind it otherwise. Ordering by the height on
        /// the screen alone put the hero behind a house he stood in front of, and hid people at its door
        /// (owner 07.10).
        /// </summary>
        /// <summary>
        /// The order of someone who walks about: among the houses as above, then in front of every barrel,
        /// cart or tree nearby that stands farther from the camera and behind every one that stands nearer.
        /// The things' own orders were raised past the houses, so the height on the screen alone put a
        /// barrel over the hero standing in front of it (owner 07.10).
        /// </summary>
        int OrderAmongThings(Vector2 p)
        {
            int o = OrderAmongHouses(p);
            float y = Iso(p).y;
            foreach (var (at, order) in things)
            {
                if ((at - p).magnitude > 4) continue;
                bool front = y < Iso(at).y;
                if (front && o <= order) o = order + 1;
                else if (!front && o >= order) o = order - 1;
            }
            return o;
        }

        int OrderAmongHouses(Vector2 p)
        {
            int o = Order(p);
            foreach (var b in blocks)
            {
                if ((b.center - p).magnitude > 14) continue;
                int house = Order(new Vector2(b.xMax, b.yMin));
                bool front = p.y < b.yMin || p.x > b.xMax;
                if (front && o <= house) o = house + 1;
                else if (!front && o >= house) o = house - 1;
            }
            return o;
        }

        // ---- making a place -------------------------------------------------------------------------

        public void Init(Camera camera, string heroSex)
        {
            cam = camera;
            cam.orthographicSize = 5.15f;
            sex = heroSex == "f" ? "f" : "m";
        }

        /// <summary>Builds [loc]; [came] is the direction of the exit the hero walked through to get here (−1 — none).</summary>
        public void Show(LocationView loc, int came)
        {
            if (root != null) Destroy(root.gameObject);
            root = new GameObject("world").transform;
            var frame = new GameObject("ground frame").transform;
            frame.SetParent(root, false);
            frame.localScale = new Vector3(1, 0.5f, 1);
            plane = new GameObject("ground plane").transform;
            plane.SetParent(frame, false);
            plane.localRotation = Quaternion.Euler(0, 0, -45);

            rnd = new System.Random(Seed(loc.id));
            biome = BiomeOf(loc.art);
            blocks.Clear(); rounds.Clear(); things.Clear(); roads.Clear(); Exits.Clear(); spots.Clear(); people.Clear();
            leaving = false;

            // Exits with a direction leave by a road to the edge; the rest wait at doors or in a ring.
            var loose = new List<ExitView>();
            var used = new bool[8];
            foreach (var e in loc.exits)
            {
                var d = Compass.Of(loc.id, e);
                if (d < 0 || used[d]) { loose.Add(e); continue; }
                used[d] = true;
                var v = Compass.Vector(d);
                var edge = v * (Half - 2.5f);
                Exits.Add((e, edge));
                roads.Add((Vector2.zero, v * (Half + Margin)));
            }
            // Water on a side no exit uses (the shore, the harbour).
            waterSide = -1;
            if (biome == Biome.Harbour || biome == Biome.Shore)
                foreach (var side in new[] { 6, 0, 4, 2 })
                    if (!used[side] && !used[(side + 1) % 8] && !used[(side + 7) % 8]) { waterSide = side; break; }
            shoreAt = 5 + (float)rnd.NextDouble() * 3;
            if (waterSide >= 0 && biome == Biome.Harbour)
            {
                var v = Compass.Vector(waterSide);
                var side = new Vector2(-v.y, v.x) * ((float)rnd.NextDouble() * 6 - 3);
                pierA = v * (shoreAt - 1.5f) + side;
                pierB = v * (Half + Margin) + side;
            }
            else pierA = pierB = Vector2.zero;

            BakeGround();
            if (biome == Biome.Town || biome == Biome.Harbour) PlaceHouses();
            PlaceThings();
            // Doors: exits without a direction at the doors of the houses, or in a ring round the middle.
            for (int i = 0; i < loose.Count; i++)
            {
                Vector2 at;
                if (i < blocks.Count) { var b = blocks[i]; at = new Vector2(b.center.x, b.yMin - 1.2f); }
                else { var a = (i * 2.4f) + 0.8f; at = new Vector2(Mathf.Cos(a), Mathf.Sin(a)) * 6; }
                Exits.Add((loose[i], Free(at)));
            }
            foreach (var (e, at) in Exits) Ring(at, e.occupied ? Palette.Danger : Palette.Accent);

            // The hero: from the side he came in by, or in the middle.
            hero = came >= 0 ? Free(-Compass.Vector(came) * (Half - 4.5f)) : Free(Vector2.zero);
            MakeHero();
            var c = Iso(hero);
            cam.transform.position = new Vector3(c.x, c.y, -10);
        }

        static int Seed(string id)
        {
            unchecked
            {
                int h = (int)2166136261;
                foreach (var ch in id ?? "") h = (h ^ ch) * 16777619;
                return h;
            }
        }

        static Biome BiomeOf(string art)
        {
            var a = art ?? "";
            if (a.Contains("harbor") || a.Contains("pirate")) return Biome.Harbour;
            if (a.Contains("coast") || a.Contains("river") || a.Contains("island") || a.Contains("swamp")) return Biome.Shore;
            if (a.Contains("desert") || a.Contains("pyramid")) return Biome.Desert;
            if (a.Contains("cave") || a.Contains("dungeon") || a.Contains("ruins")) return Biome.Under;
            if (a.Contains("city") || a.Contains("bank") || a.Contains("shop") || a.Contains("tavern") || a.Contains("academy")
                || a.Contains("barracks") || a.Contains("stable") || a.Contains("temple") || a.Contains("house") || a.Contains("castle")
                || a.Contains("fortress")) return Biome.Town;
            return Biome.Wild;
        }

        // ---- the ground ---------------------------------------------------------------------------

        class Tex { public Color32[] px; public int size; }
        static readonly Dictionary<string, Tex> textures = new Dictionary<string, Tex>();

        static Tex Load(string name)
        {
            if (textures.TryGetValue(name, out var t)) return t;
            var bytes = Resources.Load<TextAsset>("TopDown/ground/" + name);
            if (bytes != null)
            {
                var tex = new Texture2D(2, 2);
                if (tex.LoadImage(bytes.bytes)) t = new Tex { px = tex.GetPixels32(), size = tex.width };
                Destroy(tex);
            }
            if (t == null) t = new Tex { px = new[] { new Color32(60, 50, 40, 255) }, size = 1 };
            textures[name] = t;
            return t;
        }

        static Color32 Sample(Tex t, float x, float y, float metres)
        {
            float u = x / metres, v = y / metres;
            int i = (int)((u - Mathf.Floor(u)) * t.size), j = (int)((v - Mathf.Floor(v)) * t.size);
            if (t.size == 1) return t.px[0];
            return t.px[Mathf.Clamp(j, 0, t.size - 1) * t.size + Mathf.Clamp(i, 0, t.size - 1)];
        }

        float RoadDistance(Vector2 p)
        {
            float best = 999;
            foreach (var (a, b) in roads)
            {
                var ab = b - a;
                var t = Mathf.Clamp01(Vector2.Dot(p - a, ab) / Vector2.Dot(ab, ab));
                best = Mathf.Min(best, (a + ab * t - p).magnitude);
            }
            return best;
        }

        /// <summary>How far into the water a point is (metres; below 0 — on land).</summary>
        float Wet(Vector2 p, float noise)
        {
            if (waterSide < 0) return -99;
            return Vector2.Dot(p, Compass.Vector(waterSide)) - shoreAt + (noise - 0.5f) * 3;
        }

        bool OnPier(Vector2 p, out float along, out float across)
        {
            along = across = 0;
            if (pierA == pierB) return false;
            var d = (pierB - pierA).normalized;
            var n = new Vector2(-d.y, d.x);
            along = Vector2.Dot(p - pierA, d);
            across = Vector2.Dot(p - pierA, n);
            return along > 0 && along < (pierB - pierA).magnitude && Mathf.Abs(across) < 1.3f;
        }

        void BakeGround()
        {
            string baseTex, patchTex, roadTex;
            float dark = 1;
            switch (biome)
            {
                case Biome.Town: baseTex = "ground-dirt"; patchTex = "ground-cobble-b"; roadTex = "ground-cobble"; break;
                case Biome.Harbour: baseTex = "ground-grass"; patchTex = "ground-dirt"; roadTex = "ground-cobble"; break;
                case Biome.Desert: baseTex = "ground-dirt"; patchTex = "ground-road"; roadTex = "ground-road"; break;
                case Biome.Under: baseTex = "ground-dirt"; patchTex = "ground-cobble-b"; roadTex = "ground-cobble-b"; dark = 0.55f; break;
                default: baseTex = "ground-grass"; patchTex = "ground-dirt"; roadTex = "ground-road"; break;
            }
            Tex tb = Load(baseTex), tp = Load(patchTex), tr = Load(roadTex), tw = Load("ground-water"), tk = Load("ground-planks");
            float size = Area + 2 * Margin, from = Half + Margin;
            int n = (int)(size * Px);
            const int Cell = 4;                 // masks are worked out every 4 pixels and blended between
            int m = n / Cell + 2;
            var patch = new float[m * m]; var road = new float[m * m]; var water = new float[m * m];
            float ox = (float)rnd.NextDouble() * 100, oy = (float)rnd.NextDouble() * 100;
            float roadHalf = biome == Biome.Town || biome == Biome.Harbour ? 2.2f : 1.5f;
            for (int j = 0; j < m; j++)
                for (int i = 0; i < m; i++)
                {
                    var p = new Vector2((i * Cell + 0.5f) / Px - from, (j * Cell + 0.5f) / Px - from);
                    float big = Mathf.PerlinNoise(ox + p.x * 0.09f, oy + p.y * 0.09f);
                    float fine = Mathf.PerlinNoise(ox + 40 + p.x * 0.6f, oy + 40 + p.y * 0.6f);
                    patch[j * m + i] = Mathf.Clamp01((big - 0.58f) * 3.5f) * 0.85f;
                    road[j * m + i] = Mathf.Clamp01((roadHalf + (fine - 0.5f) * 0.9f - RoadDistance(p)) / 0.6f);
                    water[j * m + i] = Wet(p, big);
                }
            var px = new Color32[n * n];
            for (int y = 0; y < n; y++)
            {
                float fy = (float)y / Cell; int j0 = (int)fy; float ty = fy - j0;
                for (int x = 0; x < n; x++)
                {
                    float fx = (float)x / Cell; int i0 = (int)fx; float tx = fx - i0;
                    int k = j0 * m + i0;
                    float pa = Lerp2(patch, k, m, tx, ty), ro = Lerp2(road, k, m, tx, ty), we = Lerp2(water, k, m, tx, ty);
                    float wx = (x + 0.5f) / Px - from, wy = (y + 0.5f) / Px - from;
                    var c = (Color)Sample(tb, wx, wy, 4.5f);
                    if (pa > 0) c = Color.Lerp(c, Sample(tp, wx, wy, 3.6f), pa);
                    if (ro > 0) c = Color.Lerp(c, Sample(tr, wx, wy, 3.2f), ro);
                    if (we > -1.2f)
                    {
                        float wetBand = Mathf.Clamp01(1 - Mathf.Abs(we + 0.4f) / 1.2f) * 0.5f;
                        c = Color.Lerp(c, c * 0.4f, wetBand);
                        float into = Mathf.Clamp01(we / 0.8f);
                        if (into > 0) c = Color.Lerp(c, (Color)Sample(tw, wx, wy, 6f) * 0.75f, into);
                    }
                    if (OnPier(new Vector2(wx, wy), out var along, out var across))
                        c = Sample(tk, across + 1.3f, along, 2.6f);
                    // beyond the square the ground darkens into the night
                    float outside = Mathf.Max(Mathf.Abs(wx), Mathf.Abs(wy)) - Half;
                    c *= dark * (outside > 0 ? Mathf.Lerp(1, 0.15f, Mathf.Clamp01(outside / Margin)) : 1);
                    c.a = 1;
                    px[y * n + x] = c;
                }
            }
            var tex = new Texture2D(n, n, TextureFormat.RGBA32, false) { filterMode = FilterMode.Bilinear, wrapMode = TextureWrapMode.Clamp };
            tex.SetPixels32(px);
            tex.Apply(false, true);
            var go = new GameObject("ground");
            go.transform.SetParent(plane, false);
            var sr = go.AddComponent<SpriteRenderer>();
            sr.sprite = Sprite.Create(tex, new Rect(0, 0, n, n), new Vector2(0.5f, 0.5f), Px);
            sr.sortingOrder = -32000;
        }

        static float Lerp2(float[] a, int k, int m, float tx, float ty)
        {
            float top = a[k] + (a[k + 1] - a[k]) * tx, bottom = a[k + m] + (a[k + m + 1] - a[k + m]) * tx;
            return top + (bottom - top) * ty;
        }

        // ---- houses ---------------------------------------------------------------------------------

        class ObjMeta { public float corner, baseL, baseR, left, right, along, size, solid; public List<string> where = new List<string>(); public string near; }
        class FigMeta { public float foot, feet = 0.3f, rise, lift, height = 2.4f, fill = 1; }
        class Meta { public Dictionary<string, ObjMeta> objects = new Dictionary<string, ObjMeta>(); public Dictionary<string, FigMeta> figures = new Dictionary<string, FigMeta>(); }
        static Meta meta;
        static Meta TheMeta()
        {
            if (meta != null) return meta;
            var json = Resources.Load<TextAsset>("TopDown/meta");
            meta = json != null ? Newtonsoft.Json.JsonConvert.DeserializeObject<Meta>(json.text) : new Meta();
            return meta;
        }

        static readonly Dictionary<string, Sprite> sprites = new Dictionary<string, Sprite>();
        static Sprite Picture(string path, float pivotX, float ppu, float pivotY = 0)
        {
            var key = path + "|" + pivotX + "|" + ppu + "|" + pivotY;
            if (sprites.TryGetValue(key, out var s)) return s;
            var tex = Resources.Load<Texture2D>("TopDown/" + path);
            s = tex == null ? null : Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(pivotX, pivotY), ppu);
            sprites[key] = s;
            return s;
        }

        void PlaceHouses()
        {
            var kinds = new List<string>();
            foreach (var k in TheMeta().objects.Keys) if (k.StartsWith("obj-house")) kinds.Add(k);
            if (kinds.Count == 0) return;
            int placed = 0;
            for (int attempt = 0; attempt < 60 && placed < 7; attempt++)
            {
                var name = kinds[rnd.Next(kinds.Count)];
                var m = TheMeta().objects[name];
                float along = m.along > 0 ? m.along : 7;
                float across = Mathf.Clamp(along * (m.baseR - m.corner) / Mathf.Max(0.01f, m.corner - m.baseL), along * 0.45f, along * 1.2f);
                bool mirror = rnd.Next(2) == 1;           // mirrored, the front faces east instead of south
                float w = mirror ? across : along, d = mirror ? along : across;
                var c = new Vector2((float)rnd.NextDouble() * (Area - 8) - Half + 4, (float)rnd.NextDouble() * (Area - 8) - Half + 4);
                var r = new Rect(c.x - w / 2, c.y - d / 2, w, d);
                if (!Clear(r)) continue;
                // Near a road, not on it: houses line the streets.
                if (RoadDistance(c) > Mathf.Max(w, d) * 0.5f + 5) continue;
                blocks.Add(r);
                placed++;
                // The picture: its front (south-east) corner stands on the rect's south-east corner.
                var corner = new Vector2(r.xMax, r.yMin);
                float span = (m.baseR - m.baseL);
                var tex = Resources.Load<Texture2D>("TopDown/objects/" + name);
                if (tex == null) continue;
                float ppu = span * tex.width / ((along + across) * K);
                var go = new GameObject(name);
                go.transform.SetParent(root, false);
                var p = Iso(corner);
                go.transform.localPosition = new Vector3(p.x, p.y, 0);
                var sr = go.AddComponent<SpriteRenderer>();
                sr.sprite = Picture("objects/" + name, mirror ? 1 - m.corner : m.corner, ppu);
                sr.flipX = mirror;
                sr.sortingOrder = Order(corner);
                Shadow(new Rect(r.x + 0.9f, r.y - 0.5f, r.width + 0.4f, r.height + 0.2f), 0.55f);
            }
        }

        bool Clear(Rect r)
        {
            if (r.xMin < -Half + 1 || r.yMin < -Half + 1 || r.xMax > Half - 1 || r.yMax > Half - 1) return false;
            var c = r.center;
            if (c.magnitude < Mathf.Max(r.width, r.height) / 2 + 3) return false;                 // the middle stays free
            foreach (var b in blocks) { var g = new Rect(b.x - 1.5f, b.y - 1.5f, b.width + 3, b.height + 3); if (g.Overlaps(r)) return false; }
            // Off the roads: the nearest road must be farther than the rect's half-diagonal.
            foreach (var corner in new[] { r.min, r.max, new Vector2(r.xMin, r.yMax), new Vector2(r.xMax, r.yMin), c })
                if (RoadDistance(corner) < 2.8f) return false;
            foreach (var corner in new[] { r.min, r.max, new Vector2(r.xMin, r.yMax), new Vector2(r.xMax, r.yMin) })
                if (Wet(corner, 0.5f) > -1.5f) return false;
            foreach (var (_, at) in Exits) if ((at - c).magnitude < 4) return false;
            return true;
        }

        // ---- things ---------------------------------------------------------------------------------

        static string BiomeName(Biome b) => b == Biome.Town ? "town" : b == Biome.Harbour ? "harbour" : b == Biome.Shore ? "shore"
            : b == Biome.Desert ? "desert" : b == Biome.Under ? "under" : "wild";

        void PlaceThings()
        {
            var here = BiomeName(biome);
            var kinds = new List<string>();
            foreach (var kv in TheMeta().objects) if (kv.Value.size > 0 && kv.Value.where != null && kv.Value.where.Contains(here)) kinds.Add(kv.Key);
            if (kinds.Count == 0) return;
            int want = biome == Biome.Wild ? 16 : biome == Biome.Desert || biome == Biome.Under ? 7 : 11;
            int placed = 0;
            float roadHalf = biome == Biome.Town || biome == Biome.Harbour ? 2.2f : 1.5f;
            // Moorings along both edges of the pier first.
            if (pierA != pierB && kinds.Contains("obj-mooring"))
            {
                var d = (pierB - pierA).normalized; var n = new Vector2(-d.y, d.x);
                float len = (pierB - pierA).magnitude;
                for (float t = 1.5f; t < len - 3; t += 4.5f)
                    foreach (var side in new[] { -1.15f, 1.15f }) Thing("obj-mooring", pierA + d * t + n * side);
                // and a boat tied beside it
                if (TheMeta().objects.ContainsKey("obj-boat")) Thing("obj-boat", pierA + d * Mathf.Min(6, len / 2) + n * 3.2f);
            }
            for (int attempt = 0; attempt < 200 && placed < want; attempt++)
            {
                var name = kinds[rnd.Next(kinds.Count)];
                var m = TheMeta().objects[name];
                if (name == "obj-mooring" || name == "obj-boat" && pierA != pierB) continue;
                Vector2 p;
                switch (m.near)
                {
                    case "road":
                        if (roads.Count == 0) continue;
                        var (a, b) = roads[rnd.Next(roads.Count)];
                        var dir = (b - a).normalized; var nrm = new Vector2(-dir.y, dir.x);
                        p = a + dir * (3 + (float)rnd.NextDouble() * (Half - 5)) + nrm * ((rnd.Next(2) * 2 - 1) * (roadHalf + 0.6f + m.solid + (float)rnd.NextDouble()));
                        break;
                    case "water":
                        if (waterSide < 0) continue;
                        var v = Compass.Vector(waterSide);
                        p = v * (shoreAt + 1.5f + (float)rnd.NextDouble() * 2) + new Vector2(-v.y, v.x) * ((float)rnd.NextDouble() * 20 - 10);
                        break;
                    default:
                        p = new Vector2((float)rnd.NextDouble() * (Area - 4) - Half + 2, (float)rnd.NextDouble() * (Area - 4) - Half + 2);
                        if (RoadDistance(p) < roadHalf + 0.6f + m.solid) continue;
                        break;
                }
                if (m.near != "water" && !Room(p, Mathf.Max(m.solid, 0.5f))) continue;
                if (m.near == "water" && (Wet(p, 0.5f) < 0.8f || OnPier(p, out _, out _))) continue;
                Thing(name, p);
                placed++;
            }
        }

        bool Room(Vector2 p, float r)
        {
            if (Mathf.Abs(p.x) > Half - 1 || Mathf.Abs(p.y) > Half - 1) return false;
            if (p.magnitude < r + 2.5f) return false;
            if (Wet(p, 0.5f) > -0.8f && !OnPier(p, out _, out _)) return false;
            foreach (var bl in blocks) if (new Rect(bl.x - r - 0.6f, bl.y - r - 0.6f, bl.width + 2 * r + 1.2f, bl.height + 2 * r + 1.2f).Contains(p)) return false;
            foreach (var (at, rr) in rounds) if ((p - at).magnitude < r + rr + 0.9f) return false;
            foreach (var (_, at) in Exits) if ((at - p).magnitude < Reach + r + 1) return false;
            return true;
        }

        void Thing(string name, Vector2 p)
        {
            var m = TheMeta().objects[name];
            var tex = Resources.Load<Texture2D>("TopDown/objects/" + name);
            if (tex == null) return;
            var go = new GameObject(name);
            go.transform.SetParent(root, false);
            var at = Iso(p);
            go.transform.localPosition = new Vector3(at.x, at.y, 0);
            var sr = go.AddComponent<SpriteRenderer>();
            bool mirror = rnd.Next(2) == 1 && name != "obj-boat";
            sr.sprite = Picture("objects/" + name, mirror ? 1 - m.corner : m.corner, tex.width * Mathf.Max(0.05f, m.right - m.left) / m.size);
            sr.flipX = mirror;
            sr.sortingOrder = OrderAmongHouses(p);
            things.Add((p, sr.sortingOrder));
            if (m.solid > 0) rounds.Add((p, m.solid));
            float s = m.size * 0.55f;
            if (name != "obj-boat") Shadow(new Rect(p.x + s * 0.15f, p.y - s * 0.35f, s, s * 0.8f), 0.45f);
        }

        // ---- people and monsters ----------------------------------------------------------------------

        /// <summary>Stands the figure of a monster or a person at their spot; false if it has no figure drawn yet.</summary>
        public bool Figure(string id, string art, bool undead)
        {
            if (people.TryGetValue(id, out var have)) return have != null;
            var key = "fig-" + (art ?? "").Substring((art ?? "").LastIndexOf('/') + 1);
            TheMeta().figures.TryGetValue(key, out var m);
            var tex = m != null ? Resources.Load<Texture2D>("TopDown/figures/" + key) : null;
            if (tex == null) { people[id] = null; return false; }
            var go = new GameObject("figure " + id);
            go.transform.SetParent(root, false);
            var sr = go.AddComponent<SpriteRenderer>();
            float ppu = tex.height * m.fill / m.height;
            sr.sprite = Picture("figures/" + key, m.foot, ppu, m.lift);
            if (undead) sr.color = new Color(0.7f, 0.85f, 0.7f);
            var spot = Spot(id);
            var at = Iso(spot);
            go.transform.localPosition = new Vector3(at.x, at.y, 0);
            sr.sortingOrder = OrderAmongThings(spot);
            var shade = new GameObject("shadow");
            shade.transform.SetParent(go.transform, false);
            float w = Mathf.Clamp(m.feet * tex.width / ppu + 0.5f, 0.6f, 2.6f);
            shade.transform.localScale = new Vector3(w, Mathf.Max(w * 0.4f, m.rise * tex.height / ppu + 0.25f), 1);
            shade.transform.localPosition = new Vector3(0.08f, 0, 0);
            var ss = shade.AddComponent<SpriteRenderer>();
            ss.sprite = Art.CircleSprite;
            ss.color = new Color(0, 0, 0, 0.45f);
            ss.sortingOrder = -30000;
            people[id] = sr;
            return true;
        }

        /// <summary>How tall a monster's or a person's drawn figure is, in metres (0 — none).</summary>
        public float FigureHeight(string art)
        {
            var key = "fig-" + (art ?? "").Substring((art ?? "").LastIndexOf('/') + 1);
            return TheMeta().figures.TryGetValue(key, out var m) ? m.height : 0;
        }

        /// <summary>Canvas pixels in a metre on the screen (the canvas is 1080 tall, the camera shows 2 × its size).</summary>
        public float PixelsPerMetre => 1080f / (2 * cam.orthographicSize);

        /// <summary>A monster or a person is gone (killed, left): their figure fades away.</summary>
        public void Drop(string id)
        {
            if (!people.TryGetValue(id, out var sr)) return;
            people.Remove(id);
            if (sr != null) StartCoroutine(FadeOut(sr));
        }

        System.Collections.IEnumerator FadeOut(SpriteRenderer sr)
        {
            for (float t = 0; t < 0.8f && sr != null; t += Time.deltaTime)
            {
                var c = sr.color; c.a = 1 - t / 0.8f; sr.color = c;
                yield return null;
            }
            if (sr != null) Destroy(sr.gameObject);
        }

        /// <summary>A blow landed: the figure flashes red and shakes.</summary>
        public void Hurt(string id)
        {
            if (people.TryGetValue(id, out var sr) && sr != null) StartCoroutine(Flash(sr));
        }

        System.Collections.IEnumerator Flash(SpriteRenderer sr)
        {
            var home = sr.transform.localPosition;
            var color = sr.color;
            for (float t = 0; t < 0.3f && sr != null; t += Time.deltaTime)
            {
                float k = 1 - t / 0.3f;
                sr.color = Color.Lerp(color, new Color(1, 0.3f, 0.25f), k);
                sr.transform.localPosition = home + new Vector3(UnityEngine.Random.Range(-1f, 1f), 0, 0) * 0.08f * k;
                yield return null;
            }
            if (sr != null) { sr.color = color; sr.transform.localPosition = home; }
        }

        // ---- marks on the ground --------------------------------------------------------------------

        static Sprite soft;
        static Sprite Soft()
        {
            if (soft != null) return soft;
            const int S = 64;
            var tex = new Texture2D(S, S, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Clamp };
            var px = new Color32[S * S];
            for (int y = 0; y < S; y++)
                for (int x = 0; x < S; x++)
                {
                    float dx = Mathf.Abs(x + 0.5f - S / 2f) / (S / 2f), dy = Mathf.Abs(y + 0.5f - S / 2f) / (S / 2f);
                    float d = Mathf.Max(dx, dy);
                    px[y * S + x] = new Color32(255, 255, 255, (byte)(255 * Mathf.Clamp01((1 - d) * 3)));
                }
            tex.SetPixels32(px); tex.Apply();
            return soft = Sprite.Create(tex, new Rect(0, 0, S, S), new Vector2(0.5f, 0.5f), S);
        }

        /// <summary>A soft dark patch on the ground (in ground metres).</summary>
        void Shadow(Rect r, float strength)
        {
            var go = new GameObject("shadow");
            go.transform.SetParent(plane, false);
            go.transform.localPosition = new Vector3(r.center.x, r.center.y, 0);
            go.transform.localScale = new Vector3(r.width, r.height, 1);
            var sr = go.AddComponent<SpriteRenderer>();
            sr.sprite = Soft();
            sr.color = new Color(0, 0, 0, strength);
            sr.sortingOrder = -31000;
        }

        void Ring(Vector2 at, Color color)
        {
            var go = new GameObject("exit");
            go.transform.SetParent(plane, false);
            go.transform.localPosition = new Vector3(at.x, at.y, 0);
            go.transform.localScale = new Vector3(Reach * 2, Reach * 2, 1);
            var sr = go.AddComponent<SpriteRenderer>();
            sr.sprite = Art.CircleSprite;
            sr.color = new Color(color.r, color.g, color.b, 0.35f);
            sr.sortingOrder = -30500;
        }

        // ---- the hero -----------------------------------------------------------------------------

        void MakeHero()
        {
            var go = new GameObject("hero");
            go.transform.SetParent(root, false);
            heroPicture = go.AddComponent<SpriteRenderer>();
            var name = "fig-hero-" + sex;
            TheMeta().figures.TryGetValue(name, out var m);
            if (m == null) { name = "fig-hero-m"; TheMeta().figures.TryGetValue(name, out m); }
            var tex = Resources.Load<Texture2D>("TopDown/figures/" + name);
            float heroPpu = tex != null ? tex.height * (m != null ? m.fill : 1) / (m != null ? m.height : HeroHeight) : 100;
            if (tex != null) heroPicture.sprite = Picture("figures/" + name, m != null ? m.foot : 0.5f, heroPpu, m != null ? m.lift : 0);
            var shade = new GameObject("shadow");
            shade.transform.SetParent(go.transform, false);
            float feet = tex != null && m != null ? Mathf.Clamp(m.feet * tex.width / heroPpu + 0.5f, 0.8f, 1.6f) : 1;
            float deep = tex != null && m != null ? m.rise * tex.height / heroPpu + 0.25f : 0.4f;
            shade.transform.localScale = new Vector3(feet, Mathf.Max(feet * 0.4f, deep), 1);
            shade.transform.localPosition = new Vector3(0.08f, 0, 0);
            var ss = shade.AddComponent<SpriteRenderer>();
            ss.sprite = Art.CircleSprite;
            ss.color = new Color(0, 0, 0, 0.45f);
            ss.sortingOrder = -30000;
            look = Vector2.zero;
            Place();
        }

        void Place()
        {
            var p = Iso(hero);
            var t = heroPicture.transform;
            t.localPosition = new Vector3(p.x, p.y, 0);
            heroPicture.sortingOrder = OrderAmongThings(hero);
            float bob = Mathf.Abs(Mathf.Sin(walk * 9)) * 0.05f;
            t.localScale = new Vector3(1, 1 + bob, 1);
        }

        void Update()
        {
            if (heroPicture == null) return;
            var pull = Pull.magnitude > 1 ? Pull.normalized : Pull;
            if (pull.magnitude > 0.15f && !leaving)
            {
                var dir = Unproject(pull).normalized;
                var step = dir * Speed * pull.magnitude * Time.deltaTime;
                var next = Collide(hero + step);
                if ((next - hero).magnitude > 0.0001f) walk += Time.deltaTime;
                hero = next;
                if (Mathf.Abs(pull.x) > 0.2f) heroPicture.flipX = pull.x < 0;
                foreach (var (e, at) in Exits)
                    if ((hero - at).magnitude < Reach * 0.8f) { leaving = true; Leave?.Invoke(e); break; }
            }
            else walk = 0;
            Place();
            foreach (var kv in people)
                if (kv.Value != null && spots.TryGetValue(kv.Key, out var sp)) kv.Value.flipX = Iso(hero).x < Iso(sp).x;
            var c = Iso(hero);
            var at3 = cam.transform.position;
            var want = new Vector3(c.x, c.y, -10);
            cam.transform.position = Vector3.Lerp(at3, want, Mathf.Min(1, Time.deltaTime * 6));
        }

        /// <summary>The server refused to let the hero through (a locked way): let him walk again.</summary>
        public void Stay() => leaving = false;

        Vector2 Collide(Vector2 p)
        {
            p.x = Mathf.Clamp(p.x, -Half + 0.5f, Half - 0.5f);
            p.y = Mathf.Clamp(p.y, -Half + 0.5f, Half - 0.5f);
            foreach (var b in blocks)
            {
                var g = new Rect(b.x - HouseGap, b.y - HouseGap, b.width + 2 * HouseGap, b.height + 2 * HouseGap);
                if (!g.Contains(p)) continue;
                float l = p.x - g.xMin, r = g.xMax - p.x, d = p.y - g.yMin, u = g.yMax - p.y;
                float min = Mathf.Min(Mathf.Min(l, r), Mathf.Min(d, u));
                if (min == l) p.x = g.xMin; else if (min == r) p.x = g.xMax; else if (min == d) p.y = g.yMin; else p.y = g.yMax;
            }
            foreach (var (at, r) in rounds)
            {
                var d = p - at; float need = r + HeroRadius;
                if (d.magnitude < need) p = at + (d.magnitude > 0.001f ? d.normalized : Vector2.up) * need;
            }
            if (Wet(p, 0.5f) > 0.3f && !OnPier(p, out _, out _)) return hero;
            return p;
        }

        /// <summary>A free spot near [p]: off houses and water.</summary>
        Vector2 Free(Vector2 p)
        {
            for (int i = 0; i < 40; i++)
            {
                bool ok = Wet(p, 0.5f) < -0.5f || OnPier(p, out _, out _);
                foreach (var b in blocks) if (new Rect(b.x - 0.8f, b.y - 0.8f, b.width + 1.6f, b.height + 1.6f).Contains(p)) ok = false;
                foreach (var (at, r) in rounds) if ((p - at).magnitude < r + 0.8f) ok = false;
                if (ok) return p;
                p = p * 0.85f + new Vector2((float)rnd.NextDouble() - 0.5f, (float)rnd.NextDouble() - 0.5f);
            }
            return p;
        }

        /// <summary>Where a monster or a person of the place stands (the same spot while they stay).</summary>
        public Vector2 Spot(string id)
        {
            if (spots.TryGetValue(id, out var s)) return s;
            var r = new System.Random(Seed(id));
            var a = (float)r.NextDouble() * Mathf.PI * 2;
            var d = 3.5f + (float)r.NextDouble() * 4;
            s = Free(hero + new Vector2(Mathf.Cos(a), Mathf.Sin(a)) * d);
            spots[id] = s;
            return s;
        }

        void OnDestroy() { if (root != null) Destroy(root.gameObject); }
    }
}
