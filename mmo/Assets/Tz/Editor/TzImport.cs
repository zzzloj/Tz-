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
        var summons = SummonCreatures(balance, magic);
        var spells = Spells(repoRoot, balance, skills, magic, summons);
        var techniques = Techniques(repoRoot);
        var stun = Carrier("tz.stun", "Оглушение", true);
        var busy = Carrier("tz.busy", "Занят", false);
        var zombie = ZombieCarrier();
        var actions = Actions(repoRoot);
        var items = Items(repoRoot, balance, attributes, weaponTypes, armorTypes, spells, magic);
        AddFoodToSpells(spells, items);
        FoodExtras(repoRoot, items);
        Death();
        Player(balance, attributes, items, skills
            .Concat(spells.Select(kv => new KeyValuePair<string, BaseSkill>(kv.Key, kv.Value)))
            .Concat(techniques.Select(kv => new KeyValuePair<string, BaseSkill>(kv.Key, kv.Value)))
            .Concat(actions.Select(kv => new KeyValuePair<string, BaseSkill>(kv.Key, kv.Value)))
            .ToDictionary(kv => kv.Key, kv => kv.Value));
        LoginDataManager();
        DefaultWeapon();
        // The map from the old places (TzWorld, TzMap): its monsters are imported whatever their level.
        var npcBalance = Json(Path.Combine(repoRoot, "content", "balance", "npcs.json"));
        bool IsMonster(string id) => npcBalance.TryGetValue(id, out var o) && o is Dictionary<string, object> d
            && d.TryGetValue("kind", out var k) && (k as string == "mob" || k as string == "animal");
        var worlds = new List<Tz.World.TzWorld>();
        for (int i = 0; i < Tz.World.TzWorld.Maps.Length; i++)
            if (Tz.World.TzWorld.Maps[i].on) worlds.Add(Tz.World.TzWorld.Build(repoRoot, i, IsMonster));
        var monsters = Monsters(repoRoot, balance, items, magic, new HashSet<string>(worlds.SelectMany(w => w.Spawns).Select(s => s.Template)));
        var maps = MapAssets(worlds);
        TzMap.Begin(repoRoot);
        foreach (var w in worlds)
        {
            try { problems += TzMap.Build(w, maps[w.MapIndex].scene, maps[w.MapIndex].info); }
            catch (Exception e) { problems++; Debug.LogError($"[TzImport] map {w.MapName}: " + e); }
            Spawns(monsters, w, maps[w.MapIndex].scene);
        }
        People(repoRoot, items, worlds, maps);
        Heroes();
        Portals(worlds, maps);
        problems += TzMobile.Build();
        AddMonsterPrefabs(monsters.Select(m => m.Prefab).Concat(summons.Values));
        Database(attributes.Values, weaponTypes.Values, armorTypes.Values, items.Values, skills.Values.Concat(spells.Values).Concat(techniques.Values).Concat(new[] { stun, busy, zombie }).Concat(actions.Values), magic);
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
                e.FindPropertyRelative("skillLevel.amountIncreaseEachLevel").floatValue = 0;
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
        ("look", "Осторожность"), ("hiding", "Скрытность"), ("steal", "Кража"), ("steallook", "Подглядывание"),
        ("animaltaming", "Приручение животных"), ("animallore", "Изучение животных"), ("necro", "Некромантия"),
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
            case "m.s.wolf": case "m.s.skeleton": case "m.s.golem": case "m.s.fantom": case "m.s.tucha": case "m.s.demon":
                return TzSpell.Kind.Summon;   // дерево и волна ждут карты: им нужны деревья и вода рядом
            case "m.peace": return TzSpell.Kind.Peace;
            case "m.silence": return TzSpell.Kind.Silence;
            case "m.charm": return TzSpell.Kind.Charm;
            case "m.ressurect": return TzSpell.Kind.Resurrect;
            case "m.maddnes": return TzSpell.Kind.Madness;
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
    static Dictionary<string, TzSpell> Spells(string root, TzBalance b, Dictionary<string, BaseSkill> skills, DamageElement magic, Dictionary<string, GameObject> summons)
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
            if (summons.TryGetValue(id, out var creature))
                P(so, "summonPrefab").objectReferenceValue = creature.GetComponent<BaseMonsterCharacterEntity>();
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

    // ---- summoned creatures -------------------------------------------------------------------------------
    static readonly (string spell, string name, double hp, double dmg, double pause, bool magic)[] SummonTable =
    {
        // Pets.kt SUMMONS: health and blow as shares of an ordinary monster of the mage's level, pause in s.
        ("m.s.wolf", "Призванный волк", 0.6, 0.7, 1.2, false),
        ("m.s.skeleton", "Призванный скелет", 0.8, 0.8, 1.4, false),
        ("m.s.golem", "Призванный голем", 1.2, 0.9, 1.6, false),
        ("m.s.fantom", "Призванный фантом", 0.8, 0.8, 1.2, true),
        ("m.s.tucha", "Призванная грозовая туча", 0.8, 1.0, 1.2, true),
        ("m.s.demon", "Призванный демон", 1.4, 1.2, 1.5, false),
    };

    /// <summary>
    /// The creatures summoning spells call: their stats follow the level they are summoned at (the mage's), so
    /// every stat is written level by level 1..50 into the framework's per-level increments (Pets.kt summon).
    /// </summary>
    static Dictionary<string, GameObject> SummonCreatures(TzBalance b, DamageElement magic)
    {
        var result = new Dictionary<string, GameObject>();
        int max = b.MaxLevel;
        foreach (var c in SummonTable)
        {
            string id = "n." + c.spell.Substring(2);
            double Hp(int l) => Math.Max(1, Math.Round(b.MonsterHp(l) * c.hp, MidpointRounding.AwayFromZero));
            double Blow(int l) => b.MonsterDamagePer4s(l) * c.dmg * c.pause / 4;
            double Min(int l) => Math.Round(Blow(l) * 0.6, MidpointRounding.AwayFromZero);
            double Max(int l) => Math.Max(1, Math.Round(Blow(l) * 1.4, MidpointRounding.AwayFromZero));
            double Hit(int l) => (int)(1.6 * l + 4);
            double Dodge(int l) => (int)(1.3 * l);

            string dataPath = $"{Generated}/Monsters/{id}.asset";
            string prefabPath = $"{Generated}/Monsters/{id}.prefab";
            AssetDatabase.DeleteAsset(prefabPath);
            AssetDatabase.CopyAsset(DemoMonsterPrefab, prefabPath);
            var data = Make<TzMonsterCharacter>(dataPath);
            var so = new SerializedObject(data);
            var demo = new SerializedObject(AssetDatabase.LoadMainAssetAtPath(DemoMonster));
            var it = demo.GetIterator();
            for (bool more = it.NextVisible(true); more; more = it.NextVisible(false))
                if (it.name != "m_Script" && so.FindProperty(it.propertyPath) != null)
                    so.CopyFromSerializedProperty(it);
            S(so, "id", id);
            S(so, "defaultTitle", c.name);
            I(so, "defaultLevel", 1);
            P(so, "characteristic").enumValueIndex = 0;
            Zero(so, "stats.baseStats");
            Zero(so, "stats.statsIncreaseEachLevel");
            Zero(so, "stats.rateIncreaseEachLevel");
            F(so, "stats.baseStats.hp", (float)Hp(1));
            F(so, "stats.baseStats.accuracy", (float)Hit(1));
            F(so, "stats.baseStats.evasion", (float)Dodge(1));
            F(so, "stats.baseStats.criRate", 0.03f);
            F(so, "stats.baseStats.criDmgRate", (float)b.CritMultiplier);
            F(so, "stats.baseStats.moveSpeed", 5);
            F(so, "stats.baseStats.atkSpeed", 1);
            F(so, "stats.baseStats.rateOfFireRate", (float)(1 / c.pause - 1));
            var byLevel = P(so, "stats.statsIncreaseEachLevelByLevels");
            byLevel.arraySize = max - 1;
            for (int l = 2; l <= max; l++)
            {
                var e = byLevel.GetArrayElementAtIndex(l - 2);
                e.FindPropertyRelative("minLevel").intValue = l;
                e.FindPropertyRelative("statsIncreaseEachLevel.hp").floatValue = (float)(Hp(l) - Hp(l - 1));
                e.FindPropertyRelative("statsIncreaseEachLevel.accuracy").floatValue = (float)(Hit(l) - Hit(l - 1));
                e.FindPropertyRelative("statsIncreaseEachLevel.evasion").floatValue = (float)(Dodge(l) - Dodge(l - 1));
            }
            var armors = P(so, "armors");
            armors.arraySize = 0;
            ArmorOf(armors, magic, 2, 2);   // magic defence 2·level
            F(so, "damageAmount.amount.baseAmount.min", (float)Min(1));
            F(so, "damageAmount.amount.baseAmount.max", (float)Max(1));
            F(so, "damageAmount.amount.amountIncreaseEachLevel.min", 0);
            F(so, "damageAmount.amount.amountIncreaseEachLevel.max", 0);
            F(so, "damageAmount.amount.rateIncreaseEachLevel.min", 0);
            F(so, "damageAmount.amount.rateIncreaseEachLevel.max", 0);
            var dmgBy = P(so, "damageAmount.amount.amountIncreaseEachLevelByLevels");
            dmgBy.arraySize = max - 1;
            for (int l = 2; l <= max; l++)
            {
                var e = dmgBy.GetArrayElementAtIndex(l - 2);
                e.FindPropertyRelative("minLevel").intValue = l;
                e.FindPropertyRelative("amountIncreaseEachLevel.min").floatValue = (float)(Min(l) - Min(l - 1));
                e.FindPropertyRelative("amountIncreaseEachLevel.max").floatValue = (float)(Max(l) - Max(l - 1));
            }
            P(so, "damageAmount.damageElement").objectReferenceValue = c.magic ? magic : null;
            I(so, "randomExp.baseAmount.min", 0);
            I(so, "randomExp.baseAmount.max", 0);
            I(so, "randomGold.baseAmount.min", 0);
            I(so, "randomGold.baseAmount.max", 0);
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(data);

            var prefab = PrefabUtility.LoadPrefabContents(prefabPath);
            var eso = new SerializedObject(prefab.GetComponent<BaseMonsterCharacterEntity>());
            P(eso, "characterDatabase").objectReferenceValue = data;
            S(eso, "entityTitle", c.name);
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
            result[c.spell] = AssetDatabase.LoadAssetAtPath<GameObject>(prefabPath);
        }
        Debug.Log($"[TzImport] summoned creatures: {result.Count}");
        return result;
    }

    /// <summary>Monster prefabs the framework must know to spawn over the network (summons are not in any spawn area).</summary>
    static void AddMonsterPrefabs(IEnumerable<GameObject> prefabs)
    {
        var db = AssetDatabase.LoadMainAssetAtPath(DemoDatabase);
        var so = new SerializedObject(db);
        var list = P(so, "monsterCharacterEntities");
        if (list == null) return;
        foreach (var go in prefabs)
        {
            var e = go != null ? go.GetComponent<BaseMonsterCharacterEntity>() : null;
            if (e == null) continue;
            list.arraySize++;
            list.GetArrayElementAtIndex(list.arraySize - 1).objectReferenceValue = e;
        }
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(db);
    }

    /// <summary>The hidden skill tz.zombie: health and damage × level/100 (a raised zombie's weakness).</summary>
    static BaseSkill ZombieCarrier()
    {
        var skill = Make<Skill>($"{Generated}/Types/skill-tz.zombie.asset");
        var so = new SerializedObject(skill);
        S(so, "id", "tz.zombie");
        S(so, "defaultTitle", "Зомби");
        P(so, "skillType").enumValueIndex = 0;
        P(so, "skillBuffType").enumValueIndex = 1;
        P(so, "buff.noDuration").boolValue = true;
        // rate = −0.99 + 0.01·(level − 1) = level/100 − 1
        F(so, "buff.increaseStatsRate.baseStats.hp", -0.99f);
        F(so, "buff.increaseStatsRate.statsIncreaseEachLevel.hp", 0.01f);
        var dmg = P(so, "buff.increaseDamagesRate");
        dmg.arraySize = 1;
        var d = dmg.GetArrayElementAtIndex(0);
        d.FindPropertyRelative("damageElement").objectReferenceValue = null;
        d.FindPropertyRelative("amount.baseAmount.min").floatValue = -0.99f;
        d.FindPropertyRelative("amount.baseAmount.max").floatValue = -0.99f;
        d.FindPropertyRelative("amount.amountIncreaseEachLevel.min").floatValue = 0.01f;
        d.FindPropertyRelative("amount.amountIncreaseEachLevel.max").floatValue = 0.01f;
        var steps = P(so, "requirementEachLevels");
        steps.arraySize = 1;
        steps.GetArrayElementAtIndex(0).FindPropertyRelative("disallow").boolValue = true;
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(skill);
        return skill;
    }

    /// <summary>The actions of skills (TzAction): Приручить, Поднять труп, Подглядеть, Украсть — learnt with the skill, free.</summary>
    static Dictionary<string, TzAction> Actions(string root)
    {
        var result = new Dictionary<string, TzAction>();
        foreach (var (id, title, kind, mana, cooldown) in new[]
        {
            ("tz.tame", "Приручить", TzAction.Kind.Tame, 0, 5f),
            ("tz.raise", "Поднять труп", TzAction.Kind.Raise, 6, 10f),
            ("tz.peek", "Подглядеть", TzAction.Kind.Peek, 0, 5f),
            ("tz.steal", "Украсть", TzAction.Kind.Steal, 0, 10f),
            ("tz.hide", "Спрятаться", TzAction.Kind.Hide, 0, 5f),
        })
        {
            var a = Make<TzAction>($"{Generated}/Types/action-{id}.asset");
            var so = new SerializedObject(a);
            S(so, "id", id);
            S(so, "defaultTitle", title);
            P(so, "kind").enumValueIndex = (int)kind;
            P(so, "skillType").enumValueIndex = 0;
            I(so, "maxLevel", 1);
            I(so, "consumeMp.baseAmount", mana);
            F(so, "coolDownDuration.baseAmount", cooldown);
            if (kind == TzAction.Kind.Hide)
            {
                P(so, "skillBuffType").enumValueIndex = 1;   // BuffToUser: TzAction applies it on success
                F(so, "buff.duration.baseAmount", 60f);
                P(so, "buff.isHide").boolValue = true;
                F(so, "buff.removeBuffWhenAttackChance.baseAmount", 1f);
                F(so, "buff.removeBuffWhenUseSkillChance.baseAmount", 1f);
            }
            var steps = P(so, "requirementEachLevels");
            steps.arraySize = 1;
            var step = steps.GetArrayElementAtIndex(0);
            step.FindPropertyRelative("disallow").boolValue = false;
            step.FindPropertyRelative("skillPoint").floatValue = 0;
            step.FindPropertyRelative("gold").intValue = 0;
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(a);
            result[id] = a;
        }
        return result;
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
        Fill("m.createfood", new[] { ("i.f.apple", 21), ("i.f.gabbage", 15), ("i.f.bread", 20), ("i.f.sandwich", 20), ("i.f.mushroom", 10), ("i.f.sausage", 15) });
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
    /// The thing's price is its base price; a trader pays it × his own share (TzShopDialog, TzGameplayRule).
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
                I(sso, "sellPrice", (int)Math.Round(price));
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
                I(fso, "sellPrice", (int)Math.Round(price));
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
            I(so, "sellPrice", (int)Math.Round(price));
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

    static List<Monster> Monsters(string root, TzBalance b, Dictionary<string, BaseItem> items, DamageElement magic, HashSet<string> onMap)
    {
        TzCreatures.Load(root);
        var npcs = Json(Path.Combine(root, "content", "balance", "npcs.json"));
        var chosen = npcs
            .Where(kv => kv.Value is Dictionary<string, object> o && (string)(o.TryGetValue("kind", out var k) ? k : "") is string kind
                && (kind == "mob" || kind == "animal") && (N(o, "level", 99) <= StartZoneMaxLevel || onMap.Contains(kv.Key)))
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
            string tame = File.Exists(file) && Json(file).TryGetValue("char", out var cv2) && cv2 is Dictionary<string, object> chr && chr.TryGetValue("tame_difficulty", out var tv2)
                ? Convert.ToString(tv2, System.Globalization.CultureInfo.InvariantCulture) : null;
            I(so, "tameDifficulty", double.TryParse(tame, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var td) ? (int)td : 0);
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
            // The body lies a minute after death (a necromancer may raise it).
            F(eso, "destroyDelay", 60f);
            eso.ApplyModifiedPropertiesWithoutUndo();
            var identity = prefab.GetComponent<LiteNetLibManager.LiteNetLibIdentity>();
            if (identity != null)
            {
                var iso = new SerializedObject(identity);
                P(iso, "assetId").stringValue = AssetDatabase.AssetPathToGUID(prefabPath);
                iso.ApplyModifiedPropertiesWithoutUndo();
            }
            TzCreatures.Dress(prefab, id);   // its 3D creature, if there is one
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
    /// <summary>
    /// Monsters where the old world had them (TzWorld.Spawns: the place it respawns at, how many, the old
    /// respawn time min..max s — the area brings one back after their average). The demo's areas go.
    /// </summary>
    static void Spawns(List<Monster> monsters, Tz.World.TzWorld world, string scenePath)
    {
        var scene = EditorSceneManager.OpenScene(scenePath, OpenSceneMode.Single);
        int mask = 2097015;
        foreach (var old in UnityEngine.Object.FindObjectsByType<MonsterSpawnArea>(FindObjectsInactive.Include, FindObjectsSortMode.None))
        {
            mask = old.groundLayerMask.value;
            UnityEngine.Object.DestroyImmediate(old.gameObject);
        }
        var parent = GameObject.Find("Spawners");
        var byId = monsters.ToDictionary(m => m.Id);
        int made = 0, monstersMade = 0;
        foreach (var s in world.Spawns)
        {
            if (!byId.TryGetValue(s.Template, out var m)) { Debug.LogWarning($"[TzImport] spawn: no monster {s.Template} at {s.Place.Id}"); continue; }
            var go = new GameObject($"Spawn {m.Id} at {s.Place.Id}");
            if (parent != null) go.transform.SetParent(parent.transform, false);
            go.transform.position = new Vector3((float)s.Pos.X, 0, (float)s.Pos.Z);
            var area = go.AddComponent<MonsterSpawnArea>();
            var so = new SerializedObject(area);
            P(so, "groundLayerMask").intValue = mask;
            F(so, "randomRadius", 5f);
            P(so, "prefab").objectReferenceValue = m.Prefab.GetComponent<BaseMonsterCharacterEntity>();
            I(so, "minLevel", m.Level);
            I(so, "maxLevel", m.Level);
            I(so, "minAmount", s.Count);
            I(so, "maxAmount", s.Count);
            F(so, "destroyRespawnDelay", (float)((s.RespawnMin + s.RespawnMax) / 2));
            so.ApplyModifiedPropertiesWithoutUndo();
            made++; monstersMade += s.Count;
        }
        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        Debug.Log($"[TzImport] {made} spawn areas, {monstersMade} monsters on {scenePath}");
    }

    // ---- people ------------------------------------------------------------------------------------------
    const string DemoMapInfo = "Assets/Tz/BaseDemo/GameData/Resources/MapInfos/BaseMap-CE.asset";
    const string DemoNpcDatabase = "Assets/Tz/BaseDemo/GameData/NpcDatabase-CE.asset";
    static readonly System.Globalization.CultureInfo Inv = System.Globalization.CultureInfo.InvariantCulture;

    /// <summary>The greeting of an old dialog ("begin"), without its markup; PHP ones (eval:) get a plain hello.</summary>
    static string Greeting(Dictionary<string, object> topics)
    {
        string text = topics != null && topics.TryGetValue("begin", out var bg)
            ? bg as string ?? (bg is Dictionary<string, object> begin && begin.TryGetValue("text", out var tx) ? tx as string : null) : null;
        if (string.IsNullOrWhiteSpace(text) || text.StartsWith("eval:")) return "Здравствуй, путник.";
        text = text.Replace("<imja>", "путник").Replace("{{sid}}", "");
        text = System.Text.RegularExpressions.Regex.Replace(text, "<[^>]*>", "");
        text = text.Replace("\";", "").Replace("  ", " ").Trim();
        return text;
    }

    /// <summary>
    /// The people of the map (TzWorld.Npcs) at their places, with their old greeting. Traders (an old "bank"
    /// and a "buy" topic) get a shop with the old stock: what is in their bank (content/npcs), each thing for
    /// round(price · the multiplier of the "buy" topic) (1 for things of price ≤ 1), stock chance:min:max,
    /// restocked every "buy" period; they buy what their "sell" topic says (TzShopDialog). The banker keeps
    /// the player's storage. Teachers and quests are not here yet: they only greet.
    /// </summary>
    static void People(string root, Dictionary<string, BaseItem> items, List<Tz.World.TzWorld> worlds, Dictionary<int, (string scene, string info)> mapAssets)
    {
        var prefab = NpcPrefab();
        var bodies = new Dictionary<string, NpcEntity>();   // a prefab for each 3D body in use
        var db = AssetDatabase.LoadMainAssetAtPath(DemoNpcDatabase);
        var so = new SerializedObject(db);
        var maps = P(so, "maps");
        if (maps == null) return;
        maps.arraySize = worlds.Count;
        int shops = 0, talkers = 0;
        for (int wi = 0; wi < worlds.Count; wi++)
        {
        var world = worlds[wi];
        var map = maps.GetArrayElementAtIndex(wi);
        map.FindPropertyRelative("mapInfo").objectReferenceValue = AssetDatabase.LoadMainAssetAtPath(mapAssets[world.MapIndex].info);
        var npcs = map.FindPropertyRelative("npcs");
        npcs.arraySize = 0;
        foreach (var person in world.Npcs)
        {
            string id = person.Template;
            var npcFile = Path.Combine(root, "content", "npcs", id + ".json");
            var dialogFile = Path.Combine(root, "content", "dialogs", id + ".json");
            var npc = File.Exists(npcFile) ? Json(npcFile) : new Dictionary<string, object>();
            var topics = File.Exists(dialogFile) && Json(dialogFile).TryGetValue("topics", out var tp) ? tp as Dictionary<string, object> : null;
            string name = person.Name;
            string greeting = Greeting(topics);
            string slug = id.Replace('.', '-') + "-" + (shops + talkers);

            NpcDialog dialog;
            if (npc.TryGetValue("bank", out var bk) && bk is string bank && topics != null && topics.ContainsKey("buy"))
            {
                dialog = Shop($"{Generated}/Npcs/{slug}-shop.asset", name, greeting, bank, topics, items);
                shops++;
            }
            else
            {
                dialog = Make<NpcDialog>($"{Generated}/Npcs/{slug}-talk.asset");
                var dso = new SerializedObject(dialog);
                S(dso, "title", name);
                S(dso, "description", greeting);
                // The banker keeps things (NpcDialogType.PlayerStorage), the others only greet (Normal).
                P(dso, "type").enumValueIndex = id == "n.bankir" ? 7 : 0;
                dso.ApplyModifiedPropertiesWithoutUndo();
                EditorUtility.SetDirty(dialog);
                talkers++;
            }

            npcs.arraySize++;
            var entry = npcs.GetArrayElementAtIndex(npcs.arraySize - 1);
            string bodyName = TzCreatures.BodyFor(name);
            if (bodyName != null && !bodies.ContainsKey(bodyName)) bodies[bodyName] = NpcPrefab(bodyName);
            entry.FindPropertyRelative("entityPrefab").objectReferenceValue = bodyName != null ? bodies[bodyName] : prefab;
            entry.FindPropertyRelative("position").vector3Value = new Vector3((float)person.Pos.X, 0.05f, (float)person.Pos.Z);
            entry.FindPropertyRelative("rotation").vector3Value = new Vector3(0, (float)person.Rot, 0);
            entry.FindPropertyRelative("title").stringValue = name;
            entry.FindPropertyRelative("startDialog").objectReferenceValue = dialog;
        }
        }
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(db);
        Debug.Log($"[TzImport] people: {worlds.Sum(w => w.Npcs.Count)} on {worlds.Count} maps ({shops} traders, {talkers} others), 3D bodies: {(bodies.Count > 0 ? string.Join(", ", bodies.Keys) : "none")}");
    }

    // ---- maps and portals ----------------------------------------------------------------------------------
    const string MapsDir = "Assets/Tz/Generated/Maps";
    const string DemoWarpDatabase = "Assets/Tz/BaseDemo/GameData/WarpPortalDatabase-CE.asset";
    const string DemoWarpPortal = "Assets/Tz/BaseDemo/Prefabs/GamePlay/WarpPortalEntity-CE.prefab";

    /// <summary>The scenes of the built maps besides the demo's (MmoBuild puts them into the build).</summary>
    public static readonly List<string> MapScenes = new List<string>();

    /// <summary>
    /// A scene and a map info for every built map: the first map is the demo's (BaseMap-CE, where saved
    /// characters are), the others are copies of the demo scene and map info (its death rules too) in
    /// Assets/Tz/Generated/Maps named by TzWorld.Maps; all go into the game database.
    /// </summary>
    static Dictionary<int, (string scene, string info)> MapAssets(List<Tz.World.TzWorld> worlds)
    {
        var result = new Dictionary<int, (string, string)>();
        MapScenes.Clear();
        AssetDatabase.DeleteAsset(MapsDir);
        Directory.CreateDirectory(MapsDir);
        AssetDatabase.Refresh();
        var infos = new List<UnityEngine.Object>();
        foreach (var w in worlds)
        {
            var def = Tz.World.TzWorld.Maps[w.MapIndex];
            string scene = DemoMap, info = DemoMapInfo;
            if (def.scene != System.IO.Path.GetFileNameWithoutExtension(DemoMap))
            {
                scene = $"{MapsDir}/{def.scene}.unity";
                info = $"{MapsDir}/{def.scene}.asset";
                if (!AssetDatabase.CopyAsset(DemoMap, scene) || !AssetDatabase.CopyAsset(DemoMapInfo, info))
                { problems++; Debug.LogError($"[TzImport] cannot copy the demo map for {def.scene}"); continue; }
                var so = new SerializedObject(AssetDatabase.LoadMainAssetAtPath(info));
                P(so, "scene.sceneAsset").objectReferenceValue = AssetDatabase.LoadAssetAtPath<SceneAsset>(scene);
                S(so, "scene.sceneName", def.scene);
                S(so, "id", def.scene);
                so.ApplyModifiedPropertiesWithoutUndo();
                MapScenes.Add(scene);
            }
            result[w.MapIndex] = (scene, info);
            infos.Add(AssetDatabase.LoadMainAssetAtPath(info));
        }
        var dbo = new SerializedObject(AssetDatabase.LoadMainAssetAtPath(DemoDatabase));
        var list = P(dbo, "mapInfos");
        list.arraySize = infos.Count;
        for (int i = 0; i < infos.Count; i++) list.GetArrayElementAtIndex(i).objectReferenceValue = infos[i];
        dbo.ApplyModifiedPropertiesWithoutUndo();
        // The server starts a process for every map in its list (the demo lists only its own map).
        var server = PrefabUtility.LoadPrefabContents(DemoServerInstance);
        var spawner = server.GetComponentsInChildren<Component>(true).FirstOrDefault(c => c != null && c.GetType().Name == "MapSpawnNetworkManager");
        if (spawner != null)
        {
            var sso = new SerializedObject(spawner);
            // The list exists only in the standalone (server) build's serialization.
            var sm = sso.FindProperty("spawningMaps");
            if (sm != null)
            {
                sm.arraySize = infos.Count;
                for (int i = 0; i < infos.Count; i++) sm.GetArrayElementAtIndex(i).objectReferenceValue = infos[i];
                sso.ApplyModifiedPropertiesWithoutUndo();
                PrefabUtility.SaveAsPrefabAsset(server, DemoServerInstance);
            }
        }
        else { problems++; Debug.LogError("[TzImport] no MapSpawnNetworkManager in " + DemoServerInstance); }
        PrefabUtility.UnloadPrefabContents(server);
        AssetDatabase.SaveAssets();
        Debug.Log($"[TzImport] maps: {string.Join(", ", worlds.Select(w => w.MapName))}");
        return result;
    }

    /// <summary>
    /// The old exits between places of two built maps as the framework's warp portals (WarpPortalDatabase):
    /// at the end of the path stub to the other map, entering it takes the player to that place on the other map.
    /// </summary>
    static void Portals(List<Tz.World.TzWorld> worlds, Dictionary<int, (string scene, string info)> mapAssets)
    {
        var portal = AssetDatabase.LoadAssetAtPath<GameObject>(DemoWarpPortal)?.GetComponent<WarpPortalEntity>();
        if (portal == null) { problems++; Debug.LogError("[TzImport] no warp portal prefab " + DemoWarpPortal); return; }
        var so = new SerializedObject(AssetDatabase.LoadMainAssetAtPath(DemoWarpDatabase));
        var maps = P(so, "maps");
        maps.arraySize = worlds.Count;
        int total = 0;
        for (int wi = 0; wi < worlds.Count; wi++)
        {
            var w = worlds[wi];
            var m = maps.GetArrayElementAtIndex(wi);
            m.FindPropertyRelative("mapInfo").objectReferenceValue = AssetDatabase.LoadMainAssetAtPath(mapAssets[w.MapIndex].info);
            var list = m.FindPropertyRelative("warpPortals");
            var exits = w.Exits.Where(e => e.TargetMap >= 0 && mapAssets.ContainsKey(e.TargetMap)).ToList();
            list.arraySize = exits.Count;
            for (int k = 0; k < exits.Count; k++)
            {
                var e = exits[k];
                var el = list.GetArrayElementAtIndex(k);
                el.FindPropertyRelative("entityPrefab").objectReferenceValue = portal;
                el.FindPropertyRelative("position").vector3Value = new Vector3((float)e.Pos.X, 0.5f, (float)e.Pos.Z);
                el.FindPropertyRelative("rotation").vector3Value = new Vector3(0, (float)e.Rot, 0);
                el.FindPropertyRelative("warpPortalType").enumValueIndex = 0;
                el.FindPropertyRelative("warpToMapInfo").objectReferenceValue = AssetDatabase.LoadMainAssetAtPath(mapAssets[e.TargetMap].info);
                el.FindPropertyRelative("warpToPosition").vector3Value = new Vector3((float)e.TargetPos.X, 0.5f, (float)e.TargetPos.Z);
                el.FindPropertyRelative("warpOverrideRotation").boolValue = false;
            }
            total += exits.Count;
            Debug.Log($"[TzImport] portals of {w.MapName}: {string.Join(", ", exits.GroupBy(e => e.TargetMap).Select(g => $"{g.Count()} to {Tz.World.TzWorld.Maps[g.Key].name}"))}");
        }
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(so.targetObject);
        Debug.Log($"[TzImport] portals: {total}");
    }

    static NpcDialog Shop(string path, string name, string greeting, string bank, Dictionary<string, object> topics, Dictionary<string, BaseItem> items)
    {
        double mult = 1, period = 1200;
        if (topics.TryGetValue("buy", out var buy) && buy is string bs)
        {
            var bp = bs.Split('|');
            if (double.TryParse(bp[0], System.Globalization.NumberStyles.Float, Inv, out var m) && m > 0) mult = m;
            if (bp.Length > 1 && double.TryParse(bp[1], System.Globalization.NumberStyles.Float, Inv, out var per) && per > 0) period = per;
        }
        // "sell": "share|prefix:prefix…" — what the trader buys and for what share; no topic — buys nothing.
        double buys = 0;
        var filters = new List<string>();
        if (topics.TryGetValue("sell", out var sl) && sl is string ss)
        {
            var sp = ss.Split('|');
            double.TryParse(sp[0], System.Globalization.NumberStyles.Float, Inv, out buys);
            if (sp.Length > 1) filters.AddRange(sp[1].Split(':').Where(f => f.Length > 0));
        }
        // bank: "chance:min:max=item:…|…"
        var stock = new List<(string id, double chance, int min, int max)>();
        foreach (var part in bank.Split('|'))
        {
            int eq = part.IndexOf('=');
            if (eq < 0) continue;
            string itemId = part.Substring(eq + 1).Split(':')[0];
            var q = part.Substring(0, eq).Split(':');
            double chance0 = q.Length > 0 && double.TryParse(q[0], System.Globalization.NumberStyles.Float, Inv, out var c0) ? c0 : 100;
            int mn = q.Length > 1 && int.TryParse(q[1], out var c1) ? c1 : 1, mx = q.Length > 2 && int.TryParse(q[2], out var c2) ? c2 : mn;
            if (items.ContainsKey(itemId) && !stock.Any(x => x.id == itemId)) stock.Add((itemId, chance0, mn, mx));
        }

        var dialog = Make<TzShopDialog>(path);
        var dso = new SerializedObject(dialog);
        S(dso, "title", name);
        S(dso, "description", greeting);
        P(dso, "type").enumValueIndex = 2;   // NpcDialogType.Shop
        var sell = P(dso, "sellItems");
        sell.arraySize = stock.Count;
        var chances = P(dso, "stockChance"); chances.arraySize = stock.Count;
        var mins = P(dso, "stockMin"); mins.arraySize = stock.Count;
        var maxs = P(dso, "stockMax"); maxs.arraySize = stock.Count;
        for (int i = 0; i < stock.Count; i++)
        {
            double price = Prices.TryGetValue(stock[i].id, out var pr) ? pr : 1;
            var e = sell.GetArrayElementAtIndex(i);
            e.FindPropertyRelative("item").objectReferenceValue = items[stock[i].id];
            e.FindPropertyRelative("level").intValue = 1;
            e.FindPropertyRelative("amount").intValue = 1;
            e.FindPropertyRelative("sellPrice").intValue = (int)Math.Max(1, Math.Round(price <= 1 ? price : price * mult));
            chances.GetArrayElementAtIndex(i).floatValue = (float)stock[i].chance;
            mins.GetArrayElementAtIndex(i).intValue = stock[i].min;
            maxs.GetArrayElementAtIndex(i).intValue = stock[i].max;
        }
        F(dso, "restockSeconds", (float)period);
        F(dso, "buyMultiplier", (float)buys);
        var bf = P(dso, "buyFilters");
        bf.arraySize = filters.Count;
        for (int i = 0; i < filters.Count; i++) bf.GetArrayElementAtIndex(i).stringValue = filters[i];
        dso.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(dialog);
        Debug.Log($"[TzImport] trader «{name}»: {stock.Count} things, ×{mult}, buys ×{buys} {string.Join(":", filters)}");
        return dialog;
    }

    /// <summary>A trader on the map: a capsule with the framework's NpcEntity and its own network asset id.</summary>
    static NpcEntity NpcPrefab(string body = null)
    {
        string path = body == null ? $"{Generated}/Npcs/TzNpc.prefab" : $"{Generated}/Npcs/TzNpc-{body}.prefab";
        AssetDatabase.DeleteAsset(path);
        var go = new GameObject(body == null ? "TzNpc" : "TzNpc-" + body);
        go.AddComponent<NpcEntity>();
        var collider = go.AddComponent<CapsuleCollider>();
        collider.height = 2;
        collider.radius = 0.5f;
        collider.center = new Vector3(0, 1, 0);
        if (body != null) TzCreatures.DressPerson(go.transform, body);   // the 3D townsman (TzCreatures)
        else
        {
            var capsule = GameObject.CreatePrimitive(PrimitiveType.Capsule);
            UnityEngine.Object.DestroyImmediate(capsule.GetComponent<Collider>());
            capsule.name = "Model";
            capsule.transform.SetParent(go.transform, false);
            capsule.transform.localPosition = new Vector3(0, 1, 0);
        }
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

    // ---- heroes ------------------------------------------------------------------------------------
    const string DemoPlayerPrefab = "Assets/Tz/BaseDemo/Prefabs/GamePlay/CharacterEntities/BaseCharacter-CE.prefab";

    /// <summary>
    /// The hero and the heroine (content/art/models hero-m, hero-f): copies of the demo's player entity with
    /// the 3D figure (TzCreatures) are the game's player entities — the character creation offers both.
    /// Without both models the demo's capsule stays.
    /// </summary>
    static void Heroes()
    {
        var made = new List<UnityEngine.Object>();
        foreach (var (model, title) in new[] { ("hero-m", "Герой"), ("hero-f", "Героиня") })
        {
            if (!TzCreatures.Has(model)) continue;
            string path = $"{Generated}/Characters/TzHero-{model}.prefab";
            Directory.CreateDirectory($"{Generated}/Characters");
            AssetDatabase.DeleteAsset(path);
            if (!AssetDatabase.CopyAsset(DemoPlayerPrefab, path)) { problems++; Debug.LogError("[TzImport] cannot copy " + DemoPlayerPrefab); return; }
            var prefab = PrefabUtility.LoadPrefabContents(path);
            var identity = prefab.GetComponent<LiteNetLibManager.LiteNetLibIdentity>();
            if (identity != null)
            {
                var iso = new SerializedObject(identity);
                P(iso, "assetId").stringValue = AssetDatabase.AssetPathToGUID(path);
                iso.ApplyModifiedPropertiesWithoutUndo();
            }
            var entity = prefab.GetComponent<BasePlayerCharacterEntity>();
            if (entity != null)
            {
                var eso = new SerializedObject(entity);
                S(eso, "entityTitle", title);
                eso.ApplyModifiedPropertiesWithoutUndo();
            }
            TzCreatures.Dress(prefab, model);
            prefab.name = "TzHero-" + model;
            PrefabUtility.SaveAsPrefabAsset(prefab, path);
            PrefabUtility.UnloadPrefabContents(prefab);
            made.Add(AssetDatabase.LoadAssetAtPath<GameObject>(path).GetComponent<BasePlayerCharacterEntity>());
        }
        if (made.Count < 2) { Debug.Log("[TzImport] heroes: no 3D hero and heroine yet, the demo's capsule stays"); return; }
        var db = AssetDatabase.LoadMainAssetAtPath(DemoDatabase);
        var so = new SerializedObject(db);
        var list = P(so, "playerCharacterEntities");
        if (list == null) return;
        list.arraySize = made.Count;
        for (int i = 0; i < made.Count; i++) list.GetArrayElementAtIndex(i).objectReferenceValue = made[i];
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(db);
        Debug.Log("[TzImport] heroes: " + string.Join(", ", made.Select(m => m.name)));
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
