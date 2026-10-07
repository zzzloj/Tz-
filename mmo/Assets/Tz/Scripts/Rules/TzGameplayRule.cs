using System;
using System.Collections.Generic;
using MultiplayerARPG;
using Tz.Rules;
using UnityEngine;

/// <summary>
/// The game's combat rules (claude/balance.md, engine/server/.../Combat.kt) in the MMO framework, on top of
/// its DefaultGameplayRule. Numbers come from content/logic/balance.json (Resources/Tz/balance, copied there
/// by MmoBuild before a build). The framework's stats carry ours like this:
/// - accuracy, evasion — points, hit chance = 60 + accuracy − evasion within 15–95 %;
/// - armour of the default (physical) damage element — armour; of other elements — magic defence;
///   both stop a share a / (a + 50 + 10·attacker level), heavy weapons meet 25 % less armour, light ones 15 % more;
/// - criRate, criDmgRate — crit chance (a share, no floor: crit only from items) and its multiplier;
///   a crit never turns a miss into a hit;
/// - block only with a shield: 5 % + 0.5 % a point of dexterity (≤ 30 %) + items' blockRate; a blocked blow loses BlockCut (half);
/// - strength adds str/2 per 4 s of pause to a physical blow (not crossbows, not skills);
/// - experience: monsters' experience by the level gap, two training points a level (+2 every tenth) as stat points.
/// Weapon classes (pause, crit, penetration) are the ids of the framework's WeaponType assets: knife, sword, axe, …
/// </summary>
[CreateAssetMenu(fileName = "TzGameplayRule", menuName = "Tz/Gameplay Rule")]
public class TzGameplayRule : DefaultGameplayRule
{
    static TzBalance balance;

    /// <summary>The balance from Resources/Tz/balance.json; defaults of Balance.kt if it is missing.</summary>
    public static TzBalance Balance
    {
        get
        {
            if (balance == null)
            {
                var text = Resources.Load<TextAsset>("Tz/balance");
                if (text == null) Debug.LogWarning("[Tz] Resources/Tz/balance.json is missing: balance defaults");
                balance = text != null ? TzBalance.Parse(text.text) : new TzBalance(null);
            }
            return balance;
        }
    }

    /// <summary>Weapon class of a weapon: the id of its WeaponType ("hand" without a weapon).</summary>
    public static string WeaponClass(CharacterItem weapon)
    {
        var w = weapon.IsEmptySlot() ? null : weapon.GetWeaponItem();
        string id = w?.WeaponType != null ? w.WeaponType.Id : null;
        return string.IsNullOrEmpty(id) ? "hand" : id.ToLowerInvariant();
    }

    // The framework asks for the damage of an element and then, in the same expression, for the share
    // the armour stops, without the attacker: remember who strikes between the two calls (one thread).
    [ThreadStatic] static int strikingLevel;
    [ThreadStatic] static double strikingPen;

    public override float GetHitChance(BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver)
    {
        float acc = attacker.GetCaches().Stats.accuracy;
        float eva = damageReceiver.GetCaches().Stats.evasion;
        return (float)(Balance.HitChance(acc, eva) / 100.0);
    }

    public override bool RandomAttackHitOccurs(Vector3 fromPosition, BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver, Dictionary<DamageElement, MinMaxFloat> damageAmounts, CharacterItem weapon, BaseSkill skill, int skillLevel, int randomSeed, out bool isCritical, out bool isBlocked)
    {
        isCritical = false;
        isBlocked = false;
        if (attacker == null)
            return true;
        if (UnityEngine.Random.value >= GetHitChance(attacker, damageReceiver))
            return false;
        isCritical = UnityEngine.Random.value < GetCriticalChance(attacker, damageReceiver);
        isBlocked = UnityEngine.Random.value < GetBlockChance(attacker, damageReceiver);
        return true;
    }

    public override float RandomAttackDamage(Vector3 fromPosition, BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver, DamageElement damageElement, MinMaxFloat damageAmount, CharacterItem weapon, BaseSkill skill, int skillLevel, int randomSeed)
    {
        string cls = WeaponClass(weapon);
        strikingLevel = attacker != null ? attacker.Level : 1;
        strikingPen = Balance.Penetration(cls);
        float damage = base.RandomAttackDamage(fromPosition, attacker, damageReceiver, damageElement, damageAmount, weapon, skill, skillLevel, randomSeed);
        bool physical = damageElement == null || damageElement == GameInstance.Singleton.DefaultDamageElement;
        if (attacker != null && physical && skill == null && cls != "crossbow")
        {
            // Strength adds str/2 per 4 s of pause to every blow, so damage per second stays fair (balance.md §5).
            var w = weapon.IsEmptySlot() ? null : weapon.GetWeaponItem();
            double pause = w != null && w.RateOfFire > 0 ? 60.0 / w.RateOfFire : Balance.Pause(cls, 0);
            damage += (float)Balance.StrengthBonus(Mathf.RoundToInt(AttributeAmount(attacker, "str")), pause);
        }
        return damage;
    }

    /// <summary>A character's amount of the attribute with this id (str, dex, int); 0 if none.</summary>
    public static float AttributeAmount(BaseCharacterEntity character, string id)
    {
        var attributes = character.GetCaches().Attributes;
        if (attributes == null) return 0;
        foreach (var kv in attributes)
            if (kv.Key != null && kv.Key.Id == id) return kv.Value;
        return 0;
    }

    public override float GetDamageReducedByResistance(Dictionary<DamageElement, float> damageReceiverResistances, Dictionary<DamageElement, float> damageReceiverArmors, float damageAmount, DamageElement damageElement)
    {
        if (damageElement == null)
            damageElement = GameInstance.Singleton.DefaultDamageElement;
        float resistance;
        if (damageReceiverResistances.TryGetValue(damageElement, out resistance))
            damageAmount -= damageAmount * Mathf.Min(resistance, damageElement.MaxResistanceAmount);
        float armor;
        if (damageReceiverArmors.TryGetValue(damageElement, out armor))
        {
            bool physical = damageElement == GameInstance.Singleton.DefaultDamageElement;
            int level = strikingLevel > 0 ? strikingLevel : 1;
            double cut = physical
                ? Balance.ArmorCut(armor, level, strikingPen > 0 ? strikingPen : 1.0)
                : Balance.MagicCut(armor, level);
            damageAmount *= (float)(1 - cut);
        }
        return damageAmount;
    }

    public override float GetCriticalChance(BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver)
    {
        return Mathf.Max(0f, attacker.GetCaches().Stats.criRate);
    }

    public override float GetCriticalDamage(BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver, float damage)
    {
        float mult = attacker.GetCaches().Stats.criDmgRate;
        return damage * (mult > 0f ? mult : (float)Balance.CritMultiplier);
    }

    public override float GetBlockChance(BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver)
    {
        // Only with a shield: 5 % + 0.5 % a point of dexterity (+2 % a point of parrying, later), at most 30 %,
        // plus what items add to blockRate.
        var left = damageReceiver.GetCaches().LeftHandItem;
        if (left.IsEmptySlot() || left.GetShieldItem() == null)
            return 0f;
        int dex = Mathf.RoundToInt(AttributeAmount(damageReceiver, "dex"));
        return Mathf.Clamp01((float)(Balance.BlockChance(0, dex) / 100.0) + damageReceiver.GetCaches().Stats.blockRate);
    }

    public override float GetBlockDamage(BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver, float damage)
    {
        return damage * (float)(1 - Balance.BlockCut);
    }

    public override int GetTotalDamage(Vector3 fromPosition, EntityInfo instigator, DamageableEntity damageReceiver, float totalDamage, CharacterItem weapon, BaseSkill skill, int skillLevel)
    {
        // As on the server: a blow that got through deals at least 1.
        return totalDamage <= 0f ? 0 : Mathf.Max(1, Mathf.RoundToInt(totalDamage));
    }

    public override float GetRecoveryHpPerSeconds(BaseCharacterEntity character)
    {
        // The server heals RegenPerTick every RegenEvery seconds; here it flows evenly.
        return (float)(Balance.RegenPerTick(character.GetCaches().MaxHp, 0, false) / Balance.RegenEvery) + character.GetCaches().Stats.hpRecovery;
    }

    public override bool RewardExp(BaseCharacterEntity character, int exp, float multiplier, RewardGivenType rewardGivenType, int giverLevel, int sourceLevel, out int rewardedExp)
    {
        if (rewardGivenType == RewardGivenType.KillMonster || rewardGivenType == RewardGivenType.PartyShare)
            multiplier *= (float)Balance.ExpByGap(character.Level, sourceLevel);
        int before = character.Level;
        // Our training points instead of the framework's fixed stat and skill points.
        increaseStatPointEachLevel = 0;
        increaseSkillPointEachLevel = 0;
        bool up = base.RewardExp(character, exp, multiplier, rewardGivenType, giverLevel, sourceLevel, out rewardedExp);
        if (up && character is BasePlayerCharacterEntity player)
            for (int l = before + 1; l <= character.Level; l++)
                player.StatPoint += Balance.PointsForLevel(l);
        return up;
    }
}
