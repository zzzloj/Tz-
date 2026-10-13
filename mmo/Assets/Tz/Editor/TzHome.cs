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
}
