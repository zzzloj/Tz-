using Insthync.CameraAndInput;
using TMPro;
using UnityEditor;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// Touch controls on the gameplay canvas (CanvasGameplay-CE), shown on phones only (TzMobileOnly):
/// a joystick at the bottom left (the framework's axes Horizontal/Vertical — the hero walks),
/// a big "Удар" button at the bottom right (the framework's "Attack": the nearest enemy, then the chosen one).
/// Tapping a monster, a person or the ground works as in the framework (both modes are on).
/// </summary>
public static class TzMobile
{
    const string Canvas = "Assets/Tz/BaseDemo/Prefabs/UI/CanvasGameplay-CE.prefab";

    public static int Build()
    {
        var root = PrefabUtility.LoadPrefabContents(Canvas);
        try
        {
            var old = root.transform.Find("TzMobile");
            if (old != null) Object.DestroyImmediate(old.gameObject);
            var knob = AssetDatabase.GetBuiltinExtraResource<Sprite>("UI/Skin/Knob.psd");

            var layer = New("TzMobile", root.transform);
            Stretch(layer);
            layer.gameObject.AddComponent<TzMobileOnly>();
            layer.SetAsFirstSibling();   // under the windows of the game

            // The joystick: a touch area at the bottom left, the ring and the handle in it.
            var area = New("Joystick", layer);
            area.anchorMin = area.anchorMax = new Vector2(0, 0);
            area.pivot = new Vector2(0, 0);
            area.anchoredPosition = new Vector2(20, 20);
            area.sizeDelta = new Vector2(260, 260);
            var hit = area.gameObject.AddComponent<Image>();
            hit.color = new Color(0, 0, 0, 0.001f);
            var ring = New("Background", area);
            ring.sizeDelta = new Vector2(190, 190);
            var ringImage = ring.gameObject.AddComponent<Image>();
            ringImage.sprite = knob; ringImage.color = new Color(1, 1, 1, 0.18f); ringImage.raycastTarget = false;
            var handle = New("Handle", area);
            handle.sizeDelta = new Vector2(90, 90);
            var handleImage = handle.gameObject.AddComponent<Image>();
            handleImage.sprite = knob; handleImage.color = new Color(1f, 0.9f, 0.6f, 0.55f); handleImage.raycastTarget = false;
            var stick = area.gameObject.AddComponent<MobileMovementJoystick>();
            stick.movementRange = 70;
            stick.axisXName = "Horizontal";
            stick.axisYName = "Vertical";
            stick.controllerBackground = ring;
            stick.controllerHandler = handle;
            stick.backgroundAlphaWhileIdling = 0.6f;
            stick.handlerAlphaWhileIdling = 0.6f;

            // The blow: a round button at the bottom right.
            var attack = New("Attack", layer);
            attack.anchorMin = attack.anchorMax = new Vector2(1, 0);
            attack.pivot = new Vector2(1, 0);
            attack.anchoredPosition = new Vector2(-40, 40);
            attack.sizeDelta = new Vector2(130, 130);
            var attackImage = attack.gameObject.AddComponent<Image>();
            attackImage.sprite = knob; attackImage.color = new Color(0.65f, 0.15f, 0.1f, 0.75f);
            var button = attack.gameObject.AddComponent<MobileInputButton>();
            button.keyName = "Attack";
            var label = New("Label", attack);
            Stretch(label);
            var text = label.gameObject.AddComponent<TextMeshProUGUI>();
            text.text = "Удар";
            text.fontSize = 28;
            text.alignment = TextAlignmentOptions.Center;
            text.color = new Color(1f, 0.92f, 0.75f);
            text.raycastTarget = false;

            PrefabUtility.SaveAsPrefabAsset(root, Canvas);
            Debug.Log("[TzImport] mobile controls: joystick, attack button");
            return 0;
        }
        catch (System.Exception e)
        {
            Debug.LogError("[TzImport] mobile controls: " + e);
            return 1;
        }
        finally { PrefabUtility.UnloadPrefabContents(root); }
    }

    static RectTransform New(string name, Transform parent)
    {
        var go = new GameObject(name, typeof(RectTransform));
        go.layer = 5;   // UI
        var rt = (RectTransform)go.transform;
        rt.SetParent(parent, false);
        return rt;
    }

    static void Stretch(RectTransform rt)
    {
        rt.anchorMin = Vector2.zero; rt.anchorMax = Vector2.one;
        rt.offsetMin = rt.offsetMax = Vector2.zero;
    }
}
