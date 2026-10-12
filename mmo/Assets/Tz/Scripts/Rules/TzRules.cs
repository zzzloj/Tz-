using MultiplayerARPG;
using Tz.Rules;
using UnityEngine;

/// <summary>
/// Rules of learning (claude/balance.md §4, Game.kt teach) the framework has no place for; the patched
/// kit (mmo/tools/patch_kit.py teaching) calls these when a character raises an attribute or a skill:
/// - attributes 1–10 each, at most 24 together;
/// - a step of an attribute or a training skill costs 15·n² coins for the n-th step, but the first
///   points of a new character (level 1, fewer than 4 points spent) are free.
/// Spells and techniques always cost their teacher's price.
/// </summary>
public static class TzRules
{
    static TzBalance B => TzGameplayRule.Balance;

    /// <summary>Training points spent: attribute steps over the start and steps of training skills (TzSkill).</summary>
    public static int Spent(ICharacterData c)
    {
        int spent = 0;
        foreach (var a in c.Attributes) spent += a.amount;
        foreach (var s in c.Skills)
            if (s.GetSkill() is TzSkill) spent += s.level;
        return spent;
    }

    /// <summary>Whether the step just being learnt is one of a newcomer's free ones (Game.kt newbie, checked before the step).</summary>
    public static bool FreeStep(ICharacterData c, bool afterStep) =>
        c.Level == 1 && (afterStep ? Spent(c) - 1 : Spent(c)) < B.CreationPoints;

    static int AttributeValue(ICharacterData c, int dataId)
    {
        foreach (var a in c.Attributes)
            if (a.dataId == dataId) return B.AttrStart + a.amount;
        return B.AttrStart;
    }

    /// <summary>Before raising an attribute: the sum limit and the money for the step.</summary>
    public static bool CanRaiseAttribute(IPlayerCharacterData c, int dataId, out UITextKeys message)
    {
        message = UITextKeys.NONE;
        int sum = 0;
        foreach (var a in c.Attributes) sum += a.amount;
        sum += 3 * B.AttrStart;
        if (sum >= B.AttrSumMax)
        {
            message = UITextKeys.UI_ERROR_ATTRIBUTE_REACHED_MAX_AMOUNT;
            return false;
        }
        if (!FreeStep(c, false) && c.Gold < B.TeacherPrice(AttributeValue(c, dataId) + 1))
        {
            message = UITextKeys.UI_ERROR_NOT_ENOUGH_GOLD;
            return false;
        }
        return true;
    }

    /// <summary>After raising an attribute: takes the teacher's price of the step just learnt (free for a newcomer).</summary>
    public static void PayForAttribute(IPlayerCharacterData c, int dataId)
    {
        if (FreeStep(c, true)) return;
        c.Gold -= B.TeacherPrice(AttributeValue(c, dataId));
    }

    /// <summary>The coins a skill step takes, after it is learnt: a newcomer's training skill steps are free.</summary>
    public static int SkillGold(IPlayerCharacterData c, BaseSkill skill, int learnLevel)
    {
        int gold = skill.GetRequireCharacterGold(learnLevel);
        return skill is TzSkill && FreeStep(c, true) ? 0 : gold;
    }
}

/// <summary>A training skill (Protocol.kt SKILLS): steps 0–10 for a point and the teacher's price, free for a newcomer.</summary>
public class TzSkill : Skill
{
    public override bool CanLevelUp(IPlayerCharacterData character, int level, out UITextKeys gameMessage, bool checkSkillPoint = true, bool checkGold = true)
    {
        if (!base.CanLevelUp(character, level, out gameMessage, checkSkillPoint, false))
            return false;
        if (checkGold && !TzRules.FreeStep(character, false) && character.Gold < GetRequireCharacterGold(level))
        {
            gameMessage = UITextKeys.UI_ERROR_NOT_ENOUGH_GOLD;
            return false;
        }
        return true;
    }
}
