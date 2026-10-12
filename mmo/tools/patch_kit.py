"""Makes Open MMORPG (Assets/OpenMMORPG, a git submodule we do not edit) compile on Unity 6000.0,
and changes the few places of it our rules cannot reach from GameplayRule (one_pool).

The kit targets Unity 6000.3, whose Personal licence our CI cannot use (mmo/README.md), and only a few
editor-only files use 6000.3 APIs. This rewrites them in the checked-out copy; run it before Unity
(CI does, .github/workflows/mmo.yml; locally: python3 mmo/tools/patch_kit.py). Safe to run again.
"""
import json, os, re, sys

KIT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'Assets', 'OpenMMORPG')

def edit(rel, fn):
    path = os.path.join(KIT, rel)
    with open(path, encoding='utf-8-sig') as f: before = f.read()
    after = fn(before)
    if after != before:
        with open(path, 'w', encoding='utf-8') as f: f.write(after)
        print('patched', rel)

def main():
    # EditorUtility.EntityIdToObject (6000.3) is InstanceIDToObject before it; both take the id the callers have.
    for rel in ('ThirdParty/UnityEditorUtils/Editor/CreateScriptableObject.cs',
                'ThirdParty/xNode/Scripts/Editor/NodeEditorWindow.cs'):
        edit(rel, lambda s: s.replace('EditorUtility.EntityIdToObject(', 'EditorUtility.InstanceIDToObject('))
    # UniTask's tracker window uses the generic TreeView of 6000.3: leave that editor-only assembly out before it.
    def no_tracker(s):
        d = json.loads(s)
        if 'UNITY_6000_3_OR_NEWER' not in d.get('defineConstraints', []):
            d['defineConstraints'] = d.get('defineConstraints', []) + ['UNITY_6000_3_OR_NEWER']
        return json.dumps(d, indent=4)
    edit('ThirdParty/LiteNetLibManager/Plugins/UniTask/Editor/UniTask.Editor.asmdef', no_tracker)
    drop_urp()
    edit('ThirdParty/GraphicSettings/Scripts/GraphicSettingInitializer.cs',
         lambda s: re.sub(r'Urp\w+Setting\.Load\(\);', ';', s))
    # RaycastHit.colliderEntityId / EntityId (6000.3) is colliderInstanceID / 0 before it.
    edit('Core/Scripts/Gameplay/PhysicFunctions/JobifiedPhysicFunctions.cs',
         lambda s: s.replace('.colliderEntityId != EntityId.None', '.colliderInstanceID != 0')
                    .replace('.colliderEntityId == EntityId.None', '.colliderInstanceID == 0'))
    one_pool()
    keep_runes()
    custom_summons()
    shops()

def one_pool():
    """Learning as in our game (claude/balance.md §4, TzRules): one pool of training points for attributes
    and skills — the kit keeps StatPoint and SkillPoint, we give both the same points and spend a point from
    both; attributes at most 24 together; a step costs the teacher's price (15·n² coins), free for a newcomer."""
    mark = '// Tz: one pool'
    def fix(s):
        if mark in s: return s
        a = '            playerCharacter.StatPoint -= 1;\n'
        b = '            playerCharacter.SkillPoint -= requireSkillPoint;\n'
        c = '            if (!playerCharacter.AddAttribute(out UITextKeys gameMessage, request.dataId))\n'
        d = '            int requireGold = skill.GetRequireCharacterGold(learnLevel);\n'
        assert a in s and b in s and c in s and d in s, 'the kit changed: one_pool needs a new look'
        check = ('            if (!TzRules.CanRaiseAttribute(playerCharacter, request.dataId, out UITextKeys tzMessage)) ' + mark + '\n'
                 '            {\n'
                 '                result.InvokeError(new ResponseIncreaseAttributeAmountMessage() { message = tzMessage });\n'
                 '                return default;\n'
                 '            }\n')
        return (s.replace(c, check + c)
                 .replace(a, a + '            playerCharacter.SkillPoint -= 1; ' + mark + '\n'
                               + '            TzRules.PayForAttribute(playerCharacter, request.dataId); ' + mark + '\n')
                 .replace(b, b + '            playerCharacter.StatPoint -= requireSkillPoint; ' + mark + '\n')
                 .replace(d, '            int requireGold = TzRules.SkillGold(playerCharacter, skill, learnLevel); ' + mark + '\n'))
    edit('Core/Scripts/Networking/Implements/DefaultServerCharacterMessageHandlers.cs', fix)


def drop_urp():
    """The graphic settings for URP need the URP package; we stay on the built-in pipeline for now."""
    folder = os.path.join(KIT, 'ThirdParty', 'GraphicSettings', 'Scripts')
    for f in sorted(os.listdir(folder)):
        if 'Urp' in f:
            os.remove(os.path.join(folder, f)); print('removed', f)


def keep_runes():
    """A spell's rune i.r.* stays after the cast, its scroll i.m.* burns (Magic.kt); the kit spends every
    thing a skill is used from. TzSpell.KeepsItem tells which to keep."""
    mark = '// Tz: runes stay'
    def fix(s):
        if mark in s: return s
        a = 'if (IsServer && itemDataId.HasValue && Entity.DecreaseItems(itemDataId.Value, 1))'
        assert a in s, 'the kit changed: keep_runes needs a new look'
        return s.replace(a, 'if (IsServer && itemDataId.HasValue && !TzSpell.KeepsItem(itemDataId.Value) && Entity.DecreaseItems(itemDataId.Value, 1)) ' + mark)
    edit('Core/Scripts/Gameplay/CharacterSystems/CharacterActionsSystem/DefaultCharacterUseSkillComponent.cs', fix)


def custom_summons():
    """Our subordinates (TzSummons) are Custom summons whose dataId is the monster prefab's hash asset id; the
    kit asks its single custom manager for one prefab, so look the prefab up by the id first."""
    mark = '// Tz: custom summons'
    def fix(s):
        if mark in s: return s
        a = '                case SummonType.Custom:\n                    return GameInstance.CustomSummonManager.GetPrefab('
        assert a in s, 'the kit changed: custom_summons needs a new look'
        return s.replace(a, '                case SummonType.Custom:\n'
            '#if !EXCLUDE_PREFAB_REFS || DISABLE_ADDRESSABLES\n'
            '                    if (GameInstance.MonsterCharacterEntities.TryGetValue(dataId, out BaseMonsterCharacterEntity tzPrefab) && tzPrefab != null) ' + mark + '\n'
            '                    {\n'
            '                        prefab = tzPrefab;\n'
            '                        return false;\n'
            '                    }\n'
            '#endif\n'
            '                    return GameInstance.CustomSummonManager.GetPrefab(')
    edit('Core/Scripts/AddressableAssets/AssetReferenceExtensions.cs', fix)

def shops():
    """Traders of our game (TzShops): the stock of a position and what a trader buys and for how much depend
    on the shop, which the kit's GameplayRule calls are not told — tell TzShops which shop and position it is."""
    mark = 'Tz: shops'
    def buy(s):
        if mark in s: return s
        a = '            if (!CurrentGameplayRule.CurrenciesEnoughToBuyItem(Entity, sellItem, amount))\n'
        b = '            GameInstance.ServerLogHandlers.LogBuyNpcItem(Entity, sellItem, amount);\n'
        assert a in s and b in s, 'the kit changed: shops needs a new look'
        return (s.replace(a, '            TzShops.Current = dialog; TzShops.CurrentIndex = index; // ' + mark + '\n' + a)
                 .replace(b, b + '            TzShops.CurrentIndex = -1; // ' + mark + '\n'))
    edit('Core/Scripts/Gameplay/CharacterSystems/PlayerCharacterSystems/PlayerCharacterNpcActionComponent.cs', buy)
    def sell(s):
        if mark in s: return s
        a = 'playerCharacterEntity.NpcActionComponent.AccessingNpcShopDialog(out _)'
        assert s.count(a) == 2, 'the kit changed: shops needs a new look'
        return s.replace(a, 'playerCharacterEntity.NpcActionComponent.AccessingNpcShopDialog(out TzShops.Current) /* Tz: shops */')
    edit('Core/Scripts/Networking/Implements/DefaultServerInventoryMessageHandlers.cs', sell)
    def validate(s):
        if mark in s: return s
        a = '            if (targetItemData.RestrictSelling)\n'
        assert a in s, 'the kit changed: shops needs a new look'
        return s.replace(a, '            if (targetItemData.RestrictSelling || !TzShops.Buys(TzShops.Current, targetItemData)) // ' + mark + '\n', 1)
    edit('Core/Scripts/CharacterData/CharacterInventoryExtensions.cs', validate)



if __name__ == '__main__':
    sys.exit(main())

