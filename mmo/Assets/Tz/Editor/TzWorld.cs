using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace Tz.World
{
    /// <summary>
    /// The plan of a map made from the old game's places (content/locations): no Unity here, so that
    /// mmo/tools/world can run it and draw it without the editor; TzMap turns the plan into the scene.
    ///
    /// Every outdoor place of the old map with coordinates (x&lt;X&gt;x&lt;Y&gt;, the old world map, Y grows to
    /// the south) stands at its coordinates × (SX, SZ) metres. The old exits between places become paths;
    /// a place is a small clearing on them. What lies off the paths is filled by the kind of the nearest place:
    /// houses in a town, trees in a forest, water at a lake or the shore, graves at a cemetery, rocks in a
    /// ravine. Shops, taverns and houses one enters (indoor places) are buildings at the edge of the
    /// street they open to; their people stand at the door. Monsters come where the old game respawned them.
    /// Exits to places beyond the map end in a sign (portals to the next maps later).
    /// </summary>
    public class TzWorld
    {
        // ---- the plan -----------------------------------------------------------------------------------
        public enum Kind { Town, Pier, Shore, Lake, Forest, Road, Swamp, Cemetery, Rocks, Field }
        public enum Surface { Grass, ForestFloor, Dirt, Sand, Swamp, Cobble, Planks, Road, Water }

        public struct V { public double X, Z; public V(double x, double z) { X = x; Z = z; }
            public static V operator +(V a, V b) => new V(a.X + b.X, a.Z + b.Z);
            public static V operator -(V a, V b) => new V(a.X - b.X, a.Z - b.Z);
            public static V operator *(V a, double k) => new V(a.X * k, a.Z * k);
            public double Len => Math.Sqrt(X * X + Z * Z);
            public V Norm => Len < 1e-9 ? new V(0, 0) : this * (1 / Len);
            public override string ToString() => $"({X:0.0}, {Z:0.0})"; }

        public class Place
        {
            public string Id, Name;
            public Kind Kind;
            public V Pos;
            public bool Indoor;
            /// <summary>An indoor place: the building it is in.</summary>
            public Building Building;
            public Dictionary<string, object> Data;
        }

        public class Way { public Place A, B; public double Width; public Surface Surface; }

        public class Building
        {
            public V Pos, Door;
            /// <summary>Degrees around Y; the door looks along +Z turned by it.</summary>
            public double Rot, W, D, H;
            public string Name, Kind;
            public Place Street;
            public List<Place> Rooms = new List<Place>();
        }

        public class Prop { public string Kind; public V Pos; public double Rot, Scale; }

        public class Npc { public string Template, Name; public V Pos; public double Rot; public Place Place; }

        public class Spawn { public string Template; public V Pos; public int Count; public double RespawnMin, RespawnMax; public Place Place; }

        public class ExitSign { public V Pos; public double Rot; public Place From; public string Target, TargetName; }

        public const double SX = 1.25, SZ = 1.5;
        /// <summary>The ground grid's cell, metres.</summary>
        public const double Cell = 4;

        public string MapName;
        public List<Place> Places = new List<Place>();
        public List<Way> Paths = new List<Way>();
        public List<Building> Buildings = new List<Building>();
        public List<Prop> Props = new List<Prop>();
        public List<Npc> Npcs = new List<Npc>();
        public List<Spawn> Spawns = new List<Spawn>();
        public List<ExitSign> Exits = new List<ExitSign>();
        public V Start;
        /// <summary>The ground: MinX/MinZ and cells of Cell metres, [i, j] — i along X, j along Z.</summary>
        public double MinX, MinZ, MaxX, MaxZ;
        public Surface[,] Ground;
        public List<string> Problems = new List<string>();

        // ---- the maps ------------------------------------------------------------------------------------
        /// <summary>The maps cut from the old world map: name, the start place, the rectangle of old coordinates.</summary>
        public static readonly (string name, string start, int x0, int y0, int x1, int y1)[] Maps =
        {
            ("Город", "_begin", 900, 350, 1400, 750),
        };

        // ---- reading -------------------------------------------------------------------------------------
        static readonly Regex Coord = new Regex(@"^x(\d+)x(\d+)$");
        static readonly Regex Entering = new Regex(@"^(войти|зайти|в дом|в таверн|в магазин|магазин|дверь|здание|спуститься|подняться|на второй|в комнат|в подвал|в конюшн|в казарм|в банк|в академ)");
        static readonly Regex BuildingName = new Regex(@"(магазин|таверн|дом|комнат|банк|академ|казарм|конюшн|каморк|особняк|здани|склад|подвал|зал|этаж|кузниц|храм|церк|лавк|мастерск|оружейн)", RegexOptions.IgnoreCase);
        static readonly (string dir, V v)[] Directions =
        {
            ("на север", new V(0, 1)), ("на юг", new V(0, -1)), ("на запад", new V(-1, 0)), ("на восток", new V(1, 0)),
        };

        public static Kind KindOf(string name)
        {
            string n = (name ?? "").ToLowerInvariant();
            bool Has(params string[] w) => w.Any(n.Contains);
            if (Has("пристан", "причал")) return Kind.Pier;
            if (Has("озер", "пруд")) return Kind.Lake;
            if (Has("берег", "пляж", "побереж", "у реки", "река", "остров")) return Kind.Shore;
            if (Has("болот", "топь")) return Kind.Swamp;
            if (Has("кладбищ", "курган", "склеп", "могил")) return Kind.Cemetery;
            if (Has("овраг", "ущель", "горы", "гора", "пещер", "рудник", "скал", "предгор")) return Kind.Rocks;
            if (Has("лес", "роща", "чащ", "тропа", "тропинк", "аллея")) return Kind.Forest;
            if (Has("улиц", "квартал", "район", "площад", "переул", "двор", "ворот", "перед ", "город", "стена",
                    "ристалищ", "казарм", "особняк", "замок", "капитол", "цитадел", "крепост", "рынок", "мост", "порт", "склад"))
                return Kind.Town;
            if (Has("дорог", "тракт", "путь")) return Kind.Road;
            return Kind.Field;
        }

        static IEnumerable<(string label, string target)> ExitsOf(Dictionary<string, object> d) =>
            d.TryGetValue("exits", out var e) && e is List<object> list
                ? list.OfType<Dictionary<string, object>>()
                    .Select(x => (x.TryGetValue("label", out var l) ? l as string ?? "" : "", x.TryGetValue("target", out var t) ? t as string : null))
                    .Where(x => x.Item2 != null)
                : Enumerable.Empty<(string, string)>();

        static string Name(Dictionary<string, object> d) => d.TryGetValue("name", out var n) ? n as string ?? "" : "";

        /// <summary>A stable hash (string.GetHashCode differs between runs in .NET).</summary>
        public static int Hash(string s)
        {
            unchecked
            {
                uint h = 2166136261;
                foreach (char c in s) { h ^= c; h *= 16777619; }
                return (int)(h & 0x7fffffff);
            }
        }

        /// <summary>
        /// Builds the plan of map [mapIndex] from content/ (locations, npcs balance for who is a monster).
        /// [monster] tells which NPC templates are monsters or animals.
        /// </summary>
        public static TzWorld Build(string root, int mapIndex, Func<string, bool> monster)
        {
            var map = Maps[mapIndex];
            var w = new TzWorld { MapName = map.name };
            var all = new Dictionary<string, Dictionary<string, object>>();
            foreach (var f in Directory.GetFiles(Path.Combine(root, "content", "locations"), "*.json"))
                if (TzJsonReader(File.ReadAllText(f)) is Dictionary<string, object> d && d.TryGetValue("id", out var id) && id is string s)
                    all[s] = d;
            w.Read(all, map, monster);
            w.Lay();
            return w;
        }

        /// <summary>The JSON reader (TzJson in the game, the same file in mmo/tools/world).</summary>
        static object TzJsonReader(string text) => Tz.Rules.TzJson.Parse(text);

        static bool TryCoord(string id, out double x, out double y)
        {
            var m = Coord.Match(id);
            x = y = 0;
            if (!m.Success) return false;
            x = double.Parse(m.Groups[1].Value);
            y = double.Parse(m.Groups[2].Value);
            return true;
        }

        readonly Dictionary<string, Place> byId = new Dictionary<string, Place>();
        Dictionary<string, Dictionary<string, object>> all;
        double cx, cy;

        V At(double x, double y) => new V((x - cx) * SX, (cy - y) * SZ);

        void Read(Dictionary<string, Dictionary<string, object>> locations, (string name, string start, int x0, int y0, int x1, int y1) map, Func<string, bool> monster)
        {
            all = locations;
            // Places one enters: the target of an entering exit, named like a building.
            var indoor = new HashSet<string>();
            foreach (var kv in all)
                foreach (var (label, target) in ExitsOf(kv.Value))
                    if (Entering.IsMatch(label) && all.TryGetValue(target, out var t) && BuildingName.IsMatch(Name(t)))
                        indoor.Add(target);
            // Rooms further in (stairs, back rooms) of a building are indoor too.
            for (bool more = true; more;)
            {
                more = false;
                foreach (var id in indoor.ToList())
                    foreach (var (label, target) in ExitsOf(all[id]))
                        if (!indoor.Contains(target) && all.TryGetValue(target, out var t) && BuildingName.IsMatch(Name(t))
                            && !Directions.Any(d => label.StartsWith(d.dir)) && !label.StartsWith("выйти"))
                        { indoor.Add(target); more = true; }
            }

            // The centre: the start place's coordinates or the middle of the rectangle.
            cx = (map.x0 + map.x1) / 2.0; cy = (map.y0 + map.y1) / 2.0;

            // Outdoor places with coordinates in the rectangle.
            foreach (var kv in all)
            {
                if (indoor.Contains(kv.Key) || !TryCoord(kv.Key, out var x, out var y)) continue;
                if (x < map.x0 || x > map.x1 || y < map.y0 || y > map.y1) continue;
                Add(kv.Key, kv.Value, false).Pos = At(x, y);
            }
            // Outdoor places without coordinates (the start lane) next to them by the directions of their exits.
            foreach (var kv in all)
            {
                if (byId.ContainsKey(kv.Key) || indoor.Contains(kv.Key) || TryCoord(kv.Key, out _, out _)) continue;
                var guesses = new List<V>();
                foreach (var (label, target) in ExitsOf(kv.Value))
                {
                    var dir = Directions.FirstOrDefault(d => label.StartsWith(d.dir));
                    if (dir.dir != null && byId.TryGetValue(target, out var p) && !p.Indoor)
                        guesses.Add(p.Pos - new V(dir.v.X * 38 * SX, dir.v.Z * 27 * SZ));
                }
                if (guesses.Count < 2) continue;
                Add(kv.Key, kv.Value, false).Pos = new V(guesses.Average(g => g.X), guesses.Average(g => g.Z));
            }
            // The start: the start place, else the middle.
            Start = byId.TryGetValue(map.start, out var sp) ? sp.Pos : new V(0, 0);
            if (!byId.ContainsKey(map.start)) Problems.Add("no start place " + map.start);

            // Buildings: groups of indoor places joined by their exits; the street is the outdoor place they open to.
            var seen = new HashSet<string>();
            foreach (var id in indoor.OrderBy(x => x))
            {
                if (seen.Contains(id)) continue;
                var group = new List<string>();
                var queue = new Queue<string>(new[] { id });
                seen.Add(id);
                Place street = null;
                string door = null;
                while (queue.Count > 0)
                {
                    var g = queue.Dequeue();
                    group.Add(g);
                    foreach (var (label, target) in ExitsOf(all[g]))
                    {
                        if (indoor.Contains(target)) { if (seen.Add(target)) queue.Enqueue(target); }
                        else if (street == null && byId.TryGetValue(target, out var st) && !st.Indoor) { street = st; door = g; }
                    }
                }
                if (street == null) continue;   // a building of another map
                var b = new Building { Street = street, Name = Name(all[door]) };
                foreach (var g in group)
                {
                    var p = Add(g, all[g], true);
                    p.Building = b;
                    b.Rooms.Add(p);
                }
                // The door room first: its people stand at the door.
                b.Rooms.Sort((p, q) => (p.Id == door ? 0 : 1).CompareTo(q.Id == door ? 0 : 1));
                b.Kind = BuildingKind(b.Name);
                Buildings.Add(b);
                // Towards where the old map has it.
                if (TryCoord(door, out var dx, out var dy)) b.Pos = At(dx, dy);
                else b.Pos = street.Pos;
            }

            // Paths along the old exits between outdoor places; exits out of the map end in a sign.
            var pairs = new HashSet<(string, string)>();
            foreach (var p in Places.Where(p => !p.Indoor))
                foreach (var (label, target) in ExitsOf(p.Data))
                {
                    if (byId.TryGetValue(target, out var q))
                    {
                        if (q.Indoor || q == p) continue;
                        var key = string.CompareOrdinal(p.Id, q.Id) < 0 ? (p.Id, q.Id) : (q.Id, p.Id);
                        if (!pairs.Add(key)) continue;
                        Paths.Add(new Way { A = p, B = q, Width = PathWidth(p.Kind, q.Kind), Surface = PathSurface(p.Kind, q.Kind) });
                    }
                    else if (all.TryGetValue(target, out var t) && !indoor.Contains(target))
                    {
                        V dir;
                        if (TryCoord(target, out var tx, out var ty)) dir = (At(tx, ty) - p.Pos).Norm;
                        else { var d = Directions.FirstOrDefault(x => label.StartsWith(x.dir)); dir = d.dir != null ? d.v : new V(0, 0); }
                        if (dir.Len < 0.5) continue;
                        var end = p.Pos + dir * 16;
                        var stub = new Place { Id = "→" + target, Name = Name(t), Kind = p.Kind, Pos = end, Data = new Dictionary<string, object>() };
                        Paths.Add(new Way { A = p, B = stub, Width = PathWidth(p.Kind, p.Kind), Surface = PathSurface(p.Kind, p.Kind) });
                        Exits.Add(new ExitSign { Pos = end, Rot = Deg(dir), From = p, Target = target, TargetName = Name(t) });
                    }
                }

            // People and monsters of the places (the old world's starting state).
            foreach (var p in Places)
            {
                if (!(p.Data.TryGetValue("objects", out var o) && o is Dictionary<string, object> objects)) continue;
                foreach (var kv in objects)
                {
                    if (!kv.Key.StartsWith("n.") || !(kv.Value is Dictionary<string, object> npc)) continue;
                    string template = Template(kv.Key, monster);
                    string name = npc.TryGetValue("char", out var c) && c is Dictionary<string, object> ch && ch.TryGetValue("name", out var nm) ? nm as string : null;
                    if (monster(template))
                    {
                        // "respawn": "place:min:max" — where and how soon it comes back.
                        var where = p;
                        double rmin = 300, rmax = 600;
                        if (npc.TryGetValue("war", out var wr) && wr is Dictionary<string, object> war && war.TryGetValue("respawn", out var rs) && rs is string r)
                        {
                            var parts = r.Split(':');
                            if (byId.TryGetValue(parts[0], out var rp)) where = rp;
                            if (parts.Length > 2 && double.TryParse(parts[1], out var a) && double.TryParse(parts[2], out var bb)) { rmin = a; rmax = bb; }
                        }
                        var same = Spawns.FirstOrDefault(s => s.Template == template && s.Place == where);
                        if (same != null) same.Count++;
                        else Spawns.Add(new Spawn { Template = template, Place = where, Count = 1, RespawnMin = rmin, RespawnMax = rmax });
                    }
                    else
                        Npcs.Add(new Npc { Template = template, Name = name ?? template, Place = p });
                }
            }
        }

        /// <summary>The template of an NPC object: "n.c.rat.sk3" → "n.c.rat" when that is a monster.</summary>
        static string Template(string key, Func<string, bool> monster)
        {
            for (string t = key; t.Count(ch => ch == '.') >= 1; t = t.Substring(0, t.LastIndexOf('.')))
                if (monster(t)) return t;
            return key;
        }

        Place Add(string id, Dictionary<string, object> d, bool indoorPlace)
        {
            var p = new Place { Id = id, Name = Name(d), Indoor = indoorPlace, Data = d };
            p.Kind = indoorPlace ? Kind.Town : KindOf(p.Name);
            Places.Add(p);
            byId[id] = p;
            return p;
        }

        static string BuildingKind(string name)
        {
            string n = name.ToLowerInvariant();
            if (n.Contains("таверн")) return "tavern";
            if (n.Contains("магазин") || n.Contains("лавк")) return "shop";
            if (n.Contains("банк") || n.Contains("академ") || n.Contains("особняк") || n.Contains("казарм")) return "hall";
            if (n.Contains("конюшн") || n.Contains("склад")) return "barn";
            return "house";
        }

        static double PathWidth(Kind a, Kind b)
        {
            if (a == Kind.Town || b == Kind.Town || a == Kind.Pier || b == Kind.Pier) return 6;
            if (a == Kind.Road || b == Kind.Road) return 5;
            return 3.5;
        }

        static Surface PathSurface(Kind a, Kind b)
        {
            if (a == Kind.Pier && b == Kind.Pier) return Surface.Planks;
            if (a == Kind.Town || b == Kind.Town) return Surface.Cobble;
            if (a == Kind.Pier || b == Kind.Pier) return Surface.Planks;
            if (a == Kind.Road || b == Kind.Road) return Surface.Road;
            return Surface.Dirt;
        }

        static double Deg(V dir) => Math.Atan2(dir.X, dir.Z) * 180 / Math.PI;

        // ---- laying out ------------------------------------------------------------------------------------
        /// <summary>Clearing radius of a place.</summary>
        static double Clearing(Place p) => p.Kind == Kind.Town || p.Kind == Kind.Pier ? 7 : 5;

        /// <summary>Distance from q to the nearest path (minus its half width) or clearing (minus its radius).</summary>
        double Free(V q)
        {
            double best = double.MaxValue;
            foreach (var p in Paths)
                best = Math.Min(best, SegmentDistance(q, p.A.Pos, p.B.Pos) - p.Width / 2);
            foreach (var p in outdoor)
                best = Math.Min(best, (q - p.Pos).Len - Clearing(p));
            return best;
        }

        static double SegmentDistance(V q, V a, V b)
        {
            var ab = b - a;
            double l2 = ab.X * ab.X + ab.Z * ab.Z;
            double t = l2 < 1e-9 ? 0 : Math.Max(0, Math.Min(1, ((q.X - a.X) * ab.X + (q.Z - a.Z) * ab.Z) / l2));
            return (q - (a + ab * t)).Len;
        }

        /// <summary>The direction of the nearest path at q (for houses facing the street).</summary>
        V NearestPathPoint(V q)
        {
            double best = double.MaxValue;
            V at = q;
            foreach (var p in Paths)
            {
                var ab = p.B.Pos - p.A.Pos;
                double l2 = ab.X * ab.X + ab.Z * ab.Z;
                double t = l2 < 1e-9 ? 0 : Math.Max(0, Math.Min(1, ((q.X - p.A.Pos.X) * ab.X + (q.Z - p.A.Pos.Z) * ab.Z) / l2));
                var c = p.A.Pos + ab * t;
                double d = (q - c).Len - p.Width / 2;
                if (d < best) { best = d; at = c; }
            }
            foreach (var p in outdoor)
            {
                double d = (q - p.Pos).Len - Clearing(p);
                if (d < best) { best = d; at = p.Pos; }
            }
            return at;
        }

        Place Nearest(V q)
        {
            Place best = null;
            double bd = double.MaxValue;
            foreach (var p in outdoor)
            {
                double d = (q - p.Pos).Len;
                if (d < bd) { bd = d; best = p; }
            }
            return best;
        }

        List<Place> outdoor;
        bool[,] blocked;   // ground cells taken by a building, water or the map's edge

        int CI(double x) => (int)Math.Floor((x - MinX) / Cell);
        int CJ(double z) => (int)Math.Floor((z - MinZ) / Cell);
        V CellCentre(int i, int j) => new V(MinX + (i + 0.5) * Cell, MinZ + (j + 0.5) * Cell);

        void Lay()
        {
            outdoor = Places.Where(p => !p.Indoor).ToList();
            if (outdoor.Count == 0) { Problems.Add("no places"); Ground = new Surface[1, 1]; return; }
            const double margin = 40;
            MinX = outdoor.Min(p => p.Pos.X) - margin; MaxX = outdoor.Max(p => p.Pos.X) + margin;
            MinZ = outdoor.Min(p => p.Pos.Z) - margin; MaxZ = outdoor.Max(p => p.Pos.Z) + margin;
            int ni = (int)Math.Ceiling((MaxX - MinX) / Cell), nj = (int)Math.Ceiling((MaxZ - MinZ) / Cell);
            MaxX = MinX + ni * Cell; MaxZ = MinZ + nj * Cell;
            Ground = new Surface[ni, nj];
            blocked = new bool[ni, nj];

            // Distance to the walkable net and the nearest place, per cell.
            var free = new double[ni, nj];
            var near = new Place[ni, nj];
            for (int i = 0; i < ni; i++)
                for (int j = 0; j < nj; j++)
                {
                    var c = CellCentre(i, j);
                    free[i, j] = Free(c);
                    near[i, j] = Nearest(c);
                }

            // The ground by the nearest place; water off the paths at lakes, shores and piers.
            for (int i = 0; i < ni; i++)
                for (int j = 0; j < nj; j++)
                {
                    var k = near[i, j].Kind;
                    double far = (CellCentre(i, j) - near[i, j].Pos).Len;
                    Surface s;
                    switch (k)
                    {
                        case Kind.Town: s = Surface.Dirt; break;
                        case Kind.Pier: s = Surface.Sand; break;
                        case Kind.Shore: s = Surface.Sand; break;
                        case Kind.Lake: s = Surface.Grass; break;
                        case Kind.Forest: s = Surface.ForestFloor; break;
                        case Kind.Swamp: s = Surface.Swamp; break;
                        case Kind.Cemetery: s = Surface.Dirt; break;
                        case Kind.Rocks: s = Surface.Dirt; break;
                        default: s = Surface.Grass; break;
                    }
                    bool wet = (k == Kind.Lake || k == Kind.Shore || k == Kind.Pier) && free[i, j] > (k == Kind.Lake ? 6 : 9) && far > 14;
                    if (wet) { s = Surface.Water; blocked[i, j] = true; }
                    if (k == Kind.Swamp && free[i, j] > 5 && (Hash($"{i},{j}") % 100) < 22) { s = Surface.Water; blocked[i, j] = true; }
                    // The map's edge: beyond the margin of the places nobody goes (trees or water stand there).
                    if (free[i, j] > 34) blocked[i, j] = true;
                    Ground[i, j] = s;
                }

            PlaceBuildings(free);
            // Houses of a town and the fill of the wild off the paths.
            var rnd = new Random(Hash(MapName));
            PlaceHouses(free, near, rnd);
            PlaceWild(free, near, rnd);
            PlacePeople();
        }

        /// <summary>Buildings one enters at the edge of their street, turned to it, towards where the old map has them.</summary>
        void PlaceBuildings(double[,] free)
        {
            foreach (var b in Buildings)
            {
                var st = b.Street;
                var dir = (b.Pos - st.Pos).Norm;
                if (dir.Len < 0.5) dir = new V(0, 1);
                var big = b.Kind == "hall" || b.Kind == "tavern";
                b.W = big ? 14 : 9; b.D = big ? 12 : 8; b.H = big ? 8 : 5;
                // Try angles around the wanted direction for a spot clear of paths and other buildings.
                V best = default; double bestScore = double.MaxValue;
                for (int a = 0; a < 24; a++)
                {
                    double ang = (a % 2 == 0 ? 1 : -1) * (a / 2) * 15 * Math.PI / 180;
                    var d = new V(dir.X * Math.Cos(ang) - dir.Z * Math.Sin(ang), dir.X * Math.Sin(ang) + dir.Z * Math.Cos(ang));
                    for (double r = Clearing(st) + b.D / 2 + 1; r < Clearing(st) + b.D / 2 + 14; r += 2)
                    {
                        var c = st.Pos + d * r;
                        double clear = Free(c) - Math.Max(b.W, b.D) / 2 * 0.9;
                        bool hits = Buildings.Any(o => o != b && o.H > 0 && (o.Pos - c).Len < (Math.Max(o.W, o.D) + Math.Max(b.W, b.D)) / 2 * 0.85);
                        if (clear < -0.5 || hits) continue;
                        double score = Math.Abs(ang) * 10 + r;
                        if (score < bestScore) { bestScore = score; best = c; }
                        break;
                    }
                }
                if (bestScore == double.MaxValue) { Problems.Add($"no room for the building {b.Name} at {st.Id}"); best = st.Pos + dir * (Clearing(st) + b.D / 2 + 1); }
                b.Pos = best;
                // Facing the street: its door side (+Z) looks at the nearest path point.
                var to = (NearestPathPoint(best) - best).Norm;
                if (to.Len < 0.5) to = (st.Pos - best).Norm;
                b.Rot = Deg(to);
                b.Door = best + to * (b.D / 2 + 1.2);
                Block(best, Math.Max(b.W, b.D) / 2);
            }
        }

        void Block(V c, double r)
        {
            for (int i = Math.Max(0, CI(c.X - r)); i <= Math.Min(Ground.GetLength(0) - 1, CI(c.X + r)); i++)
                for (int j = Math.Max(0, CJ(c.Z - r)); j <= Math.Min(Ground.GetLength(1) - 1, CJ(c.Z + r)); j++)
                    if ((CellCentre(i, j) - c).Len <= r) blocked[i, j] = true;
        }

        bool Taken(V c, double r)
        {
            for (int i = Math.Max(0, CI(c.X - r)); i <= Math.Min(Ground.GetLength(0) - 1, CI(c.X + r)); i++)
                for (int j = Math.Max(0, CJ(c.Z - r)); j <= Math.Min(Ground.GetLength(1) - 1, CJ(c.Z + r)); j++)
                    if (blocked[i, j] && (CellCentre(i, j) - c).Len <= r) return true;
            return false;
        }

        /// <summary>Town houses (not entered) along the streets.</summary>
        void PlaceHouses(double[,] free, Place[,] near, Random rnd)
        {
            const double step = 10;
            for (double x = MinX + step / 2; x < MaxX; x += step)
                for (double z = MinZ + step / 2; z < MaxZ; z += step)
                {
                    var c = new V(x + (rnd.NextDouble() - 0.5) * 3, z + (rnd.NextDouble() - 0.5) * 3);
                    int i = CI(c.X), j = CJ(c.Z);
                    if (i < 0 || j < 0 || i >= Ground.GetLength(0) || j >= Ground.GetLength(1)) continue;
                    var k = near[i, j].Kind;
                    if (k != Kind.Town && k != Kind.Pier) continue;
                    double w = 6 + rnd.NextDouble() * 3, d = 6 + rnd.NextDouble() * 3, r = Math.Max(w, d) / 2;
                    double f = Free(c);
                    if (f < r + 0.5 || f > r + 16 || Taken(c, r + 0.5) || Buildings.Any(b => (b.Pos - c).Len < (Math.Max(b.W, b.D)) / 2 + r + 1)) continue;
                    var to = (NearestPathPoint(c) - c).Norm;
                    var h = new Building { Pos = c, W = w, D = d, H = 4 + rnd.NextDouble() * 3, Rot = Deg(to), Kind = "home", Name = "" };
                    Buildings.Add(h);
                    Block(c, r);
                }
        }

        /// <summary>Trees, bushes, rocks, graves off the paths by the kind of the nearest place; the edge closed by trees.</summary>
        void PlaceWild(double[,] free, Place[,] near, Random rnd)
        {
            const double step = 4.5;
            for (double x = MinX + step / 2; x < MaxX; x += step)
                for (double z = MinZ + step / 2; z < MaxZ; z += step)
                {
                    var c = new V(x + (rnd.NextDouble() - 0.5) * 3, z + (rnd.NextDouble() - 0.5) * 3);
                    int i = CI(c.X), j = CJ(c.Z);
                    if (i < 0 || j < 0 || i >= Ground.GetLength(0) || j >= Ground.GetLength(1)) continue;
                    if (Ground[i, j] == Surface.Water) continue;
                    double f = free[i, j];
                    var k = near[i, j].Kind;
                    double roll = rnd.NextDouble();
                    string kind = null;
                    double clearance = 1.5;
                    if (f > 34) kind = roll < 0.75 ? "tree" : "rock";                       // the edge: closed
                    else switch (k)
                    {
                        case Kind.Forest: if (roll < 0.62) kind = roll < 0.08 ? "bush" : "tree"; break;
                        case Kind.Swamp: if (roll < 0.25) kind = roll < 0.15 ? "tree-dead" : "bush"; break;
                        case Kind.Cemetery: if (roll < 0.45) kind = roll < 0.35 ? "grave" : "tree-dead"; clearance = 1; break;
                        case Kind.Rocks: if (roll < 0.40) kind = "rock"; break;
                        case Kind.Road: case Kind.Field: if (roll < 0.10) kind = roll < 0.05 ? "tree" : "bush"; break;
                        case Kind.Lake: case Kind.Shore: if (roll < 0.10) kind = "bush"; break;
                        case Kind.Town: case Kind.Pier: if (f > 3 && roll < 0.04) kind = roll < 0.02 ? "barrel" : "crates"; break;
                    }
                    if (kind == null || f < clearance || Taken(c, 1.2)) continue;
                    double scale = kind == "rock" ? 0.8 + rnd.NextDouble() * 1.4 : 0.8 + rnd.NextDouble() * 0.5;
                    Props.Add(new Prop { Kind = kind, Pos = c, Rot = rnd.NextDouble() * 360, Scale = scale });
                    if (kind != "bush" && kind != "grave") blocked[i, j] = true;
                }
            // Signs: a post at every exit out of the map.
            foreach (var e in Exits)
                Props.Add(new Prop { Kind = "sign", Pos = e.Pos, Rot = e.Rot, Scale = 1 });
            // A well in the middle of the start, if there is room.
        }

        /// <summary>People at their places: indoor ones at the building's door, the others around the clearing; monsters at their places.</summary>
        void PlacePeople()
        {
            var count = new Dictionary<object, int>();
            int Next(object key) { count.TryGetValue(key, out var n); count[key] = n + 1; return n; }
            foreach (var n in Npcs)
            {
                if (n.Place.Indoor && n.Place.Building != null)
                {
                    var b = n.Place.Building;
                    int k = Next(b);
                    var side = new V(Math.Cos(b.Rot * Math.PI / 180), -Math.Sin(b.Rot * Math.PI / 180));
                    var front = (b.Door - b.Pos).Norm;
                    n.Pos = b.Door + front * 1.0 + side * ((k % 2 == 0 ? 1 : -1) * (1.6 + 1.4 * (k / 2)));
                    n.Rot = b.Rot;
                }
                else
                {
                    int k = Next(n.Place);
                    double a = (Hash(n.Place.Id) % 360 + k * 70) * Math.PI / 180;
                    n.Pos = n.Place.Pos + new V(Math.Sin(a), Math.Cos(a)) * (Clearing(n.Place) - 1.5);
                    n.Rot = Deg(n.Place.Pos - n.Pos);
                }
            }
            foreach (var s in Spawns)
                s.Pos = s.Place.Indoor && s.Place.Building != null ? s.Place.Building.Door : s.Place.Pos;
        }
    }
}
