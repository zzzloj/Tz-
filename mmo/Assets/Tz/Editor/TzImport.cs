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
        Directory.CreateDirectory(Path.Combine(repoRoot, "mmo", Generated, "Monsters"));
        AssetDatabase.Refresh();
        ExpTable(balance);
        Player(balance);
        DefaultWeapon();
        var monsters = Monsters(repoRoot, balance);
        Spawns(monsters);
        CyrillicFont();
        AssetDatabase.SaveAssets();
        Debug.Log($"[TzImport] done: {monsters.Count} monsters, {problems} problems");
    }

    // ---- helpers ---------------------------------------------------------------------------------
    static SerializedProperty P(SerializedObject so, string path)
    {
        var p = so.FindProperty(path);
        if (p == null) { problems++; Debug.LogError($"[TzImport] no field {path} in {so.targetObject.name}"); }
        return p;
    }

    static void F(SerializedObject so, string path, float v) { var p = P(so, path); if (p != null) p.floatValue = v; }
    static void I(SerializedObject so, string path, int v) { var p = P(so, path); if (p != null) p.intValue = v; }
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
    /// A new character's stats with every attribute at its start value (balance.md §5): health, mana,
    /// accuracy and evasion by level, the base crit. Attributes and skills as stat sources come later.
    /// </summary>
    static void Player(TzBalance b)
    {
        var asset = AssetDatabase.LoadMainAssetAtPath(DemoPlayer);
        var so = new SerializedObject(asset);
        int a = b.AttrStart;
        Zero(so, "stats.statsIncreaseEachLevel");
        Zero(so, "stats.rateIncreaseEachLevel");
        F(so, "stats.baseStats.hp", b.HpMax(a, 1));
        F(so, "stats.statsIncreaseEachLevel.hp", (float)b.HpPerLevel);
        F(so, "stats.baseStats.mp", b.ManaMax(a, 1));
        F(so, "stats.statsIncreaseEachLevel.mp", (float)b.ManaPerLevel);
        F(so, "stats.baseStats.accuracy", (float)b.Accuracy(a, 0, 1));
        F(so, "stats.statsIncreaseEachLevel.accuracy", 1);
        F(so, "stats.baseStats.evasion", (float)b.Evasion(a, 0, 1));
        F(so, "stats.statsIncreaseEachLevel.evasion", 1);
        F(so, "stats.baseStats.criRate", (float)(b.CritBase("hand") / 100));
        F(so, "stats.baseStats.criDmgRate", (float)b.CritMultiplier);
        F(so, "stats.baseStats.blockRate", 0);
        F(so, "stats.baseStats.blockDmgRate", 0);
        F(so, "stats.baseStats.atkSpeed", 1);
        so.ApplyModifiedPropertiesWithoutUndo();
        EditorUtility.SetDirty(asset);
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
        fa.name = "AlegreyaSans-Dynamic";
        AssetDatabase.CreateAsset(fa, path);
        foreach (var tex in fa.atlasTextures) { tex.name = fa.name + " Atlas"; AssetDatabase.AddObjectToAsset(tex, fa); }
        fa.material.name = fa.name + " Material";
        AssetDatabase.AddObjectToAsset(fa.material, fa);
        EditorUtility.SetDirty(fa);
        AssetDatabase.SaveAssets();

        var settings = TMP_Settings.instance;
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
