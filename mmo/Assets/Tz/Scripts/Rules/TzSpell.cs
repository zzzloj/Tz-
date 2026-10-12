using System.Collections.Generic;
using MultiplayerARPG;
using Tz.Rules;
using UnityEngine;

/// <summary>
/// A spell of our game (content/items/m.*.json, engine/server/.../Magic.kt) as a framework skill.
/// - Every cast first rolls the chance (TzBalance.SpellChance: magic, intelligence, the circle,
///   strength over 4); a failed cast has spent its mana and pause and does nothing.
/// - Battle spells hit with the magic damage element for a range that grows with the mage's level,
///   intelligence and magic (TzBalance.SpellDamage), accuracy +20 against magic evasion, crit 6 %;
///   magic defence stops a share (TzGameplayRule). Area spells hit everyone around.
/// - Healing, the buffs Armour/Strength/Mana, meditation, food and flowers are done here.
/// Scrolls i.m.* (spent) and runes i.r.* (kept, mmo/tools/patch_kit.py keep_runes) cast them unlearnt.
/// </summary>
public class TzSpell : Skill
{
    public enum Kind { Battle, Area, Heal, HealAll, Armor, Strength, Mana, Meditation, CreateFood, Bouquet, Other }

    [Header("Tz: spell")]
    public Kind kind;
    [Tooltip("The spell's circle (content \"level\"): −10 % of the cast chance a step")]
    public int circle = 1;
    public int powerMin;
    public int powerMax;
    [Tooltip("Hits only monsters (and not players)")]
    public bool criminalsOnly;
    [Tooltip("Удар: drains roll(powerMin, powerMax) mana of the target")]
    public bool drainsMana;
    [Tooltip("Вампиризм: heals the mage 2..powerMin % of the maximum on a cast")]
    public bool vampire;
    [Tooltip("Things the spell may make (food, flowers), one at random")]
    public BaseItem[] makes = new BaseItem[0];

    static TzBalance B => TzGameplayRule.Balance;

    public static int Int(ICharacterData c) => Mathf.RoundToInt(TzGameplayRule.AttributeAmount(c, "int"));
    public static int Str(ICharacterData c) => Mathf.RoundToInt(TzGameplayRule.AttributeAmount(c, "str"));

    /// <summary>The cast chance of this spell for the mage, percent.</summary>
    public int Chance(ICharacterData mage) =>
        B.SpellChance(TzGameplayRule.SkillLevel(mage, "magic"), Int(mage), Str(mage), circle);

    public override bool TryGetBaseAttackDamageAmount(ICharacterData skillUser, int skillLevel, bool isLeftHand, out KeyValuePair<DamageElement, MinMaxFloat> result)
    {
        if (kind != Kind.Battle && kind != Kind.Area)
            return base.TryGetBaseAttackDamageAmount(skillUser, skillLevel, isLeftHand, out result);
        var weapon = skillUser.EquipWeapons.rightHand;
        B.SpellDamage(skillUser.Level, Int(skillUser), TzGameplayRule.SkillLevel(skillUser, "magic"),
            TzGameplayRule.WeaponClass(weapon), 0, powerMin, powerMax, out int min, out int max);
        result = new KeyValuePair<DamageElement, MinMaxFloat>(TzGameplayRule.MagicElement, new MinMaxFloat { min = min, max = max });
        return true;
    }

    protected override void ApplySkillImplement(BaseCharacterEntity skillUser, int skillLevel, WeaponHandlingState weaponHandlingState,
        CharacterItem weapon, int simulateSeed, byte triggerIndex, byte spreadIndex, List<Dictionary<DamageElement, MinMaxFloat>> damageAmounts,
        uint targetObjectId, AimPosition aimPosition)
    {
        if (!skillUser.IsServer)
        {
            if (kind == Kind.Battle || kind == Kind.Area)
                base.ApplySkillImplement(skillUser, skillLevel, weaponHandlingState, weapon, simulateSeed, triggerIndex, spreadIndex, damageAmounts, targetObjectId, aimPosition);
            return;
        }
        if (Random.Range(0, 101) > Chance(skillUser))
        {
            // «Заклинание сорвалось»: mana and pause are spent, nothing happens.
            return;
        }
        BaseCharacterEntity target = null;
        if (targetObjectId != 0)
            skillUser.CurrentGameManager.TryGetEntityByObjectId(targetObjectId, out target);
        int @int = Int(skillUser);
        switch (kind)
        {
            case Kind.Battle:
            case Kind.Area:
                if (vampire)
                    Heal(skillUser, Mathf.FloorToInt(skillUser.MaxHp * Random.Range(2, Mathf.Max(2, powerMin) + 1) / 100f));
                if (drainsMana && target != null && !target.IsDead())
                    target.CurrentMp = Mathf.Max(0, target.CurrentMp - Random.Range(powerMin, powerMax + 1));
                base.ApplySkillImplement(skillUser, skillLevel, weaponHandlingState, weapon, simulateSeed, triggerIndex, spreadIndex, damageAmounts, targetObjectId, aimPosition);
                break;
            case Kind.Heal:
            {
                var who = target != null && !target.IsDead() ? target : skillUser;
                Heal(who, B.HealAmount(who.MaxHp, Random.Range(powerMin, Mathf.Max(powerMin, powerMax) + 1), @int));
                break;
            }
            case Kind.HealAll:
            {
                // Yourself and the allies around (the old game: your clan; here: party and guild).
                var info = skillUser.GetInfo();
                var around = new List<BaseCharacterEntity>();
                skillUser.FindAliveEntities(around, 10f, true, false, false,
                    GameInstance.Singleton.playerLayer.Mask | GameInstance.Singleton.playingLayer.Mask);
                if (!around.Contains(skillUser)) around.Add(skillUser);
                foreach (var who in around)
                    if (who == skillUser || who.IsAlly(info))
                        Heal(who, B.HealAmount(who.MaxHp, Random.Range(powerMin, Mathf.Max(powerMin, powerMax) + 1), @int));
                break;
            }
            case Kind.Armor:
            case Kind.Strength:
            case Kind.Mana:
            {
                // Not timed in the old game: it stays till death. The buff's level carries the mage's power:
                // armour int + level, health or mana int·2 % (the buff assets scale by level).
                var who = target != null && !target.IsDead() ? target : skillUser;
                int level = kind == Kind.Armor ? @int + skillUser.Level : @int;
                who.ApplyBuff(DataId, BuffType.SkillBuff, Mathf.Max(1, level), skillUser.GetInfo(), weapon);
                break;
            }
            case Kind.Meditation:
            {
                // All health over 1 becomes mana × 0.7.
                int gain = Mathf.Min(Mathf.RoundToInt((skillUser.CurrentHp - 1) * 0.7f), skillUser.MaxMp - skillUser.CurrentMp);
                if (gain <= 0) break;
                skillUser.CurrentHp = 1;
                skillUser.CurrentMp += gain;
                break;
            }
            case Kind.CreateFood:
            case Kind.Bouquet:
                if (makes != null && makes.Length > 0 && skillUser is BasePlayerCharacterEntity player)
                {
                    var thing = makes[Random.Range(0, makes.Length)];
                    if (thing != null && player.IncreaseItems(CharacterItem.Create(thing, 1, 1)))
                        player.FillEmptySlots();
                }
                break;
            default:
                base.ApplySkillImplement(skillUser, skillLevel, weaponHandlingState, weapon, simulateSeed, triggerIndex, spreadIndex, damageAmounts, targetObjectId, aimPosition);
                break;
        }
    }

    static void Heal(BaseCharacterEntity who, int amount)
    {
        if (amount <= 0) return;
        who.CurrentHp = Mathf.Min(who.MaxHp, who.CurrentHp + amount);
    }

    /// <summary>Runes stay after a cast, scrolls burn (the patched kit asks this before spending the thing).</summary>
    public static bool KeepsItem(int itemDataId) =>
        GameInstance.Items.TryGetValue(itemDataId, out var item) && item != null && item.Id != null && item.Id.StartsWith("i.r.");
}
