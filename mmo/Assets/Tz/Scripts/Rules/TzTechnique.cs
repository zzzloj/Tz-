using System.Collections.Generic;
using MultiplayerARPG;
using Tz.Rules;
using UnityEngine;

/// <summary>
/// A technique p.* or a defensive stance p.d.* of our game (content/items/p.*.json, Magic.kt technique),
/// as a framework skill. Not with ranged weapons; no mana; learnt once for the teacher's price.
/// - Techniques strike with the weapon (×damage), their accuracy shifted by hitShift and lowered by
///   intelligence over 2 (3 a point); some add strength and weapon skill to the damage, some have an effect
///   afterwards (stun, knock the weapon or shield out) with the chance accuracy − effectShift %.
/// - Stances last their time (the content "cooldown") with a chance kept in the buff's level; one at a time.
///   Their effects are in TzGameplayRule.
/// </summary>
public class TzTechnique : Skill
{
    public enum Effect { None, Stun, Disarm, KnockShield }

    [Header("Tz: technique")]
    public bool stance;
    [Tooltip("Accuracy shift of the technique (points)")]
    public int hitShift;
    [Tooltip("Damage multiplier of the blow")]
    public float damageMultiplier = 1f;
    [Tooltip("Extra damage: strength × k + weapon skill × k (bare hands: strength × handK + hand skill)")]
    public bool extraDamage;
    public bool extraOnMinToo;
    public int extraStr = 2, extraSkill = 1, extraStrBare = 1;
    [Tooltip("Strikes twice (двойной удар)")]
    public bool twice;
    [Tooltip("Hits everyone around (мельница): uses this skill's damage info, not the weapon's")]
    public bool area;
    public Effect effect;
    [Tooltip("Effect chance = accuracy − this, percent")]
    public int effectShift;
    [Tooltip("Stun of Удар по ногам, seconds")]
    public float stunSeconds = 15f;

    static TzBalance B => TzGameplayRule.Balance;

    /// <summary>Intelligence spoils techniques: 3 accuracy a point over 2 (Magic.kt technique intPenalty).</summary>
    public static int IntPenalty(ICharacterData c) => Mathf.Max(0, TzSpell.Int(c) - 2) * 3;

    /// <summary>The character's accuracy with this technique (points).</summary>
    public int Accuracy(BaseCharacterEntity c, CharacterItem weapon)
    {
        string cls = TzGameplayRule.WeaponClass(weapon);
        float acc = c.GetCaches().Stats.accuracy + (float)B.AccPerWeaponSkill * TzGameplayRule.SkillLevel(c, TzGameplayRule.WeaponSkill(cls));
        return Mathf.RoundToInt(acc) - IntPenalty(c) + hitShift;
    }

    /// <summary>The flat damage p.g/p.p/p.s add: with a weapon str·k + coldweapon·k, bare str·k + hand.</summary>
    public int Extra(ICharacterData c, CharacterItem weapon)
    {
        if (!extraDamage) return 0;
        int str = TzSpell.Str(c);
        bool armed = !weapon.IsEmptySlot() && weapon.GetWeaponItem() != null && TzGameplayRule.WeaponClass(weapon) != "hand";
        return armed ? str * extraStr + TzGameplayRule.SkillLevel(c, "coldweapon") * extraSkill
                     : str * extraStrBare + TzGameplayRule.SkillLevel(c, "hand");
    }

    public override bool TryGetDamageInfo(BaseCharacterEntity skillUser, bool isLeftHand, out DamageInfo damageInfo)
    {
        if (area)
        {
            damageInfo = this.damageInfo;
            return true;
        }
        return base.TryGetDamageInfo(skillUser, isLeftHand, out damageInfo);
    }

    public override bool CanUse(BaseCharacterEntity skillUser, int level, bool isLeftHand, uint targetObjectId, out UITextKeys gameMessage, bool isItem = false)
    {
        bool isLeft = isLeftHand;
        var weapon = skillUser.GetAvailableWeapon(ref isLeft);
        switch (TzGameplayRule.WeaponClass(weapon))
        {
            case "bow": case "crossbow": case "thrown":
                gameMessage = UITextKeys.UI_ERROR_CANNOT_USE_SKILL_BY_CURRENT_WEAPON;
                return false;
        }
        return base.CanUse(skillUser, level, isLeftHand, targetObjectId, out gameMessage, isItem);
    }

    protected override void ApplySkillImplement(BaseCharacterEntity skillUser, int skillLevel, WeaponHandlingState weaponHandlingState,
        CharacterItem weapon, int simulateSeed, byte triggerIndex, byte spreadIndex, List<Dictionary<DamageElement, MinMaxFloat>> damageAmounts,
        uint targetObjectId, AimPosition aimPosition)
    {
        if (!stance)
        {
            base.ApplySkillImplement(skillUser, skillLevel, weaponHandlingState, weapon, simulateSeed, triggerIndex, spreadIndex, damageAmounts, targetObjectId, aimPosition);
            if (twice)
                base.ApplySkillImplement(skillUser, skillLevel, weaponHandlingState, weapon, simulateSeed + 1, triggerIndex, spreadIndex, damageAmounts, targetObjectId, aimPosition);
            return;
        }
        if (!skillUser.IsServer) return;
        // One stance at a time; its chance rides in the buff's level.
        RemoveStances(skillUser);
        int chance = Id == "p.d.c" ? TzSpell.Int(skillUser) * 8 : Mathf.Clamp(Accuracy(skillUser, weapon) - hitShift, 5, 95);
        skillUser.ApplyBuff(DataId, BuffType.SkillBuff, Mathf.Max(1, chance), skillUser.GetInfo(), weapon);
    }

    public override void OnSkillAttackHit(int skillLevel, EntityInfo instigator, CharacterItem weapon, BaseCharacterEntity target)
    {
        base.OnSkillAttackHit(skillLevel, instigator, weapon, target);
        if (effect == Effect.None || target == null || target.IsDead() || !instigator.TryGetEntity(out BaseCharacterEntity attacker))
            return;
        int chance = Accuracy(attacker, weapon) - effectShift;
        if (Random.Range(0, 101) > chance) return;
        switch (effect)
        {
            case Effect.Stun:
                if (TzGameplayRule.Guarded(target, "p.d.n")) return;
                TzGameplayRule.Stun(target, stunSeconds);
                break;
            case Effect.Disarm:
                if (!(target is BasePlayerCharacterEntity) || TzGameplayRule.Guarded(target, "p.d.r")) return;
                if (TzGameplayRule.WeaponClass(target.EquipWeapons.rightHand) == "hand") return;
                // Knocked out of the hand into the backpack (the old game dropped it on the ground), 5 s to recover.
                if (target.UnEquipWeapon(target.EquipWeaponSet, false, true, out _, out _))
                    TzGameplayRule.Stun(target, 5f);
                break;
            case Effect.KnockShield:
                if (!(target is BasePlayerCharacterEntity)) return;
                var left = target.EquipWeapons.leftHand;
                if (left.IsEmptySlot() || left.GetShieldItem() == null) return;
                if (target.UnEquipWeapon(target.EquipWeaponSet, true, true, out _, out _))
                    TzGameplayRule.Stun(target, 5f);
                break;
        }
    }

    /// <summary>Takes away the character's stance, if any.</summary>
    public static void RemoveStances(BaseCharacterEntity c)
    {
        for (int i = c.Buffs.Count - 1; i >= 0; i--)
        {
            var buff = c.Buffs[i];
            if (buff.type != BuffType.SkillBuff) continue;
            if (GameInstance.Skills.TryGetValue(buff.dataId, out var s) && s is TzTechnique t && t.stance)
            {
                c.OnRemoveBuff(buff, BuffRemoveReasons.RemoveByOtherBuffs);
                c.Buffs.RemoveAt(i);
            }
        }
    }
}
