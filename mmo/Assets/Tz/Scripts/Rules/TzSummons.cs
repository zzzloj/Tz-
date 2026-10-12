using MultiplayerARPG;
using UnityEngine;

/// <summary>
/// Subordinates of our game (Pets.kt): summoned creatures, charmed and tamed animals, raised zombies.
/// They are the framework's summons of the Custom type whose dataId is the monster prefab's hash asset id
/// (mmo/tools/patch_kit.py custom_summons teaches the framework to find the prefab by it); this class is the
/// framework's custom summon manager (set by TzGameplayRule): a summon goes when its time is up or it dies.
/// A character keeps at most 3 of them (Pets.kt: the first 2 stay when a third comes).
/// </summary>
public class TzSummons : ICustomSummonManager
{
    public static readonly TzSummons Instance = new TzSummons();
    public const int MaxSubordinates = 3;

    public bool GetPrefab(out BaseMonsterCharacterEntity prefab
#if !DISABLE_ADDRESSABLES
        , out AssetReferenceBaseMonsterCharacterEntity addressablePrefab
#endif
        )
    {
        prefab = null;
#if !DISABLE_ADDRESSABLES
        addressablePrefab = null;
#endif
        return false;
    }

    public void UnSummon(CharacterSummon characterSummon, BaseCharacterEntity summoner) { }

    public void Update(CharacterSummon characterSummon, float deltaTime) { }

    public bool ShouldRemove(CharacterSummon characterSummon, ICharacterData characterData) =>
        characterSummon.summonRemainsDuration <= 0f;

    /// <summary>Calls a new creature of [prefab] at [level] for [seconds] to follow and guard [summoner].</summary>
    public static BaseMonsterCharacterEntity Summon(BaseCharacterEntity summoner, BaseMonsterCharacterEntity prefab, int level, float seconds)
    {
        if (summoner == null || prefab == null || !summoner.IsServer) return null;
        var s = CharacterSummon.Create(SummonType.Custom, string.Empty, prefab.Identity.HashAssetId);
        s.Summon(summoner, Mathf.Max(1, level), seconds);
        summoner.Summons.Add(s);
        Limit(summoner);
        return s.CacheEntity;
    }

    /// <summary>Makes a creature already in the world (a charmed or tamed animal) follow [summoner] for [seconds].</summary>
    public static void Adopt(BaseCharacterEntity summoner, BaseMonsterCharacterEntity creature, float seconds)
    {
        if (summoner == null || creature == null || creature.IsDead() || !summoner.IsServer) return;
        var s = CharacterSummon.Create(SummonType.Custom, string.Empty, creature.Identity.HashAssetId);
        s.CacheEntity = creature;
        s.objectId = creature.ObjectId;
        s.summonRemainsDuration = seconds;
        s.level = creature.Level;
        creature.Summon(summoner, SummonType.Custom, creature.Level);
        summoner.Summons.Add(s);
        Limit(summoner);
    }

    /// <summary>Whether the creature already has a master.</summary>
    public static bool HasMaster(BaseMonsterCharacterEntity creature) => creature != null && creature.IsSummoned;

    static void Limit(BaseCharacterEntity summoner)
    {
        int count = 0;
        for (int i = summoner.Summons.Count - 1; i >= 0; i--)
        {
            if (summoner.Summons[i].type != SummonType.Custom) continue;
            if (++count <= MaxSubordinates) continue;
            // Too many: the oldest go.
            var old = summoner.Summons[i];
            summoner.Summons.RemoveAt(i);
            old.UnSummon(summoner);
        }
    }
}
