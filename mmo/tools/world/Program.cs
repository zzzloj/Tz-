using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using Tz.Rules;
using Tz.World;

/// <summary>Builds the plan of every map, prints its numbers and problems, writes mmo/tools/world/plan-&lt;n&gt;.json.</summary>
static class Program
{
    static string F(double v) => v.ToString("0.##", CultureInfo.InvariantCulture);
    static string Q(string s) => "\"" + (s ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    static int Main()
    {
        string root = File.Exists("content/logic/balance.json") ? "." : "../../..";
        var npcs = (Dictionary<string, object>)TzJson.Parse(File.ReadAllText(Path.Combine(root, "content", "balance", "npcs.json")));
        bool Monster(string id) => npcs.TryGetValue(id, out var o) && o is Dictionary<string, object> d
            && d.TryGetValue("kind", out var k) && (k as string == "mob" || k as string == "animal");
        int bad = 0;
        for (int m = 0; m < TzWorld.Maps.Length; m++)
        {
            var w = TzWorld.Build(root, m, Monster);
            Console.WriteLine($"{w.MapName}{(TzWorld.Maps[m].on ? "" : " (off)")}: {w.Places.Count(p => !p.Indoor)} places, {w.Buildings.Count(b => b.Rooms.Count > 0)} buildings ({w.Places.Count(p => p.Indoor)} rooms), " +
                $"{w.Buildings.Count(b => b.Rooms.Count == 0)} houses, {w.Paths.Count} paths, {w.Props.Count} props, {w.Npcs.Count} people, {w.Spawns.Count} spawns, {w.Exits.Count} exits, " +
                $"ground {w.Ground.GetLength(0)}×{w.Ground.GetLength(1)} cells, start {w.Start}");
            foreach (var g in w.Exits.Where(e => e.TargetMap >= 0).GroupBy(e => e.TargetMap))
                Console.WriteLine($"  portals to {TzWorld.Maps[g.Key].name}{(TzWorld.Maps[g.Key].on ? "" : " (off)")}: {g.Count()}");
            foreach (var p in w.Problems) Console.WriteLine("  problem: " + p);
            bad += w.Problems.Count;

            var sb = new StringBuilder();
            sb.Append("{\"name\":").Append(Q(w.MapName));
            sb.Append(",\"bounds\":[").Append(F(w.MinX)).Append(',').Append(F(w.MinZ)).Append(',').Append(F(w.MaxX)).Append(',').Append(F(w.MaxZ)).Append(']');
            sb.Append(",\"cell\":").Append(F(TzWorld.Cell));
            sb.Append(",\"start\":[").Append(F(w.Start.X)).Append(',').Append(F(w.Start.Z)).Append(']');
            sb.Append(",\"ground\":[");
            for (int j = 0; j < w.Ground.GetLength(1); j++)
            {
                if (j > 0) sb.Append(',');
                sb.Append('"');
                for (int i = 0; i < w.Ground.GetLength(0); i++) sb.Append((int)w.Ground[i, j]);
                sb.Append('"');
            }
            sb.Append("],\"places\":[").Append(string.Join(",", w.Places.Where(p => !p.Indoor).Select(p =>
                $"{{\"id\":{Q(p.Id)},\"name\":{Q(p.Name)},\"kind\":{Q(p.Kind.ToString())},\"x\":{F(p.Pos.X)},\"z\":{F(p.Pos.Z)}}}")));
            sb.Append("],\"paths\":[").Append(string.Join(",", w.Paths.Select(p =>
                $"[{F(p.A.Pos.X)},{F(p.A.Pos.Z)},{F(p.B.Pos.X)},{F(p.B.Pos.Z)},{F(p.Width)},{(int)p.Surface}]")));
            sb.Append("],\"buildings\":[").Append(string.Join(",", w.Buildings.Select(b =>
                $"{{\"x\":{F(b.Pos.X)},\"z\":{F(b.Pos.Z)},\"w\":{F(b.W)},\"d\":{F(b.D)},\"rot\":{F(b.Rot)},\"kind\":{Q(b.Kind)},\"name\":{Q(b.Name)},\"door\":[{F(b.Door.X)},{F(b.Door.Z)}]}}")));
            sb.Append("],\"props\":[").Append(string.Join(",", w.Props.Select(p => $"[{Q(p.Kind)},{F(p.Pos.X)},{F(p.Pos.Z)},{F(p.Scale)}]")));
            sb.Append("],\"npcs\":[").Append(string.Join(",", w.Npcs.Select(n => $"[{Q(n.Name)},{F(n.Pos.X)},{F(n.Pos.Z)}]")));
            sb.Append("],\"spawns\":[").Append(string.Join(",", w.Spawns.Select(s => $"[{Q(s.Template)},{F(s.Pos.X)},{F(s.Pos.Z)},{s.Count}]")));
            sb.Append("],\"exits\":[").Append(string.Join(",", w.Exits.Select(e => $"[{Q(e.TargetName)},{F(e.Pos.X)},{F(e.Pos.Z)}]")));
            sb.Append("]}");
            File.WriteAllText(Path.Combine(root, "mmo", "tools", "world", $"plan-{m}.json"), sb.ToString());
        }
        return 0;
    }
}
