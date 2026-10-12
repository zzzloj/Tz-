using MultiplayerARPG;
using UnityEngine;

/// <summary>
/// A trader's shop of our game (docs/mechanics-world.md §3.5, §4.2): the framework's shop dialog plus
/// - stock: each position gets rand(min, max) with its chance % (else none), renewed every period·(0.7..1.3) s;
///   the old game renewed it when someone opened the list, here when someone buys after the time is up;
/// - what the trader buys (prefixes of ids; none — everything; the multiplier 0 — buys nothing) and for what
///   share of the price.
/// </summary>
public class TzShopDialog : NpcDialog
{
    [Header("Tz: trader")]
    public float[] stockChance = new float[0];
    public int[] stockMin = new int[0];
    public int[] stockMax = new int[0];
    [Tooltip("Seconds between renewals of the stock (the old \"buy\" topic's second field)")]
    public float restockSeconds = 1200f;
    [Tooltip("Share of the price the trader pays (the \"sell\" topic); 0 — does not buy")]
    public float buyMultiplier;
    [Tooltip("What the trader buys: id prefixes; empty — everything")]
    public string[] buyFilters = new string[0];
}
