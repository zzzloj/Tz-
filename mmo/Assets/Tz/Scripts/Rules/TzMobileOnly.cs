using MultiplayerARPG;
using UnityEngine;

/// <summary>Shows its object only on a phone (or the editor's mobile test): our touch controls (TzMobile).</summary>
public class TzMobileOnly : MonoBehaviour
{
    void Awake()
    {
        if (!Application.isMobilePlatform && !GameInstance.IsMobileTestInEditor())
            gameObject.SetActive(false);
    }
}
