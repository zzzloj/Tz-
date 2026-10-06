// The part of the server protocol the Unity client uses (tz.shared.Protocol in engine/shared).
// Field names are the JSON names; anything the server sends that is not here is skipped.
using System.Collections.Generic;

namespace Amulet
{
    public class Credentials { public string login; public string password; }
    public class AuthResponse { public string token; public string login; }
    public class ErrorResponse { public string error; public string message; }
    public class NewCharacter { public string name; public string sex; }
    public class MeView { public string login; public CharacterView character; public string role; }

    public class MoveRequest { public string target; public bool gallop; }
    public class TargetRequest { public string target; public bool player; }
    public class ItemRequest { public string item; public string arg; public int? count; }
    public class LootRequest { public string corpse; public string item = ""; }

    public class CharacterView
    {
        public long id;
        public string name;
        public string sex;
        public string location;
        public int hp, hpMax, mana, manaMax;
        public int str, dex;
        [Newtonsoft.Json.JsonProperty("int")] public int intel;
        public int skillPoints;
        public bool ghost;
        public int exp, expNext, level = 1;
        public int power;
        public bool poisoned;
        public int hit, dmgMin, dmgMax, armor, dodge;
    }

    public class ExitView { public string label; public string target; public bool occupied; }

    public class NpcView
    {
        public string id;
        public string name;
        public int hp, hpMax;
        public bool fightingYou;
        public bool attackable;
        public bool canTalk;
        public string attacking;
        public bool mine;
        public string owner;
        public string art;
        public bool undead;
        public long? nextBlowMs;
        public int level;
        public bool hostile;
        public int power;
        public int? winChance;
    }

    public class GroundItemView { public string id; public string name; public int count; public bool takeable; }

    public class CorpseView
    {
        public string id;
        public string name;
        public List<GroundItemView> items = new List<GroundItemView>();
        public bool canButcher;
        public bool looting;
        public bool mine;
    }

    public class LocationView
    {
        public string id;
        public string name;
        public int zone;
        public string description;
        public List<ExitView> exits = new List<ExitView>();
        public List<NpcView> npcs = new List<NpcView>();
        public List<GroundItemView> items = new List<GroundItemView>();
        public List<string> players = new List<string>();
        public List<CorpseView> corpses = new List<CorpseView>();
        public string art;
    }

    public class InventoryItemView
    {
        public string id;
        public string name;
        public int count;
        public bool equipped;
        public bool equippable;
        public bool usable;
    }

    public class GameView
    {
        public CharacterView character;
        public LocationView location;
        public List<InventoryItemView> inventory = new List<InventoryItemView>();
        public List<string> journal = new List<string>();
        public int journalHere = -1;
        public List<string> journalKinds = new List<string>();
        public long restMs;
        public bool canResurrect;
    }
}
