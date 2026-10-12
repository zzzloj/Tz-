using System;
using System.Collections.Generic;
using MultiplayerARPG;
using UnityEngine;

/// <summary>
/// A monster of our game: the framework's MonsterCharacter whose drop is rolled the old way
/// (content/npcs "itemsrnd", docs/data-fields.md): every entry on its own — with its chance a random
/// amount min..max (0 drops nothing); trophies ("osvej") always. The framework's own drop manager keeps
/// rolling until a set number of things fall, which would make every kill drop something.
/// </summary>
public class TzMonsterCharacter : MonsterCharacter
{
    [Serializable]
    public struct Drop
    {
        public BaseItem item;
        [Tooltip("Chance in percent")]
        public float chance;
        public int min;
        public int max;
    }

    [Header("Tz: drop of the old game")]
    public List<Drop> tzDrops = new List<Drop>();

    public override void RandomItems(OnDropItemDelegate onRandomItem, float rate = 1f)
    {
        if (tzDrops == null || onRandomItem == null)
            return;
        foreach (var d in tzDrops)
        {
            if (d.item == null)
                continue;
            if (UnityEngine.Random.value * 100f >= d.chance * rate)
                continue;
            int amount = UnityEngine.Random.Range(Mathf.Min(d.min, d.max), Mathf.Max(d.min, d.max) + 1);
            if (amount > 0)
                onRandomItem(d.item, 1, amount);
        }
    }

    public override void PrepareRelatesData()
    {
        base.PrepareRelatesData();
        var items = new List<BaseItem>();
        foreach (var d in tzDrops)
            if (d.item != null) items.Add(d.item);
        GameInstance.AddItems(items);
    }
}
