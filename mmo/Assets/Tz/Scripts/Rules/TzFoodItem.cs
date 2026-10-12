using MultiplayerARPG;
using UnityEngine;

/// <summary>
/// Food and elixirs of our game (content/balance/items.json "healPct", "manaPct"): eating restores a
/// share of the maximum health and mana at once, as on the game server (Crafting.kt eat). Made from the
/// framework's potion so its inventory, hotkeys and auto-use work; the potion's buff is not used.
/// </summary>
public partial class TzFoodItem : PotionItem, IPotionItem
{
    [Header("Tz: food")]
    [Tooltip("Health restored, percent of the maximum")]
    public float healPct;
    [Tooltip("Mana restored, percent of the maximum")]
    public float manaPct;

    public new bool UseItem(BaseCharacterEntity characterEntity, int itemIndex, CharacterItem characterItem)
    {
        if (!characterEntity.CanUseItem() || !characterEntity.DecreaseItemsByIndex(itemIndex, 1, false))
            return false;
        characterEntity.FillEmptySlots();
        if (healPct > 0)
            characterEntity.CurrentHp = Mathf.Min(characterEntity.MaxHp, characterEntity.CurrentHp + Mathf.FloorToInt(characterEntity.MaxHp * healPct / 100f));
        if (manaPct > 0)
            characterEntity.CurrentMp = Mathf.Min(characterEntity.MaxMp, characterEntity.CurrentMp + Mathf.FloorToInt(characterEntity.MaxMp * manaPct / 100f));
        return true;
    }
}
