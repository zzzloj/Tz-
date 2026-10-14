using UnityEditor;
using UnityEngine;

/// <summary>
/// The first screens' look (TzHomeSkin) put on the home canvas (CanvasHome-CE) at build time, with the
/// game's splash picture (content/art/brand/splash-landscape, made a png by prepare_art.py) and the
/// Cyrillic font Alegreya Sans for its letters.
/// </summary>
public static class TzHome
{
    const string Canvas = "Assets/Tz/BaseDemo/Prefabs/UI/Home/CanvasHome-CE.prefab";
    const string Splash = "Assets/Tz/Art/UI/splash.png";
    const string Font = "Assets/Tz/Fonts/alegreya_sans_regular.ttf";

    public static int Build()
    {
        Sprite sprite = null;
        if (System.IO.File.Exists(Splash))
        {
            AssetDatabase.ImportAsset(Splash);
            var imp = (TextureImporter)AssetImporter.GetAtPath(Splash);
            imp.textureType = TextureImporterType.Sprite;
            imp.spriteImportMode = SpriteImportMode.Single;
            imp.mipmapEnabled = false;
            imp.maxTextureSize = 2048;
            imp.SaveAndReimport();
            sprite = AssetDatabase.LoadAssetAtPath<Sprite>(Splash);
        }
        var root = PrefabUtility.LoadPrefabContents(Canvas);
        try
        {
            foreach (var old in root.GetComponents<TzHomeSkin>()) Object.DestroyImmediate(old);
            EmailField(root);
            var skin = root.AddComponent<TzHomeSkin>();
            skin.background = sprite;
            skin.font = AssetDatabase.LoadAssetAtPath<Font>(Font);
            PrefabUtility.SaveAsPrefabAsset(root, Canvas);
            Debug.Log($"[TzImport] home screens: skin, picture {(sprite != null ? "yes" : "NO")}, font {(skin.font != null ? "yes" : "NO")}");
            return sprite != null && skin.font != null ? 0 : 1;
        }
        catch (System.Exception e)
        {
            Debug.LogError("[TzImport] home screens: " + e);
            return 1;
        }
        finally { PrefabUtility.UnloadPrefabContents(root); }
    }

    /// <summary>
    /// A mail field in the registration (the framework sends it with the account, the form had none): under
    /// the password, a copy of it with its label; what is below moves down, the window grows.
    /// </summary>
    static void EmailField(GameObject root)
    {
        var reg = root.GetComponentInChildren<MultiplayerARPG.MMO.UIMmoRegister>(true);
        if (reg == null || reg.uiTextPassword == null || reg.uiTextUsername == null) { Debug.LogError("[TzImport] registration: no fields"); return; }
        if (reg.uiTextEmail != null) return;
        var pass = (RectTransform)reg.uiTextPassword.transform;
        var user = (RectTransform)reg.uiTextUsername.transform;
        var content = pass.parent;
        // The password's label: the text right above the field.
        RectTransform label = null;
        foreach (Transform c in content)
            if (c.GetComponent<UnityEngine.UI.Text>() != null && c.GetComponent<UnityEngine.UI.Text>().text.Trim() == "Password") label = (RectTransform)c;
        float step = Mathf.Abs(user.anchoredPosition.y - pass.anchoredPosition.y);
        if (step < 1) step = 60;
        var field = Object.Instantiate(pass.gameObject, content).GetComponent<RectTransform>();
        field.name = "InputEmail";
        RectTransform fieldLabel = null;
        if (label != null)
        {
            fieldLabel = Object.Instantiate(label.gameObject, content).GetComponent<RectTransform>();
            fieldLabel.name = "LabelEmail";
            fieldLabel.GetComponent<UnityEngine.UI.Text>().text = "Почта";
        }
        var input = field.GetComponent<UnityEngine.UI.InputField>();
        input.text = "";
        input.contentType = UnityEngine.UI.InputField.ContentType.EmailAddress;
        input.keyboardType = TouchScreenKeyboardType.EmailAddress;
        if (input.placeholder is UnityEngine.UI.Text ph) ph.text = "Почта (чтобы восстановить пароль)";
        var wrapper = field.GetComponent<InputFieldWrapper>();
        if (wrapper == null) wrapper = field.gameObject.AddComponent<InputFieldWrapper>();
        wrapper.unityInputField = input;
        reg.uiTextEmail = wrapper;
        if (content.GetComponent<UnityEngine.UI.LayoutGroup>() != null)
        {
            int at = pass.GetSiblingIndex() + 1;
            if (fieldLabel != null) fieldLabel.SetSiblingIndex(at++);
            field.SetSiblingIndex(at);
        }
        else
        {
            foreach (Transform c in content)
            {
                var rt = (RectTransform)c;
                if (rt == field || rt == fieldLabel) continue;
                if (rt.anchoredPosition.y < pass.anchoredPosition.y - 1) rt.anchoredPosition -= new Vector2(0, step);
            }
            field.anchoredPosition = pass.anchoredPosition - new Vector2(0, step);
            if (fieldLabel != null) fieldLabel.anchoredPosition = label.anchoredPosition - new Vector2(0, step);
            var window = content.parent as RectTransform;
            if (window != null) window.sizeDelta += new Vector2(0, step);
            var contentRt = content as RectTransform;
            if (contentRt != null && contentRt.anchorMin.y == contentRt.anchorMax.y) contentRt.sizeDelta += new Vector2(0, step);
        }
        EditorUtility.SetDirty(reg);
        Debug.Log("[TzImport] registration: mail field");
    }
}
