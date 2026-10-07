using System;
using System.Collections.Generic;

namespace Tz.Rules
{
    /// <summary>
    /// The game's balance (owner 04.10.2026, claude/balance.md) — a port of
    /// engine/server/.../Balance.kt. Every number comes from content/logic/balance.json
    /// (in the MMO: Resources/Tz/balance.json, copied by mmo/tools/prepare_content.py);
    /// these are pure formulas. mmo/tools/check compares them with tools/balance/model.py.
    /// </summary>
    public class TzBalance
    {
        readonly Dictionary<string, object> o;

        public TzBalance(Dictionary<string, object> root) { o = root ?? new Dictionary<string, object>(); }

        public static TzBalance Parse(string json) => new TzBalance(TzJson.Parse(json) as Dictionary<string, object>);

        // ---- reading -------------------------------------------------------------------------
        Dictionary<string, object> Obj(params string[] path)
        {
            var cur = o;
            foreach (var k in path)
            {
                object v;
                if (cur == null || !cur.TryGetValue(k, out v) || !(v is Dictionary<string, object>)) return new Dictionary<string, object>();
                cur = (Dictionary<string, object>)v;
            }
            return cur;
        }

        static double Num(Dictionary<string, object> d, string key, double def)
        {
            object v;
            return d.TryGetValue(key, out v) && v is double x ? x : def;
        }

        static List<double> List(Dictionary<string, object> d, string key)
        {
            var r = new List<double>();
            object v;
            if (d.TryGetValue(key, out v) && v is List<object> a)
                foreach (var e in a) if (e is double x) r.Add(x);
            return r;
        }

        static double At(List<double> l, int i, double def) => i < l.Count ? l[i] : def;

        static Dictionary<string, double> Map(Dictionary<string, object> d)
        {
            var r = new Dictionary<string, double>();
            foreach (var kv in d) if (kv.Value is double x) r[kv.Key] = x;
            return r;
        }

        static long RoundL(double x) => (long)Math.Floor(x + 0.5);   // Kotlin roundToLong: half up
        static int Round(double x) => (int)Math.Floor(x + 0.5);

        // ---- levels and experience -----------------------------------------------------------
        Dictionary<string, object> Lv => Obj("level");
        public int MaxLevel => (int)Num(Lv, "max", 50);
        public int CreationPoints => (int)Num(Lv, "creationPoints", 4);

        /// <summary>Experience from level l to l + 1.</summary>
        public long ExpToNext(int l) => RoundL(Num(Lv, "expBase", 25) * Math.Pow(l, Num(Lv, "expPower", 2.7)));

        /// <summary>Total experience needed to stand on level l (level 1 needs 0).</summary>
        public long ExpForLevel(int l) { long s = 0; for (int i = 1; i < l; i++) s += ExpToNext(i); return s; }

        /// <summary>Level reached with totalExp; experience keeps counting past the top level.</summary>
        public int Level(long totalExp)
        {
            int l = 1; long need = 0;
            while (l < MaxLevel) { need += ExpToNext(l); if (totalExp < need) break; l++; }
            return l;
        }

        /// <summary>Training points granted on reaching level l (l ≥ 2).</summary>
        public int PointsForLevel(int l)
        {
            int every = (int)Num(Lv, "bonusEvery", 10);
            return (int)Num(Lv, "pointsPerLevel", 2) + (every > 0 && l % every == 0 ? (int)Num(Lv, "bonusPoints", 2) : 0);
        }

        public int TotalPoints(int l) { int s = CreationPoints; for (int i = 2; i <= l; i++) s += PointsForLevel(i); return s; }

        // ---- attributes and skills -------------------------------------------------------------
        public int AttrStart => (int)Num(Obj("attributes"), "start", 2);
        public int AttrMax => (int)Num(Obj("attributes"), "max", 10);
        public int AttrSumMax => (int)Num(Obj("attributes"), "sumMax", 24);
        public int SkillMax => (int)Num(Obj("skills"), "max", 10);

        // ---- hero ------------------------------------------------------------------------------
        Dictionary<string, object> H => Obj("hero");
        public int HpMax(int str, int l) => Round(Num(H, "hpBase", 15) + Num(H, "hpPerStr", 5) * str + Num(H, "hpPerLevel", 3) * (l - 1));
        public int ManaMax(int @int, int l) => Round(Num(H, "manaBase", 10) + Num(H, "manaPerInt", 5) * @int + Num(H, "manaPerLevel", 2) * (l - 1));
        public double HpPerStr => Num(H, "hpPerStr", 5);
        public double HpPerLevel => Num(H, "hpPerLevel", 3);
        public double ManaPerInt => Num(H, "manaPerInt", 5);
        public double ManaPerLevel => Num(H, "manaPerLevel", 2);
        public double AccPerDex => Num(H, "accPerDex", 2);
        public double AccPerWeaponSkill => Num(H, "accPerWeaponSkill", 3);
        public double EvaPerDex => Num(H, "evaPerDex", 1);
        public double EvaPerDodge => Num(H, "evaPerDodge", 3);
        public double Accuracy(int dex, int weaponSkill, int l) => AccPerDex * dex + AccPerWeaponSkill * weaponSkill + l;
        public double Evasion(int dex, int dodge, int l) => EvaPerDex * dex + EvaPerDodge * dodge + l;

        /// <summary>Hit chance in percent: 60 + accuracy − evasion, within 15–95.</summary>
        public double HitChance(double acc, double eva) =>
            Math.Min(Num(H, "hitMax", 95), Math.Max(Num(H, "hitMin", 15), Num(H, "hitBase", 60) + acc - eva));

        /// <summary>Flat strength bonus per blow (not crossbows, not magic): str/2 per 4 s of pause.</summary>
        public double StrengthBonus(int str, double pause) => Num(H, "strDamagePer4s", 0.5) * str * pause / 4;
        public double WeaponSkillMultiplier(int skill) => 1 + Num(H, "weaponSkillDamage", 0.03) * skill;

        /// <summary>Pause between blows (s) for a weapon class, shortened by dexterity.</summary>
        public double Pause(string weaponClass, int dex)
        {
            double b;
            if (!Map(Obj("pause")).TryGetValue(weaponClass ?? "", out b)) b = 1.2;
            return Math.Max(Num(H, "pauseMin", 0.7), b * (1 - Num(H, "pauseDexCut", 0.02) * dex));
        }

        /// <summary>Regeneration per tick (every RegenEvery s, RegenAfter s out of combat): a share of max HP.</summary>
        public double RegenPerTick(int maxHp, int regenSkill, bool safe) =>
            maxHp * (Num(H, "regenBase", 0.015) + Num(H, "regenPerSkill", 0.003) * regenSkill) * (safe ? Num(H, "regenSafeMult", 3) : 1);
        public double RegenAfter => Num(H, "regenAfter", 10);
        public double RegenEvery => Num(H, "regenEvery", 5);

        public double WeaponDps(int tier) { var w = Obj("items"); return Num(w, "dpsBase", 1.2) + Num(w, "dpsPerTier", 0.36) * (tier - 1); }

        public int TierWorn(int level) =>
            Math.Max(1, Math.Min(50, (int)Math.Round(level - 2 + 3 * Math.Min(1.0, (level - 1) / 9.0), MidpointRounding.AwayFromZero)));

        // ---- crit, armour, magic, block ----------------------------------------------------------
        public double CritBase(string weaponClass)
        {
            double c;
            return Map(Obj("crit", "base")).TryGetValue(weaponClass ?? "", out c) ? c : Num(Obj("crit"), "default", 5);
        }
        public double CritMultiplier => Num(Obj("crit"), "mult", 1.5);

        public double Penetration(string weaponClass)
        {
            var ar = Obj("armor");
            switch (weaponClass)
            {
                case "heavy": case "crossbow": return Num(ar, "heavyPen", 0.75);
                case "knife": case "rapier": case "thrown": return Num(ar, "lightPen", 1.15);
                default: return 1.0;
            }
        }

        double K(int attackerLevel) { var ar = Obj("armor"); return Num(ar, "k", 50) + Num(ar, "kPerLevel", 10) * attackerLevel; }

        /// <summary>Share of physical damage the armour stops (0..1).</summary>
        public double ArmorCut(double armor, int attackerLevel, double pen = 1.0)
        {
            double a = armor * pen;
            return a <= 0 ? 0 : a / (a + K(attackerLevel));
        }

        public double MagicDefence(int @int, int magicResist, double armor)
        {
            var mg = Obj("magic");
            return Num(mg, "perInt", 5) * @int + Num(mg, "perResist", 8) * magicResist + Num(mg, "armorShare", 0.25) * armor;
        }
        public double MagicCut(double defence, int attackerLevel) => defence <= 0 ? 0 : defence / (defence + K(attackerLevel));

        /// <summary>Shield block chance in percent; a blocked blow loses BlockCut of its damage.</summary>
        public double BlockChance(int parry, int dex)
        {
            var bl = Obj("block");
            return Math.Min(Num(bl, "max", 30), Num(bl, "base", 5) + Num(bl, "perParry", 2) * parry + Num(bl, "perDex", 0.5) * dex);
        }
        public double BlockCut => Num(Obj("block"), "cut", 0.5);

        // ---- monsters ------------------------------------------------------------------------------
        Dictionary<string, object> Mo => Obj("monsters");
        static double Poly(List<double> c, int l) { double s = 0; for (int i = 0; i < c.Count; i++) s += c[i] * Math.Pow(l, i); return s; }
        public double MonsterHp(int l) => Math.Max(Num(Mo, "hpMin", 18), Poly(List(Mo, "hp"), l));
        public double MonsterDamagePer4s(int l) => Poly(List(Mo, "dmgPer4s"), l);
        public long MonsterExp(int l) { var e = List(Mo, "exp"); return RoundL(At(e, 0, 8) * Math.Pow(l, At(e, 1, 1.45))); }
        public int MonsterGold(int l) { var g = List(Mo, "gold"); return Round(At(g, 0, 2) + At(g, 1, 0.9) * Math.Pow(l, At(g, 2, 1.6))); }

        /// <summary>Experience share for a hero of heroLevel killing a monster of mobLevel.</summary>
        public double ExpByGap(int heroLevel, int mobLevel)
        {
            var g = Obj("expByLevelGap");
            int d = mobLevel - heroLevel;
            double free = Num(g, "freeBelow", 4);
            if (d >= 0) return Math.Min(Num(g, "upMax", 1.5), 1 + Num(g, "upPerLevel", 0.08) * d);
            if (d >= -free) return 1.0;
            return Math.Max(Num(g, "downMin", 0.1), 1 - Num(g, "downPerLevel", 0.15) * (-d - free));
        }
        public double GroupBonusPerMember => Num(o, "groupBonusPerMember", 0.10);

        // ---- power (owner 06.10, balance.md §6) ------------------------------------------------------
        public double PowerK => At(List(Obj("power"), "chance"), 0, 8.75);
        public double PowerB => At(List(Obj("power"), "chance"), 1, 0.05);

        double Lin(string key, int l, double a, double b) { var r = List(Obj("power", "reference"), key); return At(r, 0, a) + At(r, 1, b) * l; }

        /// <summary>The ordinary monster of level l that power is measured against, and its health.</summary>
        public TzStats ReferenceMonster(int l, out int hp)
        {
            var re = Obj("power", "reference");
            double pause = Num(re, "pause", 1.4);
            double blow = MonsterDamagePer4s(l) * pause / 4;
            hp = Round(MonsterHp(l));
            double eva = Lin("evasion", l, 0, 1.3);
            return new TzStats
            {
                Hit = Round(Lin("accuracy", l, 4, 1.6)),
                DmgMin = Round(blow * 0.6),
                DmgMax = Math.Max(1, Round(blow * 1.4)),
                PauseMs = RoundL(pause * 1000),
                Armor = Round(Lin("armor", l, 1, 1.5)),
                Dodge = Round(eva),
                MagicDodge = Round(eva),
                MagicResist = Round(Lin("magicDefence", l, 0.4, 0.6)),
                Level = l,
                CritChance = Num(re, "crit", 3),
            };
        }
    }

    /// <summary>Combat parameters of a fighter (Combat.kt Stats).</summary>
    public class TzStats
    {
        /// <summary>Accuracy (points); 0 for magic means the spell fizzles.</summary>
        public int Hit;
        public int DmgMin, DmgMax;
        /// <summary>Pause after a blow, milliseconds.</summary>
        public long PauseMs = 1000;
        public bool Ranged;
        public int Armor;
        public int Dodge;
        /// <summary>Block chance in percent (only with a shield).</summary>
        public int Parry;
        public int MagicDodge;
        public int MagicResist;
        public bool Magic;
        public int Level = 1;
        public double CritChance = 5.0;
        public double CritMult = 1.5;
        /// <summary>Armour the blow meets: 0.75 heavy weapons, 1.15 light ones.</summary>
        public double Pen = 1.0;
    }

    /// <summary>One blow and the power of a fighter (Combat.kt Formulas.attack, Power.kt).</summary>
    public static class TzCombat
    {
        public enum Outcome { Miss, Hit, Fizzled }

        public struct Blow
        {
            public Outcome Outcome;
            public int Damage;
            public bool Crit;
            public int Shield;
            public int Resisted;
        }

        /// <summary>Integer in [from, to], both ends included (PHP rand()).</summary>
        public delegate int Dice(int from, int to);

        /// <summary>
        /// One blow (balance.md §5): hit chance = 60 + accuracy − evasion (15–95 %); a shield may block
        /// half of it; armour stops a share of a physical blow (heavy weapons meet less of it), magic
        /// defence of a magic one; crit multiplies what is left.
        /// </summary>
        public static Blow Attack(TzBalance b, TzStats a, TzStats d, Dice dice)
        {
            if (a.Magic && a.Hit == 0) return new Blow { Outcome = Outcome.Fizzled };
            double chance = b.HitChance(a.Hit, a.Magic ? d.MagicDodge : d.Dodge);
            if (dice(0, 99) >= chance) return new Blow { Outcome = Outcome.Miss };
            double damage = dice(Math.Min(a.DmgMin, a.DmgMax), Math.Max(a.DmgMin, a.DmgMax));
            int shield = 0, resisted = 0;
            if (!a.Magic && d.Parry > 0 && dice(0, 99) < d.Parry)
            {
                shield = (int)Math.Round(damage * b.BlockCut, MidpointRounding.AwayFromZero);
                damage -= shield;
            }
            if (a.Magic)
            {
                resisted = (int)Math.Round(damage * b.MagicCut(d.MagicResist, a.Level), MidpointRounding.AwayFromZero);
                damage -= resisted;
            }
            else damage *= 1 - b.ArmorCut(d.Armor, a.Level, a.Pen);
            bool crit = false;
            if (damage > 0 && dice(0, 9999) < a.CritChance * 100) { damage *= a.CritMult; crit = true; }
            int dealt = damage <= 0 ? 0 : Math.Max(1, (int)Math.Round(damage, MidpointRounding.AwayFromZero));
            return new Blow { Outcome = Outcome.Hit, Damage = dealt, Crit = crit, Shield = shield, Resisted = resisted };
        }

        /// <summary>Expected damage of one blow of a at d.</summary>
        public static double ExpectedBlow(TzBalance b, TzStats a, TzStats d)
        {
            if (a.Magic && a.Hit == 0) return 0;
            double x = b.HitChance(a.Hit, a.Magic ? d.MagicDodge : d.Dodge) / 100 * (a.DmgMin + a.DmgMax) / 2.0;
            if (a.Magic) x *= 1 - b.MagicCut(d.MagicResist, a.Level);
            else
            {
                if (d.Parry > 0) x *= 1 - d.Parry / 100.0 * b.BlockCut;
                x *= 1 - b.ArmorCut(d.Armor, a.Level, a.Pen);
            }
            return x * (1 + a.CritChance / 100 * (a.CritMult - 1));
        }

        public static double PerSecond(TzBalance b, TzStats a, TzStats d) => ExpectedBlow(b, a, d) * 1000 / Math.Max(1, a.PauseMs);

        /// <summary>r(A, B): how many healths of B A takes while B takes one health of A.</summary>
        public static double Ratio(TzBalance b, TzStats a, int aHp, TzStats d, int dHp) =>
            PerSecond(b, a, d) * Math.Max(1, aHp) / Math.Max(1e-9, PerSecond(b, d, a) * Math.Max(1, dHp));

        /// <summary>Chance (0..1) to win a duel at power ratio r.</summary>
        public static double Chance(TzBalance b, double r) => 1 / (1 + Math.Exp(-(b.PowerK * Math.Log(Math.Max(1e-9, r)) + b.PowerB)));

        /// <summary>The power number of s with hp health at level.</summary>
        public static int Power(TzBalance b, TzStats s, int hp, int level)
        {
            int refHp;
            var re = b.ReferenceMonster(Math.Max(1, Math.Min(b.MaxLevel, level)), out refHp);
            return (int)Math.Round(Ratio(b, s, hp, re, refHp) * refHp, MidpointRounding.AwayFromZero);
        }
    }
}
