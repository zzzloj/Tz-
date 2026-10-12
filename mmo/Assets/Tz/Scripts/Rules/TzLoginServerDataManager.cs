using System.Collections.Generic;
using MultiplayerARPG;
using MultiplayerARPG.MMO;
using Tz.Rules;
using UnityEngine;

/// <summary>
/// The login server's data manager with our new characters: they start with the training points of
/// creation (balance.json level.creationPoints, 4) in the one pool of attributes and skills — the kit
/// keeps StatPoint and SkillPoint, we give both the same (mmo/tools/patch_kit.py one_pool).
/// Put on a child of the LoginNetworkManager (TzImport does it); the rest is the kit's default.
/// </summary>
public class TzLoginServerDataManager : MonoBehaviour, ILoginServerDataManager
{
    readonly DefaultLoginServerDataManager kit = new DefaultLoginServerDataManager();

    public string GenerateCharacterId() => kit.GenerateCharacterId();

    public bool CanCreateCharacter(ref int dataId, ref int entityId, ref int factionId, IList<CharacterDataBoolean> publicBools, IList<CharacterDataInt32> publicInts, IList<CharacterDataFloat32> publicFloats, out UITextKeys errorMessage)
        => kit.CanCreateCharacter(ref dataId, ref entityId, ref factionId, publicBools, publicInts, publicFloats, out errorMessage);

    public void SetNewPlayerCharacterData(PlayerCharacterData playerCharacterData, string characterName, int dataId, int entityId, int factionId, IList<CharacterDataBoolean> publicBools, IList<CharacterDataInt32> publicInts, IList<CharacterDataFloat32> publicFloats)
    {
        kit.SetNewPlayerCharacterData(playerCharacterData, characterName, dataId, entityId, factionId, publicBools, publicInts, publicFloats);
        int points = TzGameplayRule.Balance.CreationPoints;
        playerCharacterData.StatPoint = points;
        playerCharacterData.SkillPoint = points;
    }

    public string GenerateAccessToken(string userId) => kit.GenerateAccessToken(userId);

    public string GetUserIdFromAccessToken(string accessToken) => kit.GetUserIdFromAccessToken(accessToken);
}
