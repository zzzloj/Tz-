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
/// - block only with a shield: 5 % + 2 % a step of parrying + 0.5 % a point of dexterity (≤ 30 %) + items' blockRate;
///   a blocked blow loses BlockCut (half);
/// - strength adds str/2 per 4 s of pause to a physical blow (not crossbows, not skills); the skill of the weapon in
///   hand (hand / coldweapon / ranged) adds +3 accuracy and +3 % damage a step; regeneration +0.3 % of health a step;
/// - experience: monsters' experience by the level gap, two training points a level (+2 every tenth) into the one
///   pool of attributes and skills (StatPoint and SkillPoint kept equal, mmo/tools/patch_kit.py one_pool).
/// Weapon classes (pause, crit, penetration) are the ids of the framework's WeaponType assets: knife, sword, axe, …
/// </summary>
[CreateAssetMenu(fileName = "TzGameplayRule", menuName = "Tz/Gameplay Rule")]
public class TzGameplayRule : DefaultGameplayRule
{
    static TzBalance balance;

    void OnEnable()
    {
        // Our subordinates are the framework's custom summons (TzSummons).
        GameInstance.CustomSummonManager = TzSummons.Instance;
    }

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
    [ThreadStatic] static bool strikingPlayer;

    /// <summary>When each character last struck or was struck (Time.unscaledTime): rest starts 10 s after.</summary>
    static readonly Dictionary<uint, float> lastBlow = new Dictionary<uint, float>();

    static void Blow(BaseCharacterEntity c)
    {
        if (c != null) lastBlow[c.ObjectId] = Time.unscaledTime;
    }

    /// <summary>Whether the character rests (no blows for RegenAfter seconds), so health and mana come back.</summary>
    public static bool Resting(BaseCharacterEntity c) =>
        !lastBlow.TryGetValue(c.ObjectId, out float t) || Time.unscaledTime - t >= Balance.RegenAfter;

    static DamageElement magicElement;
    /// <summary>The magic damage element (id "magic", made by TzImport): spells, staves, magic monsters.</summary>
    public static DamageElement MagicElement
    {
        get
        {
            if (magicElement == null && GameInstance.DamageElements != null)
                foreach (var e in GameInstance.DamageElements.Values)
                    if (e != null && e.Id == "magic") { magicElement = e; break; }
            return magicElement;
        }
    }

    static bool IsMagic(DamageElement e) => e != null && MagicElement != null && e == MagicElement;

    /// <summary>Evasion against magic: players dex + 3·magic evasion + level; monsters their evasion.</summary>
    public static float MagicEvasion(BaseCharacterEntity c)
    {
        if (!(c is BasePlayerCharacterEntity))
            return c.GetCaches().Stats.evasion;
        return (float)Balance.MagicEvasion(Mathf.RoundToInt(AttributeAmount(c, "dex")), SkillLevel(c, "magic_uklon"), c.Level);
    }
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
        Blow(attacker);
        Blow(damageReceiver);
        var spell = skill as TzSpell;
        bool magic = spell != null;
        if (!magic && damageAmounts != null)
            foreach (var e in damageAmounts.Keys) if (IsMagic(e)) { magic = true; break; }
        // Holy spells hit monsters only.
        if (spell != null && spell.criminalsOnly && damageReceiver is BasePlayerCharacterEntity)
            return false;
        // Skill in the weapon in hand: +3 accuracy a step (balance.md §5); a spell +20 (Magic.kt battleSpell).
        string cls = WeaponClass(weapon);
        float acc = attacker.GetCaches().Stats.accuracy + (float)Balance.AccPerWeaponSkill * SkillLevel(attacker, WeaponSkill(cls)) + (spell != null ? 20f : 0f);
        if (skill is TzTechnique tech)
            acc += tech.hitShift - TzTechnique.IntPenalty(attacker);
        float eva = magic ? MagicEvasion(damageReceiver) : damageReceiver.GetCaches().Stats.evasion;
        float blockTimes = 1f;
        if (magic)
        {
            // Сбить заклинание: a tenth of its chance to null a magic blow, unless the mage's concentration holds.
            bool concentration = Guarded(attacker, "p.d.c");
            if (Stance(damageReceiver, "p.d.z", out _) && Guarded(damageReceiver, "p.d.z", 0.1f) && !concentration)
                return false;
        }
        else
        {
            bool ranged = cls == "bow" || cls == "crossbow" || cls == "thrown";
            if (ranged && Guarded(damageReceiver, "p.d.u")) eva += 35;   // уклон от стрел
            if (Guarded(damageReceiver, "p.d.re")) eva += 20;            // реакция
            if (Guarded(damageReceiver, "p.d.p")) blockTimes = 2f;       // закрыться щитом
        }
        if (UnityEngine.Random.value * 100f >= Balance.HitChance(acc, eva))
            return false;
        float crit = GetCriticalChance(attacker, damageReceiver);
        if (spell != null)
            // Spells crit 6 %, plus what items add over the weapon's own chance.
            crit = Mathf.Max(0f, crit - (float)(Balance.CritBase(cls) / 100.0)) + (float)(Balance.CritBase("spell") / 100.0);
        isCritical = UnityEngine.Random.value < crit;
        // A shield does not stop magic.
        isBlocked = !magic && UnityEngine.Random.value < GetBlockChance(attacker, damageReceiver) * blockTimes;
        return true;
    }

    public override float RandomAttackDamage(Vector3 fromPosition, BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver, DamageElement damageElement, MinMaxFloat damageAmount, CharacterItem weapon, BaseSkill skill, int skillLevel, int randomSeed)
    {
        string cls = WeaponClass(weapon);
        strikingLevel = attacker != null ? attacker.Level : 1;
        strikingPen = Balance.Penetration(cls);
        strikingPlayer = damageReceiver is BasePlayerCharacterEntity;
        var technique = skill as TzTechnique;
        if (technique != null && attacker != null)
        {
            // Удар в голову, в прыжке, сильный: strength and weapon skill on top (on the maximum, or both ends).
            int extra = technique.Extra(attacker, weapon);
            if (extra > 0)
                damageAmount = new MinMaxFloat { min = damageAmount.min + (technique.extraOnMinToo ? extra : 0), max = damageAmount.max + extra };
        }
        float damage = base.RandomAttackDamage(fromPosition, attacker, damageReceiver, damageElement, damageAmount, weapon, skill, skillLevel, randomSeed);
        bool physical = (damageElement == null || damageElement == GameInstance.Singleton.DefaultDamageElement) && !IsMagic(damageElement);
        if (attacker != null && physical && (skill == null || technique != null) && cls != "crossbow")
        {
            // Strength adds str/2 per 4 s of pause to every blow, so damage per second stays fair (balance.md §5).
            var w = weapon.IsEmptySlot() ? null : weapon.GetWeaponItem();
            double pause = w != null && w.RateOfFire > 0 ? 60.0 / w.RateOfFire : Balance.Pause(cls, 0);
            // A technique scales the strength part with the blow (Magic.kt scales the whole damage).
            damage += (float)Balance.StrengthBonus(Mathf.RoundToInt(AttributeAmount(attacker, "str")), pause) * (technique != null ? technique.damageMultiplier : 1f);
        }
        if (attacker != null && Stance(attacker, "p.d.re", out _))
            damage *= 0.6f;   // in реакция your own blows are weaker
        if (attacker != null && (skill == null || technique != null))
            // Skill in the weapon (staves' magic blows too): +3 % damage a step, on the weapon's damage and strength's (Combat.kt).
            damage *= (float)Balance.WeaponSkillMultiplier(SkillLevel(attacker, WeaponSkill(cls)));
        return damage;
    }

    /// <summary>A character's amount of the attribute with this id (str, dex, int); 0 if none.</summary>
    public static float AttributeAmount(ICharacterData character, string id)
    {
        var attributes = character.GetCaches().Attributes;
        if (attributes == null) return 0;
        foreach (var kv in attributes)
            if (kv.Key != null && kv.Key.Id == id) return kv.Value;
        return 0;
    }

    /// <summary>Whether the character stands in this stance (TzTechnique p.d.*), and its chance.</summary>
    public static bool Stance(BaseCharacterEntity c, string id, out int chance)
    {
        chance = 0;
        if (c == null) return false;
        foreach (var buff in c.Buffs)
            if (buff.type == BuffType.SkillBuff && GameInstance.Skills.TryGetValue(buff.dataId, out var s) && s is TzTechnique t && t.stance && t.Id == id)
            {
                chance = buff.level;
                return true;
            }
        return false;
    }

    /// <summary>A stance meets what it guards against: it is spent, and holds with its chance (× share).</summary>
    public static bool Guarded(BaseCharacterEntity c, string id, float share = 1f)
    {
        if (!Stance(c, id, out int chance)) return false;
        TzTechnique.RemoveStances(c);
        return UnityEngine.Random.Range(0, 101) <= chance * share;
    }

    static readonly Dictionary<string, BaseSkill> carriers = new Dictionary<string, BaseSkill>();

    static void Carrier(string id, BaseCharacterEntity c, float seconds)
    {
        if (!carriers.TryGetValue(id, out var skill) || skill == null)
        {
            foreach (var s in GameInstance.Skills.Values)
                if (s != null && s.Id == id) { skill = s; break; }
            carriers[id] = skill;
        }
        if (skill == null || c == null || c.IsDead()) return;
        c.ApplyBuff(skill.DataId, BuffType.SkillBuff, Mathf.Max(1, Mathf.RoundToInt(seconds)), c.GetInfo(), CharacterItem.Empty);
    }

    /// <summary>The character can do nothing, not even walk, for some seconds (hidden skill tz.stun; level = seconds).</summary>
    public static void Stun(BaseCharacterEntity c, float seconds) => Carrier("tz.stun", c, seconds);

    /// <summary>A raised zombie is weaker: health and blow × share (hidden skill tz.zombie; level = percent).</summary>
    public static void Zombie(BaseCharacterEntity c, float share)
    {
        Carrier("tz.zombie", c, Mathf.Clamp(share * 100f, 1f, 100f));
        c.CurrentHp = c.MaxHp;
    }

    /// <summary>The character is busy (eating, resting): no blows, skills or things, but may walk (hidden skill tz.busy).</summary>
    public static void Busy(BaseCharacterEntity c, float seconds) => Carrier("tz.busy", c, seconds);

    /// <summary>The skill that serves a weapon class: fists — hand, bows, crossbows, thrown — ranged, the rest — coldweapon.</summary>
    public static string WeaponSkill(string weaponClass)
    {
        switch (weaponClass)
        {
            case "hand": return "hand";
            case "bow": case "crossbow": case "thrown": return "ranged";
            default: return "coldweapon";
        }
    }

    /// <summary>A character's step in the skill with this id (Protocol.kt SKILLS keys); 0 if not learnt.</summary>
    public static int SkillLevel(ICharacterData character, string id)
    {
        var skills = character.GetCaches().Skills;
        if (skills == null) return 0;
        foreach (var kv in skills)
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
        int level = strikingLevel > 0 ? strikingLevel : 1;
        if (IsMagic(damageElement))
        {
            // Magic defence: 5·int + 8·magic resistance (through the attribute's and the skill's armour of
            // the magic element) + ¼ of the armour for characters; monsters have theirs as it is.
            damageReceiverArmors.TryGetValue(damageElement, out float defence);
            if (strikingPlayer && damageReceiverArmors.TryGetValue(GameInstance.Singleton.DefaultDamageElement, out float physicalArmor))
                defence += 0.25f * physicalArmor;
            damageAmount *= (float)(1 - Balance.MagicCut(defence, level));
            return damageAmount;
        }
        float armor;
        if (damageReceiverArmors.TryGetValue(damageElement, out armor))
        {
            double cut = Balance.ArmorCut(armor, level, strikingPen > 0 ? strikingPen : 1.0);
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
        // Only with a shield: 5 % + 2 % a step of parrying + 0.5 % a point of dexterity, at most 30 %,
        // plus what items add to blockRate.
        var left = damageReceiver.GetCaches().LeftHandItem;
        if (left.IsEmptySlot() || left.GetShieldItem() == null)
            return 0f;
        int dex = Mathf.RoundToInt(AttributeAmount(damageReceiver, "dex"));
        return Mathf.Clamp01((float)(Balance.BlockChance(SkillLevel(damageReceiver, "parring"), dex) / 100.0) + damageReceiver.GetCaches().Stats.blockRate);
    }

    public override float GetBlockDamage(BaseCharacterEntity attacker, BaseCharacterEntity damageReceiver, float damage)
    {
        return damage * (float)(1 - Balance.BlockCut);
    }

    public override int GetTotalDamage(Vector3 fromPosition, EntityInfo instigator, DamageableEntity damageReceiver, float totalDamage, CharacterItem weapon, BaseSkill skill, int skillLevel)
    {
        if (damageReceiver is BaseCharacterEntity receiver)
        {
            // Защита головы stops Удар в голову; глухая оборона takes 60 % off anything but a spell, for its whole time.
            if (skill != null && skill.Id == "p.g" && Guarded(receiver, "p.d.g"))
                return 0;
            if (!(skill is TzSpell) && Stance(receiver, "p.d.o", out int defence) && UnityEngine.Random.Range(0, 101) <= defence)
                totalDamage *= 0.4f;
        }
        // As on the server: a blow that got through deals at least 1.
        return totalDamage <= 0f ? 0 : Mathf.Max(1, Mathf.RoundToInt(totalDamage));
    }

    public override float GetRecoveryHpPerSeconds(BaseCharacterEntity character)
    {
        // The server heals RegenPerTick every RegenEvery seconds, from 10 s after the last blow; here it flows evenly.
        if (!Resting(character)) return 0f;
        return (float)(Balance.RegenPerTick(character.GetCaches().MaxHp, SkillLevel(character, "regeneration"), false) / Balance.RegenEvery) + character.GetCaches().Stats.hpRecovery;
    }

    public override float GetRecoveryMpPerSeconds(BaseCharacterEntity character)
    {
        // Mana returns like health, with meditation in place of regeneration.
        if (!Resting(character)) return 0f;
        return (float)(Balance.ManaRegenPerTick(character.GetCaches().MaxMp, SkillLevel(character, "meditation"), false) / Balance.RegenEvery) + character.GetCaches().Stats.mpRecovery;
    }

    /// <summary>The firebird's feather: it burns at death and the things stay (Game.kt killPlayer).</summary>
    public const string Feather = "i.q.pjpt";

    public override void GetPlayerDeadPunishment(DeadPunishmentType type, BasePlayerCharacterEntity player, BaseCharacterEntity attacker, out int decreaseExp, out int decreaseGold, out int decreaseItems, out int attackerPkPoint)
    {
        // Death (Game.kt killPlayer): no experience or money is lost; every thing, worn ones too, goes into the
        // corpse for 10 minutes (the map's drop settings and GameInstance's corpse looting, TzImport), unless the
        // firebird's feather burns instead.
        base.GetPlayerDeadPunishment(type, player, attacker, out _, out _, out _, out attackerPkPoint);
        decreaseExp = 0;
        decreaseGold = 0;
        decreaseItems = int.MaxValue;
        foreach (var item in GameInstance.Items.Values)
        {
            if (item == null || item.Id != Feather) continue;
            if (player.CountNonEquipItems(item.DataId) > 0 && player.DecreaseItems(item.DataId, 1))
            {
                player.FillEmptySlots();
                decreaseItems = 0;
            }
            break;
        }
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
            {
                // One pool for attributes and skills: both counters get the points (patch_kit.py one_pool).
                player.StatPoint += Balance.PointsForLevel(l);
                player.SkillPoint += Balance.PointsForLevel(l);
            }
        return up;
    }
}
