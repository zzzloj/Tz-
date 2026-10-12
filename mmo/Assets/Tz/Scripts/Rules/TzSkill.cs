using MultiplayerARPG;

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
