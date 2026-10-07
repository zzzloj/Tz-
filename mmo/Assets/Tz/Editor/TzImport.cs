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
        foreach (var dir in new[] { "Monsters", "Items", "Types" })
            Directory.CreateDirectory(Path.Combine(repoRoot, "mmo", Generated, dir));
        AssetDatabase.Refresh();
        ExpTable(balance);
        var attributes = Attributes(balance);
        var weaponTypes = WeaponTypes();
        var armorTypes = ArmorTypes();
        var items = Items(repoRoot, balance, attributes, weaponTypes, armorTypes);
        Player(balance, attributes, items);
        DefaultWeapon();
        var monsters = Monsters(repoRoot, balance);
        Spawns(monsters);
        Database(attributes.Values, weaponTypes.Values, armorTypes.Values, items.Values);
        try { CyrillicFont(); }
        catch (Exception e) { problems++; Debug.LogError("[TzImport] Cyrillic font: " + e); }
        AssetDatabase.SaveAssets();
        Debug.Log($"[TzImport] done: {attributes.Count} attributes, {weaponTypes.Count} weapon types, {armorTypes.Count} armour slots, {items.Count} items, {monsters.Count} monsters, {problems} problems");
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
    static void Player(TzBalance b, Dictionary<string, MultiplayerARPG.Attribute> attributes, Dictionary<string, Item> items)
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
        if (items.TryGetValue(StartWeapon, out var knife))
            P(so, "rightHandEquipItem").objectReferenceValue = knife;
        else { problems++; Debug.LogError("[TzImport] no starting weapon " + StartWeapon); }
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(asset);
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
    static Dictionary<string, MultiplayerARPG.Attribute> Attributes(TzBalance b)
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
            if (id == "int") F(so, "statsIncreaseEachLevel.mp", (float)b.ManaPerInt);
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

    // ---- items -------------------------------------------------------------------------------------------
    /// <summary>
    /// Weapons, armour and shields of content/balance/items.json with their names from content/items:
    /// damage, pause (rate of fire 60 / pause), class crit over the fists' one, armour, the price,
    /// required level and attributes (req = [str, dex, int]).
    /// </summary>
    static Dictionary<string, Item> Items(string root, TzBalance b, Dictionary<string, MultiplayerARPG.Attribute> attributes,
        Dictionary<string, WeaponType> weaponTypes, Dictionary<string, ArmorType> armorTypes)
    {
        var result = new Dictionary<string, Item>();
        var all = Json(Path.Combine(root, "content", "balance", "items.json"));
        foreach (var kv in all.OrderBy(k => k.Key))
        {
            if (!(kv.Value is Dictionary<string, object> o)) continue;
            string type = o.TryGetValue("type", out var tv) ? tv as string : null;
            string id = kv.Key;
            bool weapon = type == "weapon" && id.StartsWith("i.w.");
            bool shield = type == "armor" && id.StartsWith("i.a.s.");
            string slot = type == "armor" && id.StartsWith("i.a.") && id.Length > 5 ? id.Substring(4, 1) : null;
            if (!weapon && !shield && (slot == null || !armorTypes.ContainsKey(slot))) continue;

            string name = id;
            var file = Path.Combine(root, "content", "items", id + ".json");
            if (File.Exists(file) && Json(file).TryGetValue("name", out var nm) && nm is string s && s.Length > 0)
                name = char.ToUpper(s[0]) + s.Substring(1);

            string path = $"{Generated}/Items/{id}.asset";
            AssetDatabase.DeleteAsset(path);
            if (!AssetDatabase.CopyAsset(DemoWeapon, path)) { problems++; Debug.LogError("[TzImport] cannot copy an item for " + id); continue; }
            var item = AssetDatabase.LoadAssetAtPath<Item>(path);
            var so = new SerializedObject(item);
            S(so, "id", id);
            S(so, "defaultTitle", name);
            I(so, "sellPrice", (int)N(o, "price"));
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
                double pause = Math.Max(0.5, N(o, "pause", b.Pause(cls, 0)));
                F(so, "rateOfFire", (float)(60 / pause));
                F(so, "increaseStats.baseStats.criRate", (float)((b.CritBase(cls) - b.CritBase("hand")) / 100));
            }
            else
            {
                P(so, "itemType").enumValueIndex = shield ? 3 : 1;
                if (!shield) P(so, "armorType").objectReferenceValue = armorTypes[slot];
                F(so, "armorAmount.amount.baseAmount", (float)N(o, "armor"));
            }
            I(so, "requirement.level", (int)N(o, "level", 1));
            var req = P(so, "requirement.attributeAmounts");
            if (req != null)
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
        Debug.Log($"[TzImport] items: {result.Count} — {result.Values.Count(i => i.IsWeapon())} weapons, {result.Values.Count(i => i.IsShield())} shields, {result.Values.Count(i => i.IsArmor())} armour");
        return result;
    }

    /// <summary>Registers what was made in the demo's game database, so saved characters find their things.</summary>
    static void Database(IEnumerable<MultiplayerARPG.Attribute> attributes, IEnumerable<WeaponType> weaponTypes, IEnumerable<ArmorType> armorTypes, IEnumerable<Item> items)
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

    static List<Monster> Monsters(string root, TzBalance b)
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
            if (!AssetDatabase.CopyAsset(DemoMonster, dataPath) || !AssetDatabase.CopyAsset(DemoMonsterPrefab, prefabPath))
            { problems++; Debug.LogError("[TzImport] cannot copy the demo monster for " + id); continue; }

            var data = AssetDatabase.LoadMainAssetAtPath(dataPath);
            var so = new SerializedObject(data);
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
