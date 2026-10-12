using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Tz.World;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using Object = UnityEngine.Object;

/// <summary>
/// Pictures of the built map for looking at it without a phone: CI runs the editor with graphics after the
/// server build (.github/workflows/mmo.yml, step "Pictures of the map") and puts them on the branch mmo-shots.
/// The whole map from above and views from the game's camera height at the start, streets, the pier, the
/// forest, the cemetery, the lake. People (yellow) and monsters (red) stand as capsules where they will be.
/// Run: unity-editor -batchmode -projectPath mmo -executeMethod TzShots.Take (not -nographics).
/// </summary>
public static class TzShots
{
    const string DemoMap = "Assets/Tz/BaseDemo/Scenes/BaseMap-CE.unity";

    public static void Take()
    {
        int code = 0;
        try { Shots(); }
        catch (Exception e) { Debug.LogException(e); code = 1; }
        EditorApplication.Exit(code);
    }

    static void Shots()
    {
        string repo = Path.GetFullPath(Path.Combine(Application.dataPath, "..", ".."));
        string outDir = Path.Combine(repo, "mmo", "build", "shots");
        Directory.CreateDirectory(outDir);
        Debug.Log("[TzShots] graphics: " + SystemInfo.graphicsDeviceType + " " + SystemInfo.graphicsDeviceName);
        // The other maps from above first (their scenes are copies made by the import).
        for (int i = 1; i < TzWorld.Maps.Length; i++)
        {
            var def = TzWorld.Maps[i];
            string path = $"Assets/Tz/Generated/Maps/{def.scene}.unity";
            if (!def.on || !File.Exists(Path.Combine(Application.dataPath, "..", path))) continue;
            EditorSceneManager.OpenScene(path, OpenSceneMode.Single);
            Unfold();
            var wi = TzWorld.Build(repo, i, id => false);
            var c = TopCamera(wi);
            Shot(c, Path.Combine(outDir, $"0{i}-map-{def.scene}.jpg"), 2048, Mathf.RoundToInt(2048 * (float)((wi.MaxZ - wi.MinZ) / (wi.MaxX - wi.MinX))));
        }

        EditorSceneManager.OpenScene(DemoMap, OpenSceneMode.Single);
        Unfold();
        var world = TzWorld.Build(repo, 0, id => false);

        // Capsules where the people and the monsters will stand.
        var yellow = new Material(Shader.Find("Standard")) { color = new Color(1f, 0.85f, 0.2f) };
        var red = new Material(Shader.Find("Standard")) { color = new Color(0.85f, 0.15f, 0.1f) };
        foreach (var n in world.Npcs) Capsule(n.Pos, yellow);
        foreach (var s in world.Spawns) Capsule(s.Pos, red);

        var cam = new GameObject("TzShotCamera").AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.55f, 0.65f, 0.75f);
        cam.farClipPlane = 3000;
        cam.nearClipPlane = 0.3f;

        // The whole map from above.
        float w = (float)(world.MaxX - world.MinX), h = (float)(world.MaxZ - world.MinZ);
        cam.orthographic = true;
        cam.orthographicSize = h / 2;
        cam.transform.SetPositionAndRotation(new Vector3((float)(world.MinX + world.MaxX) / 2, 400, (float)(world.MinZ + world.MaxZ) / 2), Quaternion.Euler(90, 0, 0));
        Shot(cam, Path.Combine(outDir, "00-map.jpg"), 2048, Mathf.RoundToInt(2048 * h / w));

        // Views as the game's camera sees the hero: behind, above, looking down at ~40°.
        cam.orthographic = false;
        cam.fieldOfView = 50;
        var views = new List<(string name, Vector3 at, float yaw, float dist)>
        {
            ("01-start", W(world.Start), 0, 16),
            ("02-start-wide", W(world.Start), 200, 40),
        };
        void Place(string file, Func<TzWorld.Place, bool> pick, float yaw, float dist)
        {
            var p = world.Places.FirstOrDefault(x => !x.Indoor && pick(x));
            if (p != null) views.Add((file, W(p.Pos), yaw, dist));
        }
        Place("03-street", p => p.Name == "Центральная улица", 30, 22);
        Place("04-pier", p => p.Kind == TzWorld.Kind.Pier, 150, 26);
        Place("05-forest", p => p.Name == "Березовая роща", 80, 18);
        Place("06-cemetery", p => p.Kind == TzWorld.Kind.Cemetery, 0, 24);
        Place("07-lake", p => p.Kind == TzWorld.Kind.Lake, 180, 30);
        Place("08-swamp", p => p.Kind == TzWorld.Kind.Swamp, 45, 24);
        var tavern = world.Buildings.FirstOrDefault(b => b.Name == "Таверна");
        if (tavern != null) views.Add(("09-tavern", W(tavern.Door), (float)tavern.Rot + 180, 14));
        // The 3D creatures in a row near the start, caught mid-step of their walk.
        var creatures = AssetDatabase.FindAssets("t:Prefab", new[] { "Assets/Tz/Generated/Monsters" })
            .Select(AssetDatabase.GUIDToAssetPath).Select(AssetDatabase.LoadAssetAtPath<GameObject>)
            .Where(p => p != null && p.GetComponentsInChildren<SkinnedMeshRenderer>(true).Length > 0).ToList();
        var row = W(world.Start) + new Vector3(4, 0, -6);
        for (int i = 0; i < creatures.Count; i++)
        {
            var c = (GameObject)PrefabUtility.InstantiatePrefab(creatures[i]);
            c.transform.SetPositionAndRotation(row + Vector3.right * i * 3f, Quaternion.Euler(0, 20, 0));   // facing the camera
            var model = c.GetComponents<Component>().FirstOrDefault(k => k != null && k.GetType().Name == "PlayableCharacterModel");
            var clip = model != null ? new SerializedObject(model).FindProperty("defaultAnimations.moveStates.forwardState.clip")?.objectReferenceValue as AnimationClip : null;
            if (clip != null) clip.SampleAnimation(c, clip.length * 0.3f);
            Debug.Log($"[TzShots] creature {creatures[i].name}, walk {(clip != null ? clip.length.ToString("0.00") + " s" : "none")}");
        }
        if (creatures.Count > 0) views.Add(("10-creatures", row + Vector3.right * (creatures.Count - 1) * 1.5f, 180, 6));
        foreach (var v in views)
        {
            var rot = Quaternion.Euler(40, v.yaw, 0);
            cam.transform.SetPositionAndRotation(v.at + Vector3.up * 1.5f - rot * Vector3.forward * v.dist, rot);
            Shot(cam, Path.Combine(outDir, v.name + ".jpg"), 1600, 900);
        }
        Debug.Log($"[TzShots] {views.Count + 1} pictures in {outDir}");
    }

    /// <summary>The instanced trees and props (TzInstancer draws them only in play) as plain objects for the pictures.</summary>
    static void Unfold()
    {
        int n = 0;
        foreach (var r in Object.FindObjectsByType<TzInstancer>(FindObjectsSortMode.None))
            foreach (var m in r.matrices)
            {
                var go = new GameObject("shot");
                go.transform.SetPositionAndRotation(m.GetColumn(3), m.rotation);
                go.transform.localScale = m.lossyScale;
                go.AddComponent<MeshFilter>().sharedMesh = r.nearMesh;
                go.AddComponent<MeshRenderer>().sharedMaterial = r.nearMaterial;
                n++;
            }
        Debug.Log($"[TzShots] {n} instanced copies unfolded");
    }

    /// <summary>A camera over the whole map, looking down.</summary>
    static Camera TopCamera(TzWorld world)
    {
        var cam = new GameObject("TzShotCamera").AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.55f, 0.65f, 0.75f);
        cam.farClipPlane = 3000;
        cam.orthographic = true;
        cam.orthographicSize = (float)(world.MaxZ - world.MinZ) / 2;
        cam.transform.SetPositionAndRotation(new Vector3((float)(world.MinX + world.MaxX) / 2, 400, (float)(world.MinZ + world.MaxZ) / 2), Quaternion.Euler(90, 0, 0));
        return cam;
    }

    static Vector3 W(TzWorld.V v) => new Vector3((float)v.X, 0, (float)v.Z);

    static void Capsule(TzWorld.V at, Material m)
    {
        var c = GameObject.CreatePrimitive(PrimitiveType.Capsule);
        Object.DestroyImmediate(c.GetComponent<Collider>());
        c.transform.position = W(at) + Vector3.up;
        c.GetComponent<MeshRenderer>().sharedMaterial = m;
    }

    static void Shot(Camera cam, string path, int width, int height)
    {
        var rt = new RenderTexture(width, height, 24) { antiAliasing = 4 };
        cam.targetTexture = rt;
        cam.Render();
        var prev = RenderTexture.active;
        RenderTexture.active = rt;
        var tex = new Texture2D(width, height, TextureFormat.RGB24, false);
        tex.ReadPixels(new Rect(0, 0, width, height), 0, 0);
        tex.Apply();
        RenderTexture.active = prev;
        cam.targetTexture = null;
        File.WriteAllBytes(path, tex.EncodeToJPG(85));
        Object.DestroyImmediate(tex);
        rt.Release();
        Debug.Log("[TzShots] " + path);
    }
}
