// Our game's data in the MMO framework, made before every build from content/ (the one source of it):
// - experience table, the player's base stats and the default weapon's pause from content/logic/balance.json;
// - the starting zone's monsters and animals (levels 1–5, content/balance/npcs.json + names from content/npcs)
//   as MonsterCharacter assets and entity prefabs (copies of the demo's capsule), put into the demo map's spawn areas;
// - a Cyrillic font (Alegreya Sans, OFL) as TextMesh Pro's fallback, so Russian names and texts show.
// What is made goes to Assets/Tz/Generated (not in git): change content/, not the assets.
#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using MultiplayerARPG;
using TMPro;
using Tz.Rules;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

public static class TzImport
{
    const string Generated = "Assets/Tz/Generated";
    const string DemoMonster = "Assets/Tz/BaseDemo/GameData/Resources/MonsterCharacters/BaseEnemy-CE.asset";
    const string DemoMonsterPrefab = "Assets/Tz/BaseDemo/Prefabs/GamePlay/CharacterEntities/BaseEnemy-CE.prefab";
    const string DemoPlayer = "Assets/Tz/BaseDemo/GameData/Resources/PlayerCharacters/BaseCharacter-CE.asset";
    const string DemoWeapon = "Assets/Tz/BaseDemo/GameData/Resources/Items/DefaultWeaponItem-CE.asset";
    const string DemoUnarmed = "Assets/Tz/BaseDemo/GameData/Resources/WeaponTypes/Unarmed-CE.asset";
    const string DemoDatabase = "Assets/Tz/BaseDemo/GameData/GameDatabase-CE.asset";
    const string DemoGameInstance = "Assets/Tz/BaseDemo/Prefabs/GameInstance-CE.prefab";
    const string DemoMap = "Assets/Tz/BaseDemo/Scenes/BaseMap-CE.unity";
    const string FontPath = "Assets/Tz/Fonts/alegreya_sans_regular.ttf";

    /// <summary>The starting zone: monsters and animals of these levels.</summary>
    const int StartZoneMaxLevel = 5;

    static int problems;

    public static void Run(string repoRoot)
    {
        problems = 0;
        var balance = TzBalance.Parse(File.ReadAllText(Path.Combine(repoRoot, "content", "logic", "balance.json")));
        foreach (var dir in new[] { "Monsters", "Items", "Types", "Npcs" })
            Directory.CreateDirectory(Path.Combine(repoRoot, "mmo", Generated, dir));
        AssetDatabase.Refresh();
        ExpTable(balance);
        var magic = MagicElement();
        var attributes = Attributes(balance, magic);
        var weaponTypes = WeaponTypes();
        var armorTypes = ArmorTypes();
        var skills = Skills(balance, magic);
        var spells = Spells(repoRoot, balance, skills, magic);
        var techniques = Techniques(repoRoot);
        var stun = Carrier("tz.stun", "Оглушение", true);
        var busy = Carrier("tz.busy", "Занят", false);
        var items = Items(repoRoot, balance, attributes, weaponTypes, armorTypes, spells, magic);
        AddFoodToSpells(spells, items);
        FoodExtras(repoRoot, items);
        Death();
        Player(balance, attributes, items, skills
            .Concat(spells.Select(kv => new KeyValuePair<string, BaseSkill>(kv.Key, kv.Value)))
            .Concat(techniques.Select(kv => new KeyValuePair<string, BaseSkill>(kv.Key, kv.Value)))
            .ToDictionary(kv => kv.Key, kv => kv.Value));
        LoginDataManager();
        DefaultWeapon();
        var monsters = Monsters(repoRoot, balance, items, magic);
        Spawns(monsters);
        Shops(repoRoot, items);
        Database(attributes.Values, weaponTypes.Values, armorTypes.Values, items.Values, skills.Values.Concat(spells.Values).Concat(techniques.Values).Concat(new[] { stun, busy }), magic);
        try { CyrillicFont(); }
        catch (Exception e) { problems++; Debug.LogError("[TzImport] Cyrillic font: " + e); }
        AssetDatabase.SaveAssets();
        Debug.Log($"[TzImport] done: {attributes.Count} attributes, {weaponTypes.Count} weapon types, {armorTypes.Count} armour slots, {items.Count} items, {skills.Count} skills, {spells.Count} spells, {techniques.Count} techniques, {monsters.Count} monsters, {problems} problems");
    }

    // ---- helpers ---------------------------------------------------------------------------------
    static SerializedProperty P(SerializedObject so, string path)
    {
        var p = so.FindProperty(path);
        if (p == null) { problems++; Debug.LogError($"[TzImport] no field {path} in {so.targetObject.name}"); }
        return p;
    }

    static void F(SerializedObject so, string path, float v) { var p = P(so, path); if (p != null) p.floatValue = v; }
    static void I(SerializedObject so, string path, int v)
    {
        var p = P(so, path);
        if (p == null) return;
        if (p.propertyType == SerializedPropertyType.Float) p.floatValue = v; else p.intValue = v;
    }
    static void S(SerializedObject so, string path, string v) { var p = P(so, path); if (p != null) p.stringValue = v; }

    /// <summary>Zeroes every number under a stats block (baseStats, statsIncreaseEachLevel, …).</summary>
    static void Zero(SerializedObject so, string path)
    {
        var p = P(so, path);
        if (p == null) return;
        var end = p.GetEndProperty();
        var it = p.Copy();
        if (!it.NextVisible(true)) return;
        while (!SerializedProperty.EqualContents(it, end))
        {
            if (it.propertyType == SerializedPropertyType.Float) it.floatValue = 0;
            else if (it.propertyType == SerializedPropertyType.Integer) it.intValue = 0;
            if (!it.NextVisible(it.propertyType == SerializedPropertyType.Generic)) break;
        }
    }

    // ---- experience, player, weapon ------------------------------------------------------------------
    static void ExpTable(TzBalance b)
    {
        var gi = AssetDatabase.LoadAssetAtPath<GameInstance>(DemoGameInstance);
        var so = new SerializedObject(gi);
        var tree = P(so, "expTree");
        if (tree == null) return;
        // expTree[l − 1] — experience from level l to l + 1; none past the top level.
        tree.arraySize = b.MaxLevel - 1;
        for (int l = 1; l < b.MaxLevel; l++) tree.GetArrayElementAtIndex(l - 1).intValue = (int)Math.Min(int.MaxValue, b.ExpToNext(l));
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(gi);
    }

    /// <summary>
    /// A new character (balance.md §5): attributes at their start value through the attributes,
    /// the rest by level — accuracy and evasion +1, health +3, mana +2 a level; the base crit (fists);
    /// the starting knife in hand.
    /// </summary>
    static void Player(TzBalance b, Dictionary<string, MultiplayerARPG.Attribute> attributes, Dictionary<string, BaseItem> items, Dictionary<string, BaseSkill> skills)
    {
        var asset = AssetDatabase.LoadMainAssetAtPath(DemoPlayer);
        var so = new SerializedObject(asset);
        Zero(so, "stats.statsIncreaseEachLevel");
        Zero(so, "stats.rateIncreaseEachLevel");
        F(so, "stats.baseStats.hp", b.HpMax(0, 1));
        F(so, "stats.statsIncreaseEachLevel.hp", (float)b.HpPerLevel);
        F(so, "stats.baseStats.mp", b.ManaMax(0, 1));
        F(so, "stats.statsIncreaseEachLevel.mp", (float)b.ManaPerLevel);
        F(so, "stats.baseStats.accuracy", (float)b.Accuracy(0, 0, 1));
        F(so, "stats.statsIncreaseEachLevel.accuracy", 1);
        F(so, "stats.baseStats.evasion", (float)b.Evasion(0, 0, 1));
        F(so, "stats.statsIncreaseEachLevel.evasion", 1);
        F(so, "stats.baseStats.criRate", (float)(b.CritBase("hand") / 100));
        F(so, "stats.baseStats.criDmgRate", (float)b.CritMultiplier);
        F(so, "stats.baseStats.blockRate", 0);
        F(so, "stats.baseStats.blockDmgRate", 0);
        F(so, "stats.baseStats.atkSpeed", 1);
        var list = P(so, "attributes");
        if (list != null)
        {
            var order = new[] { "str", "dex", "int" };
            list.arraySize = order.Length;
            for (int i = 0; i < order.Length; i++)
            {
                var e = list.GetArrayElementAtIndex(i);
                e.FindPropertyRelative("attribute").objectReferenceValue = attributes[order[i]];
                e.FindPropertyRelative("amount.baseAmount").floatValue = b.AttrStart;
                e.FindPropertyRelative("amount.amountIncreaseEachLevel").floatValue = 0;
                e.FindPropertyRelative("amount.rateIncreaseEachLevel").floatValue = 0;
            }
        }
        var learnable = P(so, "skills");
        if (learnable != null)
        {
            // Every skill can be learnt from step 0.
            var all = skills.Values.ToArray();
            learnable.arraySize = all.Length;
            for (int i = 0; i < all.Length; i++)
            {
                var e = learnable.GetArrayElementAtIndex(i);
                e.FindPropertyRelative("skill").objectReferenceValue = all[i];
                e.FindPropertyRelative("level").intValue = 0;
                e.FindPropertyRelative("skillLevel.baseAmount").intValue = 0;
                e.FindPropertyRelative("skillLevel.amountIncreaseEachLevel").intValue = 0;
            }
        }
        if (items.TryGetValue(StartWeapon, out var knife))
            P(so, "rightHandEquipItem").objectReferenceValue = knife;
        else { problems++; Debug.LogError("[TzImport] no starting weapon " + StartWeapon); }
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(asset);
    }

    // ---- magic --------------------------------------------------------------------------------------------
    /// <summary>The magic damage element: spells, staves and magic monsters hit with it; magic defence is its armour.</summary>
    static DamageElement MagicElement()
    {
        var e = Make<DamageElement>($"{Generated}/Types/element-magic.asset");
        var so = new SerializedObject(e);
        S(so, "id", "magic");
        S(so, "defaultTitle", "Магия");
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(e);
        return e;
    }

    static void ArmorOf(SerializedProperty list, DamageElement element, float baseAmount, float perLevel)
    {
        list.arraySize++;
        var e = list.GetArrayElementAtIndex(list.arraySize - 1);
        e.FindPropertyRelative("damageElement").objectReferenceValue = element;
        e.FindPropertyRelative("amount.baseAmount").floatValue = baseAmount;
        e.FindPropertyRelative("amount.amountIncreaseEachLevel").floatValue = perLevel;
        e.FindPropertyRelative("amount.rateIncreaseEachLevel").floatValue = 0;
    }

    // ---- attributes, weapon classes, armour slots ------------------------------------------------------
    const string StartWeapon = "i.w.k.begin";

    static T Make<T>(string path) where T : ScriptableObject
    {
        AssetDatabase.DeleteAsset(path);
        var o = ScriptableObject.CreateInstance<T>();
        o.name = Path.GetFileNameWithoutExtension(path);
        AssetDatabase.CreateAsset(o, path);
        return o;
    }

    /// <summary>
    /// Strength, dexterity, intelligence (balance.md §5), 1–10 each. A point of strength: +5 health
    /// (and str/2 damage per 4 s of pause, in TzGameplayRule); of dexterity: +2 accuracy, +1 evasion,
    /// a pause about 2 % shorter (rate of fire +2.22 %: 1 / (1 + 0.0222·dex) ≈ 1 − 0.02·dex up to 10),
    /// +0.5 % block with a shield (TzGameplayRule); of intelligence: +5 mana.
    /// </summary>
    static Dictionary<string, MultiplayerARPG.Attribute> Attributes(TzBalance b, DamageElement magic)
    {
        var result = new Dictionary<string, MultiplayerARPG.Attribute>();
        foreach (var (id, title) in new[] { ("str", "Сила"), ("dex", "Ловкость"), ("int", "Интеллект") })
        {
            var a = Make<MultiplayerARPG.Attribute>($"{Generated}/Types/attr-{id}.asset");
            var so = new SerializedObject(a);
            S(so, "id", id);
            S(so, "defaultTitle", title);
            I(so, "maxAmount", b.AttrMax);
            if (id == "str") F(so, "statsIncreaseEachLevel.hp", (float)b.HpPerStr);
            if (id == "dex")
            {
                F(so, "statsIncreaseEachLevel.accuracy", (float)b.AccPerDex);
                F(so, "statsIncreaseEachLevel.evasion", (float)b.EvaPerDex);
                F(so, "statsIncreaseEachLevel.rateOfFireRate", 0.0222f);
            }
            if (id == "int")
            {
                F(so, "statsIncreaseEachLevel.mp", (float)b.ManaPerInt);
                // Magic defence +5 a point (balance.md §5).
                var armors = P(so, "increaseArmors");
                if (armors != null) { armors.arraySize = 0; ArmorOf(armors, magic, (float)b.MagicDefencePerInt, (float)b.MagicDefencePerInt); }
            }
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(a);
            result[id] = a;
        }
        return result;
    }

    static readonly (string id, string title, int equip, float distance)[] Classes =
    {
        // equip: 0 one hand, 2 two hands (WeaponItemEquipType); distance — how far a blow reaches, m.
        ("knife", "Нож", 0, 1.5f), ("sword", "Меч", 0, 1.8f), ("axe", "Топор", 0, 1.8f), ("spear", "Копьё", 0, 2.4f),
        ("rapier", "Шпага", 0, 1.8f), ("staff", "Посох", 2, 2.0f), ("heavy", "Двуручное оружие", 2, 2.2f),
        ("bow", "Лук", 2, 12f), ("crossbow", "Арбалет", 2, 12f), ("thrown", "Метательное оружие", 0, 8f),
    };

    /// <summary>Weapon classes (balance.md §13) as WeaponType assets with the class as id; fists are the demo's Unarmed, id "hand".</summary>
    static Dictionary<string, WeaponType> WeaponTypes()
    {
        var result = new Dictionary<string, WeaponType>();
        var unarmed = AssetDatabase.LoadAssetAtPath<WeaponType>(DemoUnarmed);
        var uso = new SerializedObject(unarmed);
        S(uso, "id", "hand");
        S(uso, "defaultTitle", "Кулаки");
        uso.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(unarmed);
        result["hand"] = unarmed;
        foreach (var c in Classes)
        {
            string path = $"{Generated}/Types/weapon-{c.id}.asset";
            AssetDatabase.DeleteAsset(path);
            AssetDatabase.CopyAsset(DemoUnarmed, path);
            var t = AssetDatabase.LoadAssetAtPath<WeaponType>(path);
            var so = new SerializedObject(t);
            S(so, "id", c.id);
            S(so, "defaultTitle", c.title);
            P(so, "equipType").enumValueIndex = c.equip;
            F(so, "damageInfo.hitDistance", c.distance);
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(t);
            result[c.id] = t;
        }
        return result;
    }

    static readonly (string slot, string title)[] Slots =
    {
        // i.a.<slot>.… — one thing a slot (data-fields.md §0); i.a.s is the shield, in the left hand.
        ("b", "Доспех"), ("h", "Шлем"), ("r", "Рубашка"), ("p", "Поручи"), ("l", "Поножи"), ("e", "Плащ"),
        ("w", "Штаны"), ("c", "Обувь"), ("a", "Украшение"), ("o", "Очки"), ("d", "Знак"), ("m", "Ожерелье"),
    };

    static Dictionary<string, ArmorType> ArmorTypes()
    {
        var result = new Dictionary<string, ArmorType>();
        foreach (var (slot, title) in Slots)
        {
            var t = Make<ArmorType>($"{Generated}/Types/armor-{slot}.asset");
            var so = new SerializedObject(t);
            S(so, "id", "armor-" + slot);
            S(so, "defaultTitle", title);
            S(so, "equipPosition", "TZ_" + slot.ToUpperInvariant());
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(t);
            result[slot] = t;
        }
        return result;
    }

    // ---- skills ------------------------------------------------------------------------------------------
    static readonly (string id, string title)[] SkillList =
    {
        // Keys and titles of engine/shared Protocol.kt SKILLS; only those our rules use so far.
        ("hand", "Рукопашная"), ("coldweapon", "Холодное оружие"), ("ranged", "Стрельба"),
        ("parring", "Парирование"), ("uklon", "Уклон"), ("regeneration", "Регенерация"),
        ("magic", "Магия"), ("magic_resist", "Сопротивление магии"), ("magic_uklon", "Уклон от магии"),
        ("meditation", "Медитация"), ("healing", "Лечение"),
    };

    /// <summary>
    /// Passive skills, steps 0–10, a step for one training point and 15·n² coins for the n-th (the teacher's
    /// price, balance.md §4; the framework takes the coins when the step is learnt — there are no teachers
    /// yet). Their effects are in TzGameplayRule (weapon skills, parrying, regeneration); dodging gives
    /// +3 evasion a step through the skill's buff.
    /// </summary>
    static Dictionary<string, BaseSkill> Skills(TzBalance b, DamageElement magic)
    {
        var result = new Dictionary<string, BaseSkill>();
        foreach (var (id, title) in SkillList)
        {
            var skill = Make<TzSkill>($"{Generated}/Types/skill-{id}.asset");
            var so = new SerializedObject(skill);
            S(so, "id", id);
            S(so, "defaultTitle", title);
            P(so, "skillType").enumValueIndex = 1;   // SkillType.Passive
            I(so, "maxLevel", b.SkillMax);
            var steps = P(so, "requirementEachLevels");
            if (steps != null)
            {
                steps.arraySize = b.SkillMax;
                for (int n = 1; n <= b.SkillMax; n++)
                {
                    var e = steps.GetArrayElementAtIndex(n - 1);
                    e.FindPropertyRelative("disallow").boolValue = false;
                    e.FindPropertyRelative("characterLevel").intValue = 0;
                    e.FindPropertyRelative("skillPoint").floatValue = 1;
                    e.FindPropertyRelative("gold").intValue = b.TeacherPrice(n);
                }
            }
            if (id == "magic_resist")
            {
                // Magic defence +8 a step, as the magic element's armour.
                var armors = P(so, "buff.increaseArmors");
                if (armors != null) { armors.arraySize = 0; ArmorOf(armors, magic, (float)b.MagicDefencePerResist, (float)b.MagicDefencePerResist); }
            }
            if (id == "uklon")
            {
                F(so, "buff.increaseStats.baseStats.evasion", (float)b.EvaPerDodge);
                F(so, "buff.increaseStats.statsIncreaseEachLevel.evasion", (float)b.EvaPerDodge);
            }
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(skill);
            result[id] = skill;
        }
        return result;
    }

    /// <summary>The spells made so far (the others need summons, pets or the world map: later).</summary>
    static TzSpell.Kind? SpellKind(string id)
    {
        if (id.StartsWith("m.w.a.")) return id == "m.w.a.qv" ? (TzSpell.Kind?)null : TzSpell.Kind.Area;
        if (id.StartsWith("m.w.")) return TzSpell.Kind.Battle;
        switch (id)
        {
            case "m.heal": case "m.heal.great": return TzSpell.Kind.Heal;
            case "m.heal.all": return TzSpell.Kind.HealAll;
            case "m.armor": return TzSpell.Kind.Armor;
            case "m.str": return TzSpell.Kind.Strength;
            case "m.man": return TzSpell.Kind.Mana;
            case "m.meditation": return TzSpell.Kind.Meditation;
            case "m.createfood": return TzSpell.Kind.CreateFood;
            case "m.buket": return TzSpell.Kind.Bouquet;
            case "m.n": case "m.roj": return TzSpell.Kind.Other;   // stun 20 s
            default: return null;
        }
    }

    /// <summary>The lowest price a teacher asks for a spell or technique (content/dialogs "skill|id|cost", content/logic "teach"); 0 — only from a quest.</summary>
    static Dictionary<string, int> TeacherPrices(string root)
    {
        var prices = new Dictionary<string, int>();
        void Note(string id, int cost) { if (!prices.TryGetValue(id, out var c) || cost < c) prices[id] = cost; }
        foreach (var f in Directory.GetFiles(Path.Combine(root, "content", "dialogs"), "*.json"))
            if (Json(f).TryGetValue("topics", out var t) && t is Dictionary<string, object> topics)
                foreach (var v in topics.Values.OfType<string>())
                {
                    var p = v.Split('|');
                    if (p.Length > 2 && p[0] == "skill" && double.TryParse(p[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var c))
                        Note(p[1], (int)c);
                }
        var teach = new System.Text.RegularExpressions.Regex("\"teach\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"cost\"\\s*:\\s*(\\d+)");
        foreach (var f in Directory.GetFiles(Path.Combine(root, "content", "logic"), "*.json"))
            foreach (System.Text.RegularExpressions.Match m in teach.Matches(File.ReadAllText(f)))
                Note(m.Groups[1].Value, int.Parse(m.Groups[2].Value));
        return prices;
    }

    /// <summary>
    /// Spells of content/items/m.*.json as TzSpell skills (Magic.kt): mana, cooldown (battle spells: the
    /// staff's pause instead), circle, power; learnt once for the teacher's price (no training points),
    /// or cast from a scroll or rune without learning.
    /// </summary>
    static Dictionary<string, TzSpell> Spells(string root, TzBalance b, Dictionary<string, BaseSkill> skills, DamageElement magic)
    {
        var result = new Dictionary<string, TzSpell>();
        var prices = TeacherPrices(root);
        foreach (var file in Directory.GetFiles(Path.Combine(root, "content", "items"), "m.*.json").OrderBy(f => f))
        {
            var o = Json(file);
            string id = o.TryGetValue("id", out var iv) ? iv as string : null;
            if (id == null) continue;
            var kind = SpellKind(id);
            if (kind == null) continue;
            string name = o.TryGetValue("name", out var nm) && nm is string s && s.Length > 0 ? char.ToUpper(s[0]) + s.Substring(1) : id;
            var spell = Make<TzSpell>($"{Generated}/Types/spell-{id}.asset");
            var so = new SerializedObject(spell);
            S(so, "id", id);
            S(so, "defaultTitle", name);
            S(so, "defaultDescription", o.TryGetValue("description", out var dv) ? dv as string ?? "" : "");
            P(so, "kind").enumValueIndex = (int)kind.Value;
            I(so, "circle", (int)N(o, "level", 1));
            I(so, "powerMin", (int)N(o, "power_min"));
            I(so, "powerMax", (int)N(o, "power_max"));
            P(so, "criminalsOnly").boolValue = N(o, "criminals_only") != 0;
            P(so, "drainsMana").boolValue = id == "m.w.hit";
            P(so, "vampire").boolValue = id == "m.w.vamp";
            P(so, "skillType").enumValueIndex = 0;   // Active
            I(so, "maxLevel", 1);
            I(so, "consumeMp.baseAmount", (int)N(o, "mana_cost"));
            bool battle = kind == TzSpell.Kind.Battle || kind == TzSpell.Kind.Area;
            F(so, "coolDownDuration.baseAmount", battle ? (float)b.Pause("staff", 0) : (float)N(o, "cooldown"));
            if (battle)
            {
                P(so, "skillAttackType").enumValueIndex = 1;   // Normal: TzSpell gives the damage
                I(so, "damageInfo.damageType", 0);               // melee: at once
                F(so, "damageInfo.hitDistance", kind == TzSpell.Kind.Area ? 10f : 12f);
                F(so, "damageInfo.hitFov", 360f);
                P(so, "damageInfo.hitOnlySelectedTarget").boolValue = kind == TzSpell.Kind.Battle;
                P(so, "damageAmount.damageElement").objectReferenceValue = magic;
            }
            else
            {
                P(so, "skillAttackType").enumValueIndex = 0;
                switch (kind.Value)
                {
                    case TzSpell.Kind.Heal: case TzSpell.Kind.Armor: case TzSpell.Kind.Strength: case TzSpell.Kind.Mana:
                        P(so, "skillBuffType").enumValueIndex = 4;   // BuffToTarget (yourself if none)
                        P(so, "buffToUserIfNoTarget").boolValue = true;
                        F(so, "buffDistance.baseAmount", 10f);
                        P(so, "buff.noDuration").boolValue = true;
                        break;
                    case TzSpell.Kind.Other:   // рой мошек: the target stands 20 s
                        P(so, "skillBuffType").enumValueIndex = 7;   // BuffToEnemy
                        F(so, "buffDistance.baseAmount", 10f);
                        F(so, "buff.duration.baseAmount", 20f);
                        P(so, "buff.disallowMove").boolValue = true;
                        P(so, "buff.disallowAttack").boolValue = true;
                        P(so, "buff.disallowUseSkill").boolValue = true;
                        P(so, "buff.disallowUseItem").boolValue = true;
                        break;
                }
                if (kind == TzSpell.Kind.Armor)
                {
                    // Armour + (the mage's int + level): the buff's level carries it.
                    var armors = P(so, "buff.increaseArmors");
                    armors.arraySize = 0;
                    ArmorOf(armors, null, 1, 1);
                }
                if (kind == TzSpell.Kind.Strength) { F(so, "buff.increaseStatsRate.baseStats.hp", 0.02f); F(so, "buff.increaseStatsRate.statsIncreaseEachLevel.hp", 0.02f); }
                if (kind == TzSpell.Kind.Mana) { F(so, "buff.increaseStatsRate.baseStats.mp", 0.02f); F(so, "buff.increaseStatsRate.statsIncreaseEachLevel.mp", 0.02f); }
            }
            // Learning: the teacher's price once; a spell no teacher sells (or a quest gives for 0) comes only on scrolls and runes.
            int price = prices.TryGetValue(id, out var pr) ? pr : 0;
            var steps = P(so, "requirementEachLevels");
            steps.arraySize = 1;
            var step = steps.GetArrayElementAtIndex(0);
            step.FindPropertyRelative("disallow").boolValue = price <= 0;
            step.FindPropertyRelative("characterLevel").intValue = 0;
            step.FindPropertyRelative("skillPoint").floatValue = 0;
            step.FindPropertyRelative("gold").intValue = price;
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(spell);
            result[id] = spell;
        }
        Debug.Log($"[TzImport] spells: {result.Count} — {string.Join(", ", result.Keys)}");
        return result;
    }

    // ---- techniques -------------------------------------------------------------------------------------
    // id → accuracy shift, damage ×, extra damage (on min too?, str k, skill k, bare str k), twice, area, effect, effect shift
    static readonly Dictionary<string, (int hit, float dmg, bool extra, bool both, int strK, int skillK, int bareK, bool twice, bool area, TzTechnique.Effect fx, int fxShift)> TechniqueTable =
        new Dictionary<string, (int, float, bool, bool, int, int, int, bool, bool, TzTechnique.Effect, int)>
        {
            // Magic.kt technique (the busy times of быстрый удар and мельница are not carried over)
            ["p.b"] = (0, 0.7f, false, false, 0, 0, 0, false, false, TzTechnique.Effect.None, 0),
            ["p.d"] = (-15, 1f, false, false, 0, 0, 0, true, false, TzTechnique.Effect.None, 0),
            ["p.me"] = (-10, 1f, false, false, 0, 0, 0, false, true, TzTechnique.Effect.None, 0),
            ["p.m"] = (40, 0.7f, false, false, 0, 0, 0, false, false, TzTechnique.Effect.None, 0),
            ["p.n"] = (0, 0.6f, false, false, 0, 0, 0, false, false, TzTechnique.Effect.Stun, 40),
            ["p.r"] = (0, 0.4f, false, false, 0, 0, 0, false, false, TzTechnique.Effect.Disarm, 70),
            ["p.vw"] = (0, 0.2f, false, false, 0, 0, 0, false, false, TzTechnique.Effect.KnockShield, 40),
            ["p.g"] = (-30, 1f, true, true, 2, 1, 1, false, false, TzTechnique.Effect.None, 0),
            ["p.p"] = (-20, 1f, true, false, 3, 2, 2, false, false, TzTechnique.Effect.None, 0),
            ["p.s"] = (-10, 1f, true, false, 2, 1, 1, false, false, TzTechnique.Effect.None, 0),
        };
    /// <summary>Stances (p.d.s «остаться в седле» waits for horses).</summary>
    static readonly HashSet<string> StanceIds = new HashSet<string> { "p.d.c", "p.d.o", "p.d.re", "p.d.z", "p.d.u", "p.d.g", "p.d.n", "p.d.p", "p.d.r" };

    /// <summary>
    /// Techniques and stances of content/items/p.*.json as TzTechnique skills: the weapon's blow ×damage with
    /// the content cooldown, or a stance for the content time; learnt once for the teacher's price.
    /// Выбить с седла and остаться в седле wait for horses.
    /// </summary>
    static Dictionary<string, TzTechnique> Techniques(string root)
    {
        var result = new Dictionary<string, TzTechnique>();
        var prices = TeacherPrices(root);
        foreach (var file in Directory.GetFiles(Path.Combine(root, "content", "items"), "p.*.json").OrderBy(f => f))
        {
            var o = Json(file);
            string id = o.TryGetValue("id", out var iv) ? iv as string : null;
            if (id == null) continue;
            bool isStance = StanceIds.Contains(id);
            if (!isStance && !TechniqueTable.ContainsKey(id)) continue;
            string name = o.TryGetValue("name", out var nm) && nm is string s && s.Length > 0 ? char.ToUpper(s[0]) + s.Substring(1) : id;
            var t = Make<TzTechnique>($"{Generated}/Types/technique-{id}.asset");
            var so = new SerializedObject(t);
            S(so, "id", id);
            S(so, "defaultTitle", name);
            S(so, "defaultDescription", o.TryGetValue("description", out var dv) ? dv as string ?? "" : "");
            P(so, "skillType").enumValueIndex = 0;   // Active
            I(so, "maxLevel", 1);
            float time = (float)N(o, "cooldown");
            P(so, "stance").boolValue = isStance;
            if (isStance)
            {
                P(so, "skillAttackType").enumValueIndex = 0;
                P(so, "skillBuffType").enumValueIndex = 1;   // BuffToUser (TzTechnique applies it with the chance as level)
                F(so, "buff.duration.baseAmount", time);
                F(so, "buff.duration.amountIncreaseEachLevel", 0);
                if (id == "p.d.o")
                {
                    // Глухая оборона: the whole time you only stand and defend.
                    P(so, "buff.disallowAttack").boolValue = true;
                    P(so, "buff.disallowUseSkill").boolValue = true;
                    P(so, "buff.disallowUseItem").boolValue = true;
                }
                if (id == "p.d.p") P(so, "requireShield").boolValue = true;
            }
            else
            {
                var r = TechniqueTable[id];
                P(so, "skillAttackType").enumValueIndex = 2;   // BasedOnWeapon
                F(so, "weaponDamageMultiplicator.baseAmount", r.dmg);
                F(so, "coolDownDuration.baseAmount", time);
                I(so, "hitShift", r.hit);
                F(so, "damageMultiplier", r.dmg);
                P(so, "extraDamage").boolValue = r.extra;
                P(so, "extraOnMinToo").boolValue = r.both;
                I(so, "extraStr", r.strK);
                I(so, "extraSkill", r.skillK);
                I(so, "extraStrBare", r.bareK);
                P(so, "twice").boolValue = r.twice;
                P(so, "area").boolValue = r.area;
                P(so, "effect").enumValueIndex = (int)r.fx;
                I(so, "effectShift", r.fxShift);
                if (r.area)
                {
                    I(so, "damageInfo.damageType", 0);
                    F(so, "damageInfo.hitDistance", 2.5f);
                    F(so, "damageInfo.hitFov", 360f);
                    P(so, "damageInfo.hitOnlySelectedTarget").boolValue = false;
                }
            }
            int price = prices.TryGetValue(id, out var pr) ? pr : 0;
            var steps = P(so, "requirementEachLevels");
            steps.arraySize = 1;
            var step = steps.GetArrayElementAtIndex(0);
            step.FindPropertyRelative("disallow").boolValue = price <= 0;
            step.FindPropertyRelative("characterLevel").intValue = 0;
            step.FindPropertyRelative("skillPoint").floatValue = 0;
            step.FindPropertyRelative("gold").intValue = price;
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(t);
            result[id] = t;
        }
        Debug.Log($"[TzImport] techniques: {result.Count} — {string.Join(", ", result.Keys)}");
        return result;
    }

    /// <summary>
    /// A hidden skill whose buff the rules put on a character for some seconds (level = seconds):
    /// tz.stun — nothing at all (оглушение, рой мошек); tz.busy — no blows, skills or things, walking allowed (eating).
    /// </summary>
    static BaseSkill Carrier(string id, string title, bool noMove)
    {
        var skill = Make<Skill>($"{Generated}/Types/skill-{id}.asset");
        var so = new SerializedObject(skill);
        S(so, "id", id);
        S(so, "defaultTitle", title);
        P(so, "skillType").enumValueIndex = 0;
        P(so, "skillBuffType").enumValueIndex = 1;   // BuffToUser: TryGetBuff gives the buff
        F(so, "buff.duration.baseAmount", 1);
        F(so, "buff.duration.amountIncreaseEachLevel", 1);
        P(so, "buff.disallowMove").boolValue = noMove;
        P(so, "buff.disallowAttack").boolValue = true;
        P(so, "buff.disallowUseSkill").boolValue = true;
        P(so, "buff.disallowUseItem").boolValue = true;
        var steps = P(so, "requirementEachLevels");
        steps.arraySize = 1;
        steps.GetArrayElementAtIndex(0).FindPropertyRelative("disallow").boolValue = true;
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(skill);
        return skill;
    }

    /// <summary>Food's busy seconds and what is left after it (content/crafting.json "consumables").</summary>
    static void FoodExtras(string root, Dictionary<string, BaseItem> items)
    {
        var file = Path.Combine(root, "content", "crafting.json");
        if (!File.Exists(file) || !(Json(file).TryGetValue("consumables", out var cv) && cv is Dictionary<string, object> consumables)) return;
        foreach (var kv in consumables)
        {
            if (!(items.TryGetValue(kv.Key, out var it) && it is TzFoodItem food) || !(kv.Value is Dictionary<string, object> o)) continue;
            var so = new SerializedObject(food);
            float busy = (float)N(o, "busy");
            F(so, "busySeconds", busy);
            F(so, "useItemCooldown", busy);
            string leaves = o.TryGetValue("leaves", out var lv) ? lv as string : null;
            P(so, "leaves").objectReferenceValue = leaves != null && items.TryGetValue(leaves, out var left) ? left : null;
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(food);
        }
    }

    /// <summary>
    /// Death (Game.kt killPlayer): every thing, worn ones too, goes into the corpse for 10 minutes; only its
    /// owner and the killer may take from it at first (the framework's corpse looting). Money stays with the
    /// player: the framework keeps it apart from things and would only burn it.
    /// </summary>
    static void Death()
    {
        var map = AssetDatabase.LoadMainAssetAtPath(DemoMapInfo);
        var so = new SerializedObject(map);
        foreach (var f in new[] { "playerDeadDropsEquipWeapons", "playerDeadDropsEquipItems", "playerDeadDropsNonEquipItems" })
            P(so, f).enumValueIndex = 1;   // AlwaysDrop
        P(so, "playerDeadDropsGold").enumValueIndex = 0;
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(map);
        var gi = AssetDatabase.LoadAssetAtPath<GameInstance>(DemoGameInstance);
        var gso = new SerializedObject(gi);
        P(gso, "playerDeadDropItemMode").enumValueIndex = 1;   // CorpseLooting
        F(gso, "playerCorpseAppearDuration", 600);
        gso.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(gi);
    }

    /// <summary>Создать еду and Букет make things, chosen as in Magic.kt (createfood weights by repeating).</summary>
    static void AddFoodToSpells(Dictionary<string, TzSpell> spells, Dictionary<string, BaseItem> items)
    {
        void Fill(string spell, IEnumerable<(string id, int weight)> things)
        {
            if (!spells.TryGetValue(spell, out var s)) return;
            var list = new List<BaseItem>();
            foreach (var (id, weight) in things)
                if (items.TryGetValue(id, out var it)) for (int i = 0; i < weight; i++) list.Add(it);
                else Debug.Log($"[TzImport] {spell}: no {id}");
            var so = new SerializedObject(s);
            var makes = P(so, "makes");
            makes.arraySize = list.Count;
            for (int i = 0; i < list.Count; i++) makes.GetArrayElementAtIndex(i).objectReferenceValue = list[i];
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(s);
        }
        Fill("m.createfood", new[] { ("i.f.apple", 21), ("i.f.cabbage", 15), ("i.f.bread", 20), ("i.f.sandwich", 20), ("i.f.mushroom", 10), ("i.f.sausage", 15) });
        Fill("m.buket", "roftphlgvadibjeknmxs".Select(c => ("i.bc." + c, 1)));
    }

    const string DemoServerInstance = "Assets/Tz/BaseDemo/Prefabs/MMOServerInstance-CE.prefab";

    /// <summary>Puts TzLoginServerDataManager under the login server, which looks for one among its children.</summary>
    static void LoginDataManager()
    {
        var root = PrefabUtility.LoadPrefabContents(DemoServerInstance);
        try
        {
            var login = root.GetComponentInChildren<MultiplayerARPG.MMO.LoginNetworkManager>(true);
            if (login == null) { problems++; Debug.LogError("[TzImport] no LoginNetworkManager in " + DemoServerInstance); return; }
            if (login.GetComponentInChildren<TzLoginServerDataManager>(true) == null)
            {
                var go = new GameObject("TzLoginServerDataManager");
                go.transform.SetParent(login.transform, false);
                go.AddComponent<TzLoginServerDataManager>();
                PrefabUtility.SaveAsPrefabAsset(root, DemoServerInstance);
            }
        }
        finally { PrefabUtility.UnloadPrefabContents(root); }
    }

    // ---- items -------------------------------------------------------------------------------------------
    /// <summary>
    /// Every thing of content/items with its name and price; weapons, armour and shields take the new
    /// balance of content/balance/items.json: damage, pause (rate of fire 60 / pause), class crit over
    /// the fists' one, armour, required level and attributes (req = [str, dex, int]). Other things
    /// (trophies, food, gems, books, materials) are plain things in stacks for now.
    /// The price a trader pays (the framework has one per thing) is the price × the multiplier of the
    /// trader who buys such things in the old game (docs/mechanics-world.md §4.3).
    /// </summary>
    static Dictionary<string, BaseItem> Items(string root, TzBalance b, Dictionary<string, MultiplayerARPG.Attribute> attributes,
        Dictionary<string, WeaponType> weaponTypes, Dictionary<string, ArmorType> armorTypes, Dictionary<string, TzSpell> spells, DamageElement magic)
    {
        var result = new Dictionary<string, BaseItem>();
        var balance = Json(Path.Combine(root, "content", "balance", "items.json"));
        var kinds = new HashSet<string> { "weapon", "armor", "item", "food", "gem", "book" };
        foreach (var file in Directory.GetFiles(Path.Combine(root, "content", "items"), "*.json").OrderBy(f => f))
        {
            var src = Json(file);
            if (src == null || !(src.TryGetValue("id", out var iv) && iv is string id) || !(src.TryGetValue("kind", out var kv2) && kv2 is string kind) || !kinds.Contains(kind))
                continue;
            var o = balance.TryGetValue(id, out var ov) && ov is Dictionary<string, object> od ? od : src;
            bool weapon = kind == "weapon" && id.StartsWith("i.w.") && o != src;
            bool shield = kind == "armor" && id.StartsWith("i.a.s.") && o != src;
            string slot = kind == "armor" && id.StartsWith("i.a.") && id.Length > 5 && o != src ? id.Substring(4, 1) : null;
            bool equipment = weapon || shield || (slot != null && armorTypes.ContainsKey(slot));
            if ((kind == "weapon" || kind == "armor") && !equipment) continue;

            string name = src.TryGetValue("name", out var nm) && nm is string s && s.Length > 0 ? char.ToUpper(s[0]) + s.Substring(1) : id;
            double price = N(o, "price", N(src, "price"));
            Prices[id] = price;

            string path = $"{Generated}/Items/{id}.asset";
            string spellId = id.StartsWith("i.m.") ? "m." + id.Substring(4) : id.StartsWith("i.r.") ? "m." + id.Substring(4) : null;
            if (spellId != null && spells.TryGetValue(spellId, out var spellOfItem))
            {
                // A scroll (burns) or a rune (stays): casts its spell without learning it.
                var scroll = Make<SkillItem>(path);
                var sso = new SerializedObject(scroll);
                S(sso, "id", id);
                S(sso, "defaultTitle", name);
                I(sso, "sellPrice", (int)Math.Round(price * SellMultiplier(id)));
                I(sso, "maxStack", id.StartsWith("i.m.") ? 99 : 1);
                P(sso, "usingSkill").objectReferenceValue = spellOfItem;
                I(sso, "usingSkillLevel", 1);
                sso.ApplyModifiedPropertiesWithoutUndo();
                EditorUtility.SetDirty(scroll);
                result[id] = scroll;
                continue;
            }
            if (kind == "food" && o != src)
            {
                // Food and elixirs: a share of the maximum health and mana at once (TzFoodItem).
                var food = Make<TzFoodItem>(path);
                var fso = new SerializedObject(food);
                S(fso, "id", id);
                S(fso, "defaultTitle", name);
                I(fso, "sellPrice", (int)Math.Round(price * SellMultiplier(id)));
                I(fso, "maxStack", 999);
                F(fso, "healPct", (float)N(o, "healPct"));
                F(fso, "manaPct", (float)N(o, "manaPct"));
                fso.ApplyModifiedPropertiesWithoutUndo();
                EditorUtility.SetDirty(food);
                result[id] = food;
                continue;
            }
            AssetDatabase.DeleteAsset(path);
            if (!AssetDatabase.CopyAsset(DemoWeapon, path)) { problems++; Debug.LogError("[TzImport] cannot copy an item for " + id); continue; }
            var item = AssetDatabase.LoadAssetAtPath<Item>(path);
            var so = new SerializedObject(item);
            S(so, "id", id);
            S(so, "defaultTitle", name);
            I(so, "sellPrice", (int)Math.Round(price * SellMultiplier(id)));
            Zero(so, "increaseStats");
            F(so, "rateOfFire", 0);
            P(so, "weaponType").objectReferenceValue = null;
            P(so, "armorType").objectReferenceValue = null;
            F(so, "damageAmount.amount.baseAmount.min", 0);
            F(so, "damageAmount.amount.baseAmount.max", 0);
            F(so, "armorAmount.amount.baseAmount", 0);
            F(so, "armorAmount.amount.amountIncreaseEachLevel", 0);
            if (weapon)
            {
                string cls = o.TryGetValue("class", out var cv) && cv is string c && weaponTypes.ContainsKey(c) ? c : "sword";
                P(so, "itemType").enumValueIndex = 2;
                P(so, "weaponType").objectReferenceValue = weaponTypes[cls];
                var dmg = o.TryGetValue("dmg", out var dv) && dv is List<object> dl && dl.Count == 2 ? dl.Select(x => (float)(double)x).ToArray() : new[] { 1f, 2f };
                F(so, "damageAmount.amount.baseAmount.min", dmg[0]);
                F(so, "damageAmount.amount.baseAmount.max", dmg[1]);
                F(so, "damageAmount.amount.amountIncreaseEachLevel.min", 0);
                F(so, "damageAmount.amount.amountIncreaseEachLevel.max", 0);
                // Staves hit with magic, through armour (balance.md §7): the old verb says so.
                string verb = src.TryGetValue("verb", out var vb) ? vb as string : null;
                P(so, "damageAmount.damageElement").objectReferenceValue = verb == "магией" || verb == "молнией" ? magic : null;
                double pause = Math.Max(0.5, N(o, "pause", b.Pause(cls, 0)));
                F(so, "rateOfFire", (float)(60 / pause));
                F(so, "increaseStats.baseStats.criRate", (float)((b.CritBase(cls) - b.CritBase("hand")) / 100));
            }
            else if (!equipment)
            {
                P(so, "itemType").enumValueIndex = 0;   // Junk: a thing in a stack
                I(so, "maxStack", 999);
            }
            else
            {
                P(so, "itemType").enumValueIndex = shield ? 3 : 1;
                if (!shield) P(so, "armorType").objectReferenceValue = armorTypes[slot];
                F(so, "armorAmount.amount.baseAmount", (float)N(o, "armor"));
            }
            I(so, "requirement.level", equipment ? (int)N(o, "level", 1) : 0);
            var req = P(so, "requirement.attributeAmounts");
            if (req != null) req.arraySize = 0;
            if (req != null && equipment)
            {
                req.arraySize = 0;
                var r = o.TryGetValue("req", out var rv) && rv is List<object> rl ? rl.Select(x => (int)(double)x).ToArray() : new int[0];
                var ids = new[] { "str", "dex", "int" };
                for (int i = 0; i < Math.Min(3, r.Length); i++)
                {
                    if (r[i] <= 0) continue;
                    req.arraySize++;
                    var e = req.GetArrayElementAtIndex(req.arraySize - 1);
                    e.FindPropertyRelative("attribute").objectReferenceValue = attributes[ids[i]];
                    e.FindPropertyRelative("amount").floatValue = r[i];
                }
            }
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(item);
            result[id] = item;
        }
        Debug.Log($"[TzImport] items: {result.Count} — {result.Values.Count(i => i.IsWeapon())} weapons, {result.Values.Count(i => i.IsShield())} shields, {result.Values.Count(i => i.IsArmor())} armour, {result.Values.Count(i => i is TzFoodItem)} food, {result.Values.Count(i => i.IsJunk())} other");
        return result;
    }

    /// <summary>The base price of every thing made (content), for the traders' prices.</summary>
    static readonly Dictionary<string, double> Prices = new Dictionary<string, double>();

    /// <summary>What share of the price a trader pays for a thing (the old traders' sell multipliers).</summary>
    static double SellMultiplier(string id)
    {
        if (id.StartsWith("i.w.") || id.StartsWith("i.a.")) return 0.6;      // Плант, Силт
        if (id.StartsWith("i.f.b.")) return 0.5;                              // Витуан, Борн
        if (id.StartsWith("i.h.")) return 0.8;                                // Лексли: шкуры, клыки
        if (id.StartsWith("i.c.") || id.StartsWith("i.ring.") || id.StartsWith("i.oj.") || id.StartsWith("i.med.")) return 0.5;   // Махмет
        if (id.StartsWith("i.bc")) return 1.0;                                // Катрина
        if (id.StartsWith("i.log") || id.StartsWith("i.ruda")) return 0.8;   // Сарант, Ракс
        return 0.7;                                                           // Гостон: всё
    }

    /// <summary>Registers what was made in the demo's game database, so saved characters find their things.</summary>
    static void Database(IEnumerable<MultiplayerARPG.Attribute> attributes, IEnumerable<WeaponType> weaponTypes, IEnumerable<ArmorType> armorTypes, IEnumerable<BaseItem> items, IEnumerable<BaseSkill> skills, DamageElement magic)
    {
        var db = AssetDatabase.LoadMainAssetAtPath(DemoDatabase);
        var so = new SerializedObject(db);
        void Fill(string field, IEnumerable<UnityEngine.Object> objects)
        {
            var list = P(so, field);
            if (list == null) return;
            var arr = objects.ToArray();
            list.arraySize = arr.Length;
            for (int i = 0; i < arr.Length; i++) list.GetArrayElementAtIndex(i).objectReferenceValue = arr[i];
        }
        Fill("attributes", attributes.Cast<UnityEngine.Object>());
        Fill("weaponTypes", weaponTypes.Cast<UnityEngine.Object>());
        Fill("armorTypes", armorTypes.Cast<UnityEngine.Object>());
        Fill("items", items.Cast<UnityEngine.Object>());
        Fill("skills", skills.Cast<UnityEngine.Object>());
        Fill("damageElements", new UnityEngine.Object[] { magic });
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(db);
    }

    /// <summary>
    /// The pause between blows: the framework's attack lasts 60 / rate of fire seconds when the weapon has one.
    /// The default weapon (fists, and every monster's blow) gets 60 — one second; a monster's own pause
    /// comes through its rateOfFireRate (60 / (60 · (1 + rate)) = pause).
    /// </summary>
    static void DefaultWeapon()
    {
        var asset = AssetDatabase.LoadMainAssetAtPath(DemoWeapon);
        var so = new SerializedObject(asset);
        F(so, "rateOfFire", 60);
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(asset);
    }

    // ---- monsters ----------------------------------------------------------------------------------
    class Monster { public string Id, Name, Kind; public int Level; public GameObject Prefab; }

    static Dictionary<string, object> Json(string path) => TzJson.Parse(File.ReadAllText(path)) as Dictionary<string, object>;
    static double N(Dictionary<string, object> o, string k, double def = 0) => o.TryGetValue(k, out var v) && v is double d ? d : def;

    static List<Monster> Monsters(string root, TzBalance b, Dictionary<string, BaseItem> items, DamageElement magic)
    {
        var npcs = Json(Path.Combine(root, "content", "balance", "npcs.json"));
        var chosen = npcs
            .Where(kv => kv.Value is Dictionary<string, object> o && (string)(o.TryGetValue("kind", out var k) ? k : "") is string kind
                && (kind == "mob" || kind == "animal") && N(o, "level", 99) <= StartZoneMaxLevel)
            .Select(kv => (id: kv.Key, o: (Dictionary<string, object>)kv.Value))
            // Monsters first, then animals; by level.
            .OrderBy(x => (string)x.o["kind"] == "mob" ? 0 : 1).ThenBy(x => N(x.o, "level")).ThenBy(x => x.id)
            .ToList();
        var result = new List<Monster>();
        foreach (var (id, o) in chosen)
        {
            string name = id;
            var file = Path.Combine(root, "content", "npcs", id + ".json");
            if (File.Exists(file) && Json(file).TryGetValue("char", out var ch) && ch is Dictionary<string, object> c && c.TryGetValue("name", out var nm) && nm is string s && s.Length > 0)
                name = char.ToUpper(s[0]) + s.Substring(1);
            var m = new Monster { Id = id, Name = name, Kind = (string)o["kind"], Level = (int)N(o, "level", 1) };
            string dataPath = $"{Generated}/Monsters/{id}.asset";
            string prefabPath = $"{Generated}/Monsters/{id}.prefab";
            AssetDatabase.DeleteAsset(dataPath);
            AssetDatabase.DeleteAsset(prefabPath);
            if (!AssetDatabase.CopyAsset(DemoMonsterPrefab, prefabPath))
            { problems++; Debug.LogError("[TzImport] cannot copy the demo monster for " + id); continue; }

            // Our monster class (its own drop), with everything else the demo monster has.
            var data = ScriptableObject.CreateInstance<TzMonsterCharacter>();
            data.name = id;
            AssetDatabase.CreateAsset(data, dataPath);
            var so = new SerializedObject(data);
            var demo = new SerializedObject(AssetDatabase.LoadMainAssetAtPath(DemoMonster));
            var it = demo.GetIterator();
            for (bool more = it.NextVisible(true); more; more = it.NextVisible(false))
                if (it.name != "m_Script" && so.FindProperty(it.propertyPath) != null)
                    so.CopyFromSerializedProperty(it);
            Drops(so, file, items);
            S(so, "id", id);
            S(so, "defaultTitle", name);
            I(so, "defaultLevel", m.Level);
            // Monsters attack on sight, animals only answer (MonsterCharacteristic: 0 Normal, 1 Aggressive).
            P(so, "characteristic").enumValueIndex = m.Kind == "mob" ? 1 : 0;
            Zero(so, "stats.baseStats");
            Zero(so, "stats.statsIncreaseEachLevel");
            Zero(so, "stats.rateIncreaseEachLevel");
            F(so, "stats.baseStats.hp", (float)N(o, "hp", 10));
            F(so, "stats.baseStats.accuracy", (float)N(o, "accuracy"));
            F(so, "stats.baseStats.evasion", (float)N(o, "evasion"));
            F(so, "stats.baseStats.criRate", 0.03f);
            F(so, "stats.baseStats.criDmgRate", (float)b.CritMultiplier);
            F(so, "stats.baseStats.moveSpeed", 4);
            F(so, "stats.baseStats.atkSpeed", 1);
            double pause = Math.Max(0.5, N(o, "pause", 1.2));
            F(so, "stats.baseStats.rateOfFireRate", (float)(1 / pause - 1));
            var armors = P(so, "armors");
            armors.arraySize = 1;
            var armor = armors.GetArrayElementAtIndex(0);
            armor.FindPropertyRelative("damageElement").objectReferenceValue = null;
            armor.FindPropertyRelative("amount.baseAmount").floatValue = (float)N(o, "armor");
            armor.FindPropertyRelative("amount.amountIncreaseEachLevel").floatValue = 0;
            armor.FindPropertyRelative("amount.rateIncreaseEachLevel").floatValue = 0;
            ArmorOf(armors, magic, (float)N(o, "magicDefence"), 0);
            // A monster whose old blow is «магией»/«молнией» hits with magic.
            string verb = File.Exists(file) && Json(file).TryGetValue("war", out var wv) && wv is Dictionary<string, object> war && war.TryGetValue("verb", out var vv) ? vv as string : null;
            P(so, "damageAmount.damageElement").objectReferenceValue = verb == "магией" || verb == "молнией" ? magic : null;
            var dmg = o.TryGetValue("dmg", out var dv) && dv is List<object> dl && dl.Count == 2 ? dl.Select(x => (float)(double)x).ToArray() : new[] { 1f, 2f };
            F(so, "damageAmount.amount.baseAmount.min", dmg[0]);
            F(so, "damageAmount.amount.baseAmount.max", dmg[1]);
            F(so, "damageAmount.amount.amountIncreaseEachLevel.min", 0);
            F(so, "damageAmount.amount.amountIncreaseEachLevel.max", 0);
            F(so, "damageAmount.amount.rateIncreaseEachLevel.min", 0);
            F(so, "damageAmount.amount.rateIncreaseEachLevel.max", 0);
            int exp = (int)N(o, "exp", b.MonsterExp(m.Level)), gold = (int)N(o, "gold", b.MonsterGold(m.Level));
            I(so, "randomExp.baseAmount.min", exp);
            I(so, "randomExp.baseAmount.max", exp);
            I(so, "randomExp.amountIncreaseEachLevel.min", 0);
            I(so, "randomExp.amountIncreaseEachLevel.max", 0);
            I(so, "randomGold.baseAmount.min", gold / 2);
            I(so, "randomGold.baseAmount.max", gold);
            I(so, "randomGold.amountIncreaseEachLevel.min", 0);
            I(so, "randomGold.amountIncreaseEachLevel.max", 0);
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(data);

            // The entity: the demo's capsule with our data, its own network asset id (a copy keeps the demo's).
            var prefab = PrefabUtility.LoadPrefabContents(prefabPath);
            var entity = prefab.GetComponent<BaseMonsterCharacterEntity>();
            var eso = new SerializedObject(entity);
            P(eso, "characterDatabase").objectReferenceValue = data;
            S(eso, "entityTitle", name);
            eso.ApplyModifiedPropertiesWithoutUndo();
            var identity = prefab.GetComponent<LiteNetLibManager.LiteNetLibIdentity>();
            if (identity != null)
            {
                var iso = new SerializedObject(identity);
                P(iso, "assetId").stringValue = AssetDatabase.AssetPathToGUID(prefabPath);
                iso.ApplyModifiedPropertiesWithoutUndo();
            }
            prefab.name = id;
            PrefabUtility.SaveAsPrefabAsset(prefab, prefabPath);
            PrefabUtility.UnloadPrefabContents(prefab);
            m.Prefab = AssetDatabase.LoadAssetAtPath<GameObject>(prefabPath);
            result.Add(m);
            Debug.Log($"[TzImport] {id} «{name}» L{m.Level} {m.Kind}: hp {N(o, "hp")}, dmg {dmg[0]}–{dmg[1]}, pause {pause}");
        }
        return result;
    }

    /// <summary>
    /// The old drop of a monster (content/npcs/&lt;id&gt;.json): "itemsrnd" — chance %, amount min..max;
    /// "osvej" — trophies of the carcass, always (in the old game they were cut off with a knife).
    /// Money comes from the balance's gold, not from here.
    /// </summary>
    static void Drops(SerializedObject so, string npcFile, Dictionary<string, BaseItem> items)
    {
        var list = P(so, "tzDrops");
        if (list == null) return;
        list.arraySize = 0;
        if (!File.Exists(npcFile)) return;
        var npc = Json(npcFile);
        void Add(string itemId, double chance, int min, int max)
        {
            if (itemId == "i.money") return;
            if (!items.TryGetValue(itemId, out var item)) { Debug.Log($"[TzImport] drop {itemId} skipped: no such thing"); return; }
            list.arraySize++;
            var e = list.GetArrayElementAtIndex(list.arraySize - 1);
            e.FindPropertyRelative("item").objectReferenceValue = item;
            e.FindPropertyRelative("chance").floatValue = (float)chance;
            e.FindPropertyRelative("min").intValue = min;
            e.FindPropertyRelative("max").intValue = max;
        }
        if (npc.TryGetValue("itemsrnd", out var r) && r is List<object> rnd)
            foreach (var x in rnd.OfType<Dictionary<string, object>>())
                Add(x.TryGetValue("id", out var v) ? v as string : "", N(x, "chance", 100), (int)N(x, "min"), (int)N(x, "max", 1));
        if (npc.TryGetValue("osvej", out var t) && t is List<object> trophies)
            foreach (var x in trophies.OfType<Dictionary<string, object>>())
            {
                int count = (int)N(x, "count", 1);
                Add(x.TryGetValue("id", out var v) ? v as string : "", 100, count, count);
            }
    }

    /// <summary>The demo map's spawn areas get our monsters in turn, each at its own level, four at a time.</summary>
    static void Spawns(List<Monster> monsters)
    {
        if (monsters.Count == 0) return;
        var scene = EditorSceneManager.OpenScene(DemoMap, OpenSceneMode.Single);
        var areas = UnityEngine.Object.FindObjectsByType<MonsterSpawnArea>(FindObjectsInactive.Include, FindObjectsSortMode.None)
            .OrderBy(a => a.transform.position.x).ThenBy(a => a.transform.position.z).ToArray();
        for (int i = 0; i < areas.Length; i++)
        {
            var m = monsters[i % monsters.Count];
            var so = new SerializedObject(areas[i]);
            P(so, "prefab").objectReferenceValue = m.Prefab.GetComponent<BaseMonsterCharacterEntity>();
            I(so, "minLevel", m.Level);
            I(so, "maxLevel", m.Level);
            I(so, "minAmount", 4);
            I(so, "maxAmount", 4);
            so.ApplyModifiedPropertiesWithoutUndo();
            areas[i].gameObject.name = "Spawn " + m.Id;
        }
        EditorSceneManager.SaveScene(scene);
        Debug.Log($"[TzImport] {areas.Length} spawn areas on {DemoMap}");
    }

    // ---- traders ------------------------------------------------------------------------------------------
    /// <summary>The town's traders on the demo map for now (docs/mechanics-world.md §4.3).</summary>
    static readonly string[] Traders = { "n.Goston", "n.Plant", "n.Silt", "n.Silvia", "n.Leksli" };
    const string DemoMapInfo = "Assets/Tz/BaseDemo/GameData/Resources/MapInfos/BaseMap-CE.asset";
    const string DemoNpcDatabase = "Assets/Tz/BaseDemo/GameData/NpcDatabase-CE.asset";

    /// <summary>
    /// Traders with the old stock and prices: what is in their "bank" (content/npcs), each thing for
    /// round(price · the multiplier of their "buy" topic) (1 for things of price ≤ 1), their greeting.
    /// The framework's shop has no stock counts and no restocking: everything is always there.
    /// They stand in a half circle around the map's start point.
    /// </summary>
    static void Shops(string root, Dictionary<string, BaseItem> items)
    {
        var prefab = NpcPrefab();
        var mapInfo = AssetDatabase.LoadMainAssetAtPath(DemoMapInfo);
        var start = new SerializedObject(mapInfo).FindProperty("startPosition")?.vector3Value ?? Vector3.zero;
        EditorSceneManager.OpenScene(DemoMap, OpenSceneMode.Single);
        Physics.SyncTransforms();

        var db = AssetDatabase.LoadMainAssetAtPath(DemoNpcDatabase);
        var so = new SerializedObject(db);
        var maps = P(so, "maps");
        if (maps == null) return;
        maps.arraySize = 1;
        var map = maps.GetArrayElementAtIndex(0);
        map.FindPropertyRelative("mapInfo").objectReferenceValue = mapInfo;
        var npcs = map.FindPropertyRelative("npcs");
        npcs.arraySize = 0;
        for (int n = 0; n < Traders.Length; n++)
        {
            string id = Traders[n];
            var npcFile = Path.Combine(root, "content", "npcs", id + ".json");
            var dialogFile = Path.Combine(root, "content", "dialogs", id + ".json");
            if (!File.Exists(npcFile) || !File.Exists(dialogFile)) { problems++; Debug.LogError("[TzImport] no trader " + id); continue; }
            var npc = Json(npcFile);
            string name = npc.TryGetValue("char", out var ch) && ch is Dictionary<string, object> c && c.TryGetValue("name", out var nm) ? nm as string : id;
            var topics = Json(dialogFile).TryGetValue("topics", out var tp) ? tp as Dictionary<string, object> : null;
            string greeting = topics != null && topics.TryGetValue("begin", out var bg) && bg is Dictionary<string, object> begin && begin.TryGetValue("text", out var tx) ? tx as string : "";
            greeting = (greeting ?? "").Replace("<imja>", "путник").Replace("  ", " ");
            double mult = 1;
            if (topics != null && topics.TryGetValue("buy", out var buy) && buy is string bs
                && double.TryParse(bs.Split('|')[0], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var m) && m > 0)
                mult = m;

            // bank: "chance:min:max=item:…|…"
            var stock = new List<string>();
            if (npc.TryGetValue("bank", out var bk) && bk is string bank)
                foreach (var part in bank.Split('|'))
                {
                    int eq = part.IndexOf('=');
                    if (eq < 0) continue;
                    string itemId = part.Substring(eq + 1).Split(':')[0];
                    if (items.ContainsKey(itemId) && !stock.Contains(itemId)) stock.Add(itemId);
                }

            var dialog = Make<NpcDialog>($"{Generated}/Npcs/{id}-shop.asset");
            var dso = new SerializedObject(dialog);
            S(dso, "title", name);
            S(dso, "description", greeting);
            P(dso, "type").enumValueIndex = 2;   // NpcDialogType.Shop
            var sell = P(dso, "sellItems");
            sell.arraySize = stock.Count;
            for (int i = 0; i < stock.Count; i++)
            {
                double price = Prices.TryGetValue(stock[i], out var pr) ? pr : 1;
                var e = sell.GetArrayElementAtIndex(i);
                e.FindPropertyRelative("item").objectReferenceValue = items[stock[i]];
                e.FindPropertyRelative("level").intValue = 1;
                e.FindPropertyRelative("amount").intValue = 1;
                e.FindPropertyRelative("sellPrice").intValue = (int)Math.Max(1, Math.Round(price <= 1 ? price : price * mult));
            }
            dso.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(dialog);

            float angle = (n - (Traders.Length - 1) / 2f) * 30f;
            var pos = start + Quaternion.Euler(0, angle, 0) * new Vector3(0, 0, 6);
            pos.y = Physics.Raycast(new Vector3(pos.x, start.y + 300, pos.z), Vector3.down, out var hit, 1000f) ? hit.point.y : start.y;
            npcs.arraySize++;
            var entry = npcs.GetArrayElementAtIndex(npcs.arraySize - 1);
            entry.FindPropertyRelative("entityPrefab").objectReferenceValue = prefab;
            entry.FindPropertyRelative("position").vector3Value = pos;
            entry.FindPropertyRelative("rotation").vector3Value = new Vector3(0, angle + 180, 0);
            entry.FindPropertyRelative("title").stringValue = name;
            entry.FindPropertyRelative("startDialog").objectReferenceValue = dialog;
            Debug.Log($"[TzImport] trader {id} «{name}» at {pos}: {stock.Count} things, ×{mult}");
        }
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(db);
    }

    /// <summary>A trader on the map: a capsule with the framework's NpcEntity and its own network asset id.</summary>
    static NpcEntity NpcPrefab()
    {
        string path = $"{Generated}/Npcs/TzNpc.prefab";
        AssetDatabase.DeleteAsset(path);
        var go = new GameObject("TzNpc");
        go.AddComponent<NpcEntity>();
        var collider = go.AddComponent<CapsuleCollider>();
        collider.height = 2;
        collider.radius = 0.5f;
        collider.center = new Vector3(0, 1, 0);
        var body = GameObject.CreatePrimitive(PrimitiveType.Capsule);
        UnityEngine.Object.DestroyImmediate(body.GetComponent<Collider>());
        body.name = "Model";
        body.transform.SetParent(go.transform, false);
        body.transform.localPosition = new Vector3(0, 1, 0);
        PrefabUtility.SaveAsPrefabAsset(go, path);
        UnityEngine.Object.DestroyImmediate(go);

        var contents = PrefabUtility.LoadPrefabContents(path);
        var identity = contents.GetComponent<LiteNetLibManager.LiteNetLibIdentity>();
        if (identity != null)
        {
            var iso = new SerializedObject(identity);
            P(iso, "assetId").stringValue = AssetDatabase.AssetPathToGUID(path);
            iso.ApplyModifiedPropertiesWithoutUndo();
        }
        else { problems++; Debug.LogError("[TzImport] the trader prefab has no LiteNetLibIdentity"); }
        PrefabUtility.SaveAsPrefabAsset(contents, path);
        PrefabUtility.UnloadPrefabContents(contents);
        return AssetDatabase.LoadAssetAtPath<GameObject>(path).GetComponent<NpcEntity>();
    }

    // ---- font --------------------------------------------------------------------------------------
    static void CyrillicFont()
    {
        string path = $"{Generated}/AlegreyaSans-Dynamic.asset";
        var ttf = AssetDatabase.LoadAssetAtPath<Font>(FontPath);
        if (ttf == null) { problems++; Debug.LogError("[TzImport] no font at " + FontPath); return; }
        AssetDatabase.DeleteAsset(path);
        var fa = TMP_FontAsset.CreateFontAsset(ttf, 64, 6, UnityEngine.TextCore.LowLevel.GlyphRenderMode.SDFAA, 1024, 1024, AtlasPopulationMode.Dynamic, true);
        if (fa == null) { problems++; Debug.LogError("[TzImport] TextMesh Pro could not make a font of " + FontPath); return; }
        fa.name = "AlegreyaSans-Dynamic";
        AssetDatabase.CreateAsset(fa, path);
        foreach (var tex in fa.atlasTextures) { tex.name = fa.name + " Atlas"; AssetDatabase.AddObjectToAsset(tex, fa); }
        fa.material.name = fa.name + " Material";
        AssetDatabase.AddObjectToAsset(fa.material, fa);
        EditorUtility.SetDirty(fa);
        AssetDatabase.SaveAssets();

        var settings = AssetDatabase.LoadAssetAtPath<TMP_Settings>("Assets/TextMesh Pro/Resources/TMP Settings.asset");
        if (settings == null) { problems++; Debug.LogError("[TzImport] no TMP Settings"); return; }
        var so = new SerializedObject(settings);
        var list = P(so, "m_fallbackFontAssets");
        if (list == null) return;
        list.arraySize = 1;
        list.GetArrayElementAtIndex(0).objectReferenceValue = fa;
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(settings);
        Debug.Log("[TzImport] Cyrillic fallback font: " + path);
    }
}
#endif
