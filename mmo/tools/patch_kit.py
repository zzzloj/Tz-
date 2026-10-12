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

def one_pool():
    """Our training points are one pool for attributes and skills (claude/balance.md §4); the kit keeps two,
    StatPoint and SkillPoint. TzGameplayRule and TzLoginServerDataManager give both the same points, and
    here spending one point spends it from both, so they stay equal."""
    mark = '// Tz: one pool'
    def fix(s):
        if mark in s: return s
        a = '            playerCharacter.StatPoint -= 1;\n'
        b = '            playerCharacter.SkillPoint -= requireSkillPoint;\n'
        assert a in s and b in s, 'the kit changed: one_pool needs a new look'
        return (s.replace(a, a + '            playerCharacter.SkillPoint -= 1; ' + mark + '\n')
                 .replace(b, b + '            playerCharacter.StatPoint -= requireSkillPoint; ' + mark + '\n'))
    edit('Core/Scripts/Networking/Implements/DefaultServerCharacterMessageHandlers.cs', fix)

def drop_urp():
    """The graphic settings for URP need the URP package; we stay on the built-in pipeline for now."""
    folder = os.path.join(KIT, 'ThirdParty', 'GraphicSettings', 'Scripts')
    for f in sorted(os.listdir(folder)):
        if 'Urp' in f:
            os.remove(os.path.join(folder, f)); print('removed', f)


if __name__ == '__main__':
    sys.exit(main())
