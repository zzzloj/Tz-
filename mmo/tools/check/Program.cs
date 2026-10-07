using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Tz.Rules;

/// <summary>Recomputes every case of reference.json with TzBalance and fails on a difference.</summary>
static class Program
{
    static double D(object o) => o is bool b ? (b ? 1 : 0) : (double)o;
    static int I(object o) => (int)D(o);

    static TzStats Fighter(Dictionary<string, object> f, out int hp)
    {
        hp = I(f["maxhp"]);
        var block = (List<object>)f["block"];
        return new TzStats
        {
            Level = I(f["L"]), Hit = I(f["acc"]), Dodge = I(f["eva"]), MagicDodge = I(f["meva"]),
            DmgMin = I(f["dmin"]), DmgMax = I(f["dmax"]), Armor = I(f["armor"]), MagicResist = I(f["mres"]),
            Parry = I(block[0]), CritChance = D(f["crit"]), CritMult = 1.5, Pen = D(f["pen"]),
            Magic = (bool)f["magic"], PauseMs = (long)Math.Round(D(f["delay"]) * 1000),
        };
    }

    static int Main(string[] args)
    {
        // Run from the repository root or from this folder.
        string balancePath = File.Exists("content/logic/balance.json") ? "content/logic/balance.json" : "../../../content/logic/balance.json";
        string refPath = File.Exists("mmo/tools/check/reference.json") ? "mmo/tools/check/reference.json" : "reference.json";
        var b = TzBalance.Parse(File.ReadAllText(balancePath));
        var cases = (List<object>)TzJson.Parse(File.ReadAllText(refPath));
        int bad = 0;
        foreach (Dictionary<string, object> c in cases)
        {
            string f = (string)c["f"];
            var a = (List<object>)c["a"];
            object want = c["v"];
            double got;
            double[] gotPair = null;
            switch (f)
            {
                case "ExpToNext": got = b.ExpToNext(I(a[0])); break;
                case "TotalPoints": got = b.TotalPoints(I(a[0])); break;
                case "TierWorn": got = b.TierWorn(I(a[0])); break;
                case "MonsterHp": got = b.MonsterHp(I(a[0])); break;
                case "MonsterDamagePer4s": got = b.MonsterDamagePer4s(I(a[0])); break;
                case "MonsterExp": got = b.MonsterExp(I(a[0])); break;
                case "MonsterGold": got = b.MonsterGold(I(a[0])); break;
                case "WeaponDps": got = b.WeaponDps(I(a[0])); break;
                case "HpMax": got = b.HpMax(I(a[0]), I(a[1])); break;
                case "ManaMax": got = b.ManaMax(I(a[0]), I(a[1])); break;
                case "Accuracy": got = b.Accuracy(I(a[0]), I(a[1]), I(a[2])); break;
                case "Evasion": got = b.Evasion(I(a[0]), I(a[1]), I(a[2])); break;
                case "BlockChance": got = b.BlockChance(I(a[0]), I(a[1])); break;
                case "RegenPerTick": got = b.RegenPerTick(I(a[0]), I(a[1]), I(a[2]) != 0); break;
                case "MagicDefence": got = b.MagicDefence(I(a[0]), I(a[1]), D(a[2])); break;
                case "ArmorCut": got = b.ArmorCut(D(a[0]), I(a[1]), D(a[2])); break;
                case "MagicCut": got = b.MagicCut(D(a[0]), I(a[1])); break;
                case "ExpByGap": got = b.ExpByGap(I(a[0]), I(a[1])); break;
                case "HitChance": got = b.HitChance(D(a[0]), D(a[1])); break;
                case "Pause": got = b.Pause((string)a[0], I(a[1])); break;
                case "CritBase": got = b.CritBase((string)a[0]); break;
                case "Penetration": got = b.Penetration((string)a[0]); break;
                case "Duel":
                    int ah, dh;
                    var fa = Fighter((Dictionary<string, object>)a[0], out ah);
                    var fd = Fighter((Dictionary<string, object>)a[1], out dh);
                    double r = TzCombat.Ratio(b, fa, ah, fd, dh);
                    gotPair = new[] { r, TzCombat.Chance(b, r) };
                    got = r;
                    break;
                default: Console.WriteLine("unknown case " + f); bad++; continue;
            }
            if (gotPair != null)
            {
                var w = (List<object>)want;
                for (int i = 0; i < 2; i++)
                    if (Math.Abs(gotPair[i] - D(w[i])) > 1e-6 * Math.Max(1, Math.Abs(D(w[i]))))
                    { bad++; Console.WriteLine($"{f}: got {gotPair[i]}, want {D(w[i])}"); }
                continue;
            }
            if (Math.Abs(got - D(want)) > 1e-9 * Math.Max(1, Math.Abs(D(want))))
            {
                bad++;
                Console.WriteLine($"{f}({string.Join(", ", a)}): got {got.ToString(CultureInfo.InvariantCulture)}, want {D(want).ToString(CultureInfo.InvariantCulture)}");
            }
        }
        Console.WriteLine($"{cases.Count} cases, {bad} differences");
        return bad == 0 ? 0 : 1;
    }
}
