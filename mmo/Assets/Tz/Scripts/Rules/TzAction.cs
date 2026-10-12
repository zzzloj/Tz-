using System.Collections.Generic;
using MultiplayerARPG;
using UnityEngine;

/// <summary>
/// Actions of our skills that are not blows or spells (Pets.kt tame, raise; Skills.kt peek, steal), used from
/// the skill bar on the selected creature. The old 0–5 skill scale counts here: old(x) = (x + 1) / 2.
/// - Приручить: an animal with no master follows and guards; chance 10·(old taming + 1 − its difficulty) %,
///   needs animal lore; it stays 60 + up to old lore · 600 s. Busy 5 s.
/// - Поднять труп: a monster or animal dead a moment ago rises as a zombie with 0.4 + 0.05·necromancy of
///   its health and blow for 300 + up to necromancy · 90 s; chance 10·necromancy %, 6 mana. Busy 10 s.
/// - Подглядеть / Украсть (players only): peek with 6·(old dex + old peeking − their old caution) %, then for a
///   minute steal a random stack of their backpack with 4·(old dex + old stealing − caution) %. Busy 5 / 10 s.
/// </summary>
public class TzAction : Skill
{
    public enum Kind { Tame, Raise, Peek, Steal }

    [Header("Tz: action")]
    public Kind kind;

    /// <summary>The old 0–5 scale of a 0–10 step (Game.kt oldSkill).</summary>
    public static int Old(int step) => (step + 1) / 2;
    static int Skill(ICharacterData c, string id) => TzGameplayRule.SkillLevel(c, id);
    static int Dex(ICharacterData c) => Mathf.RoundToInt(TzGameplayRule.AttributeAmount(c, "dex"));

    /// <summary>Who may steal from whom till when, after a good peek.</summary>
    static readonly Dictionary<uint, (uint target, float until)> peeked = new Dictionary<uint, (uint, float)>();

    protected override void ApplySkillImplement(BaseCharacterEntity skillUser, int skillLevel, WeaponHandlingState weaponHandlingState,
        CharacterItem weapon, int simulateSeed, byte triggerIndex, byte spreadIndex, List<Dictionary<DamageElement, MinMaxFloat>> damageAmounts,
        uint targetObjectId, AimPosition aimPosition)
    {
        if (!skillUser.IsServer) return;
        BaseGameEntity target = null;
        if (targetObjectId != 0) skillUser.CurrentGameManager.TryGetEntityByObjectId(targetObjectId, out target);
        switch (kind)
        {
            case Kind.Tame:
            {
                TzGameplayRule.Busy(skillUser, 5);
                if (!(target is BaseMonsterCharacterEntity animal) || animal.IsDead() || TzSummons.HasMaster(animal)) return;
                var data = animal.CharacterDatabase as TzMonsterCharacter;
                if (data == null || !data.Id.StartsWith("n.a.") || data.tameDifficulty <= 0) return;
                int lore = Old(Skill(skillUser, "animallore"));
                if (lore <= 0) return;
                int chance = 10 * (Old(Skill(skillUser, "animaltaming")) + 1 - data.tameDifficulty);
                if (Random.Range(0, 101) > chance) return;
                TzSummons.Adopt(skillUser, animal, 60 + Random.Range(0, lore * 600 + 1));
                break;
            }
            case Kind.Raise:
            {
                TzGameplayRule.Busy(skillUser, 10);
                if (!(target is BaseMonsterCharacterEntity dead) || !dead.IsDead() || dead.IsSummoned) return;
                string id = dead.CharacterDatabase != null ? dead.CharacterDatabase.Id : "";
                if (!id.StartsWith("n.c.") && !id.StartsWith("n.a.")) return;
                int necro = Skill(skillUser, "necro");
                if (Random.Range(0, 100) >= 10 * necro) return;
                var prefab = GameInstance.MonsterCharacterEntities.TryGetValue(dead.Identity.HashAssetId, out var p) ? p : null;
                var zombie = TzSummons.Summon(skillUser, prefab, dead.Level, Random.Range(300, 300 + necro * 90 + 1));
                // The zombie is weaker: its health and blow ×(0.4 + 0.05·necromancy), carried by the buff's level in percent.
                if (zombie != null)
                    TzGameplayRule.Zombie(zombie, 0.4f + 0.05f * necro);
                break;
            }
            case Kind.Peek:
            {
                TzGameplayRule.Busy(skillUser, 5);
                if (!(target is BasePlayerCharacterEntity victim) || victim == skillUser) return;
                int chance = 6 * (Old(Dex(skillUser)) + Old(Skill(skillUser, "steallook")) - Old(Skill(victim, "look")));
                if (Random.Range(0, 101) < chance)
                    peeked[skillUser.ObjectId] = (victim.ObjectId, Time.unscaledTime + 60f);
                break;
            }
            case Kind.Steal:
            {
                TzGameplayRule.Busy(skillUser, 10);
                if (!(target is BasePlayerCharacterEntity victim) || !(skillUser is BasePlayerCharacterEntity thief)) return;
                if (!peeked.TryGetValue(thief.ObjectId, out var seen) || seen.target != victim.ObjectId || Time.unscaledTime > seen.until) return;
                int chance = 4 * (Old(Dex(thief)) + Old(Skill(thief, "steal")) - Old(Skill(victim, "look")));
                if (Random.Range(0, 101) >= chance) return;
                var stacks = new List<int>();
                for (int i = 0; i < victim.NonEquipItems.Count; i++)
                    if (!victim.NonEquipItems[i].IsEmptySlot()) stacks.Add(i);
                if (stacks.Count == 0) return;
                int index = stacks[Random.Range(0, stacks.Count)];
                var stack = victim.NonEquipItems[index];
                if (!thief.IncreaseItems(stack)) return;
                victim.NonEquipItems.RemoveAt(index);
                victim.FillEmptySlots();
                thief.FillEmptySlots();
                peeked.Remove(thief.ObjectId);
                break;
            }
        }
    }
}
