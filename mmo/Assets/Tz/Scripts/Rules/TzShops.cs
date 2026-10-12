using System.Collections.Generic;
using MultiplayerARPG;
using UnityEngine;

/// <summary>The traders' stock and buying (TzShopDialog); the patched framework (patch_kit.py shops) tells which shop is open.</summary>
public static class TzShops
{
    /// <summary>The shop and the position the framework is buying from or selling to right now.</summary>
    public static NpcDialog Current;
    public static int CurrentIndex = -1;

    class State { public float next; public int[] stock; }
    static readonly Dictionary<int, State> states = new Dictionary<int, State>();

    static State Of(TzShopDialog shop)
    {
        if (!states.TryGetValue(shop.DataId, out var s))
            states[shop.DataId] = s = new State { next = 0f, stock = new int[shop.sellItems != null ? shop.sellItems.Length : 0] };
        if (Time.unscaledTime >= s.next)
        {
            for (int i = 0; i < s.stock.Length; i++)
            {
                float chance = i < shop.stockChance.Length ? shop.stockChance[i] : 100f;
                int min = i < shop.stockMin.Length ? shop.stockMin[i] : 1, max = i < shop.stockMax.Length ? shop.stockMax[i] : 1;
                s.stock[i] = Random.Range(0f, 100f) < chance ? Random.Range(min, max + 1) : 0;
            }
            s.next = Time.unscaledTime + shop.restockSeconds * Random.Range(0.7f, 1.3f);
        }
        return s;
    }

    /// <summary>Whether the current shop has [amount] of its current position (framework shops — always).</summary>
    public static bool InStock(int amount)
    {
        if (!(Current is TzShopDialog shop) || CurrentIndex < 0) return true;
        var s = Of(shop);
        return CurrentIndex < s.stock.Length && s.stock[CurrentIndex] >= amount;
    }

    /// <summary>Takes [amount] of the current position from the stock.</summary>
    public static void Take(int amount)
    {
        if (!(Current is TzShopDialog shop) || CurrentIndex < 0) return;
        var s = Of(shop);
        if (CurrentIndex < s.stock.Length) s.stock[CurrentIndex] = Mathf.Max(0, s.stock[CurrentIndex] - amount);
    }

    /// <summary>Whether this shop buys the thing.</summary>
    public static bool Buys(NpcDialog dialog, BaseItem item)
    {
        if (!(dialog is TzShopDialog shop)) return true;
        if (shop.buyMultiplier <= 0 || item == null) return false;
        if (shop.buyFilters == null || shop.buyFilters.Length == 0) return true;
        foreach (var f in shop.buyFilters)
            if (!string.IsNullOrEmpty(f) && item.Id.StartsWith(f)) return true;
        return false;
    }

    /// <summary>The share of the price the current shop pays (1 for framework shops).</summary>
    public static float BuyMultiplier => Current is TzShopDialog shop ? shop.buyMultiplier : 1f;

    /// <summary>The thing at a place of the character's inventory, for the selling check.</summary>
    public static BaseItem ItemAt(IPlayerCharacterData c, InventoryType type, int index, byte equipSlotIndex)
    {
        switch (type)
        {
            case InventoryType.NonEquipItems:
                return index >= 0 && index < c.NonEquipItems.Count ? c.NonEquipItems[index].GetItem() : null;
            case InventoryType.EquipItems:
                return index >= 0 && index < c.EquipItems.Count ? c.EquipItems[index].GetItem() : null;
            case InventoryType.EquipWeaponRight:
                return equipSlotIndex < c.SelectableWeaponSets.Count ? c.SelectableWeaponSets[equipSlotIndex].rightHand.GetItem() : null;
            case InventoryType.EquipWeaponLeft:
                return equipSlotIndex < c.SelectableWeaponSets.Count ? c.SelectableWeaponSets[equipSlotIndex].leftHand.GetItem() : null;
            default:
                return null;
        }
    }
}
