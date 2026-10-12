using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using MultiplayerARPG;
using TMPro;
using Tz.World;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.Rendering;
using Object = UnityEngine.Object;

/// <summary>
/// Turns a map plan (TzWorld) into the demo map scene: the ground by cells, paths and clearings, buildings
/// with signs, trees, rocks and the rest, invisible walls at water and the map's edge, the navigation mesh
/// for monsters, the map's start. Simple shapes for now: a model content/art/models/&lt;kind&gt;.glb will
/// replace a shape of its kind (step 4, models).
/// </summary>
public static class TzMap
{
    const string Dir = "Assets/Tz/Generated/World";
    const string GroundArt = "Assets/Tz/Art/Ground";
    const float Chunk = 64f;
    /// <summary>Layers the framework does not take for ground (GameInstance.GetGameEntityGroundDetectionLayerMask): monsters and drops do not land on roofs, trees and the walls over water.</summary>
    const int NotGround = 1, WaterLayer = 4;

    static readonly Dictionary<string, Material> materials = new Dictionary<string, Material>();

    /// <summary>A material: the texture Assets/Tz/Art/Ground/&lt;texture&gt;.png (mmo/tools/prepare_art.py) tinted, or the colour alone.</summary>
    static Material Mat(string name, Color color, string texture = null, float smooth = 0.1f)
    {
        if (materials.TryGetValue(name, out var m) && m != null) return m;
        string path = $"{Dir}/{name}.mat";
        AssetDatabase.DeleteAsset(path);
        m = new Material(Shader.Find("Standard")) { name = name, color = color };
        m.SetFloat("_Glossiness", smooth);
        if (texture != null)
        {
            var tex = AssetDatabase.LoadAssetAtPath<Texture2D>($"{GroundArt}/{texture}.png");
            if (tex != null) m.mainTexture = tex;
        }
        m.enableInstancing = true;
        AssetDatabase.CreateAsset(m, path);
        materials[name] = m;
        return m;
    }

    static Material SurfaceMat(TzWorld.Surface s)
    {
        switch (s)
        {
            case TzWorld.Surface.Grass: return Mat("ground-grass", new Color(1.2f, 1.2f, 1.1f), "ground-grass");
            case TzWorld.Surface.ForestFloor: return Mat("ground-forest", new Color(0.75f, 0.85f, 0.7f), "ground-grass");
            case TzWorld.Surface.Dirt: return Mat("ground-dirt", Color.white, "ground-dirt");
            case TzWorld.Surface.Sand: return Mat("ground-sand", new Color(1.35f, 1.2f, 0.95f), "ground-dirt");
            case TzWorld.Surface.Swamp: return Mat("ground-swamp", new Color(0.7f, 0.8f, 0.6f), "ground-grass");
            case TzWorld.Surface.Cobble: return Mat("ground-cobble", Color.white, "ground-cobble");
            case TzWorld.Surface.Planks: return Mat("ground-planks", Color.white, "ground-planks");
            case TzWorld.Surface.Road: return Mat("ground-road", Color.white, "ground-road");
            default: return Mat("ground-water", new Color(0.8f, 0.95f, 1.15f), "ground-water", 0.5f);
        }
    }

    /// <summary>Height of a surface's paths, so that crossing paths of different kinds do not flicker.</summary>
    static float Lift(TzWorld.Surface s) => s == TzWorld.Surface.Planks ? 0.05f : s == TzWorld.Surface.Cobble ? 0.04f : s == TzWorld.Surface.Road ? 0.03f : 0.02f;

    // ---- meshes ---------------------------------------------------------------------------------------------
    /// <summary>Collects triangles of one material in one chunk; quads get UVs in world metres / tile.</summary>
    class Builder
    {
        public readonly List<Vector3> V = new List<Vector3>(), N = new List<Vector3>();
        public readonly List<Vector2> UV = new List<Vector2>();
        public readonly List<int> T = new List<int>();

        public void Quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, float tile)
        {
            int i = V.Count;
            foreach (var p in new[] { a, b, c, d }) { V.Add(p); N.Add(Vector3.up); UV.Add(new Vector2(p.x / tile, p.z / tile)); }
            T.AddRange(new[] { i, i + 1, i + 2, i, i + 2, i + 3 });
        }

        public void Add(Mesh m, Matrix4x4 at)
        {
            int i = V.Count;
            var vs = m.vertices; var ns = m.normals; var uvs = m.uv;
            for (int k = 0; k < vs.Length; k++)
            {
                V.Add(at.MultiplyPoint3x4(vs[k]));
                N.Add(at.MultiplyVector(k < ns.Length ? ns[k] : Vector3.up).normalized);
                UV.Add(k < uvs.Length ? uvs[k] : Vector2.zero);
            }
            foreach (var t in m.triangles) T.Add(i + t);
        }

        public Mesh Make(string name)
        {
            var m = new Mesh { name = name, indexFormat = IndexFormat.UInt32 };
            m.SetVertices(V); m.SetNormals(N); m.SetUVs(0, UV); m.SetTriangles(T, 0);
            m.RecalculateBounds();
            return m;
        }
    }

    /// <summary>Builders by (material, chunk).</summary>
    class Batch
    {
        readonly Dictionary<(Material, int, int), Builder> parts = new Dictionary<(Material, int, int), Builder>();
        public Builder For(Material m, Vector3 at)
        {
            var key = (m, Mathf.FloorToInt(at.x / Chunk), Mathf.FloorToInt(at.z / Chunk));
            if (!parts.TryGetValue(key, out var b)) parts[key] = b = new Builder();
            return b;
        }

        /// <summary>Saves the meshes as assets and puts them under [parent]; with [collide], as mesh colliders only.</summary>
        public int Emit(Transform parent, string prefix, bool collide = false, int layer = 0)
        {
            int n = 0;
            foreach (var kv in parts)
            {
                if (kv.Value.T.Count == 0) continue;
                string name = $"{prefix}-{(kv.Key.Item1 != null ? kv.Key.Item1.name : "collider")}-{kv.Key.Item2}-{kv.Key.Item3}";
                var mesh = kv.Value.Make(name);
                AssetDatabase.CreateAsset(mesh, $"{Dir}/Meshes/{name}.asset");
                var go = new GameObject(name) { layer = layer };
                go.transform.SetParent(parent, false);
                if (collide)
                    go.AddComponent<MeshCollider>().sharedMesh = mesh;
                else
                {
                    go.AddComponent<MeshFilter>().sharedMesh = mesh;
                    go.AddComponent<MeshRenderer>().sharedMaterial = kv.Key.Item1;
                    GameObjectUtility.SetStaticEditorFlags(go, StaticEditorFlags.BatchingStatic);
                }
                n++;
            }
            return n;
        }
    }

    static Mesh cube, cylinder, sphere;
    static Dictionary<string, List<TzModels.Model>> models = new Dictionary<string, List<TzModels.Model>>();

    /// <summary>A model for [kind], chosen by the place (the same place gets the same one every build).</summary>
    static TzModels.Model ModelFor(string kind, Vector3 at)
    {
        if (!models.TryGetValue(kind, out var list) || list.Count == 0) return null;
        return list[TzWorld.Hash($"{Mathf.RoundToInt(at.x)},{Mathf.RoundToInt(at.z)}") % list.Count];
    }
    static Mesh Builtin(string name)
    {
        var go = GameObject.CreatePrimitive(name == "Cube" ? PrimitiveType.Cube : name == "Cylinder" ? PrimitiveType.Cylinder : PrimitiveType.Sphere);
        var m = go.GetComponent<MeshFilter>().sharedMesh;
        Object.DestroyImmediate(go);
        return m;
    }

    static Matrix4x4 TRS(Vector3 p, float rotY, Vector3 s) => Matrix4x4.TRS(p, Quaternion.Euler(0, rotY, 0), s);
    static Vector3 W(TzWorld.V v, float y = 0) => new Vector3((float)v.X, y, (float)v.Z);

    // ---- the map --------------------------------------------------------------------------------------------
    /// <summary>Builds the plan into [scenePath] (under its NavMeshSurface "Level") and sets the start of [mapInfoPath]. Returns problems.</summary>
    public static int Build(TzWorld w, string scenePath, string mapInfoPath)
    {
        int problems = 0;
        materials.Clear();
        AssetDatabase.DeleteAsset(Dir);
        Directory.CreateDirectory(Dir + "/Meshes");
        AssetDatabase.Refresh();
        cube = Builtin("Cube"); cylinder = Builtin("Cylinder"); sphere = Builtin("Sphere");
        farTree = null;
        models = TzModels.Load(Path.GetFullPath(Path.Combine(Application.dataPath, "..", "..")));

        var scene = EditorSceneManager.OpenScene(scenePath, OpenSceneMode.Single);
        var level = GameObject.Find("Level");
        var surface = level != null ? level.GetComponent<NavMeshSurface>() : null;
        if (surface == null) { Debug.LogError("[TzImport] map: no Level with a NavMeshSurface in " + scenePath); return 1; }
        for (int i = level.transform.childCount - 1; i >= 0; i--)
            Object.DestroyImmediate(level.transform.GetChild(i).gameObject);
        var root = new GameObject("TzWorld " + w.MapName).transform;
        root.SetParent(level.transform, false);

        Ground(w, root);
        Paths(w, root);
        Buildings(w, root);
        Props(w, root);
        Walls(w, root);

        // Light: a warm sun at an angle, soft ambient.
        var sun = Object.FindObjectsByType<Light>(FindObjectsSortMode.None).FirstOrDefault(l => l.type == LightType.Directional);
        if (sun != null) { sun.transform.rotation = Quaternion.Euler(50, -35, 0); sun.color = new Color(1f, 0.95f, 0.85f); sun.intensity = 1.1f; }
        RenderSettings.ambientMode = AmbientMode.Flat;
        RenderSettings.ambientLight = new Color(0.45f, 0.47f, 0.5f);

        // The navigation mesh (monsters walk by it) from the colliders: ground, walls, buildings, trunks, rocks.
        surface.collectObjects = CollectObjects.Children;
        surface.useGeometry = NavMeshCollectGeometry.PhysicsColliders;
        surface.layerMask = ~0;
        surface.BuildNavMesh();
        if (surface.navMeshData != null)
        {
            AssetDatabase.CreateAsset(surface.navMeshData, $"{Dir}/NavMesh.asset");
            EditorUtility.SetDirty(surface);
            var tri = NavMesh.CalculateTriangulation();
            Debug.Log($"[TzImport] map: navigation mesh {tri.indices.Length / 3} triangles");
        }
        else { problems++; Debug.LogError("[TzImport] map: the navigation mesh was not built"); }

        // The start of new characters (and of the map).
        var info = AssetDatabase.LoadMainAssetAtPath(mapInfoPath);
        if (info != null)
        {
            var so = new SerializedObject(info);
            var sp = so.FindProperty("startPosition");
            if (sp != null) sp.vector3Value = W(w.Start, 0.2f);
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(info);
        }
        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        foreach (var p in w.Problems) { problems++; Debug.LogError("[TzImport] map: " + p); }
        Debug.Log($"[TzImport] map «{w.MapName}»: {w.Places.Count(p => !p.Indoor)} places, {w.Paths.Count} paths, {w.Buildings.Count} buildings, " +
                  $"{w.Props.Count} props, {w.Npcs.Count} people, {w.Spawns.Count} spawns, {w.Exits.Count} exits, " +
                  $"{(w.MaxX - w.MinX):0}×{(w.MaxZ - w.MinZ):0} m, start {w.Start}");
        return problems;
    }

    static void Ground(TzWorld w, Transform root)
    {
        var batch = new Batch();
        float c = (float)TzWorld.Cell;
        int ni = w.Ground.GetLength(0), nj = w.Ground.GetLength(1);
        for (int i = 0; i < ni; i++)
            for (int j = 0; j < nj; j++)
            {
                var s = w.Ground[i, j];
                float x = (float)w.MinX + i * c, z = (float)w.MinZ + j * c;
                float y = s == TzWorld.Surface.Water ? -0.3f : 0f;
                var mat = SurfaceMat(s);
                batch.For(mat, new Vector3(x, 0, z)).Quad(new Vector3(x, y, z), new Vector3(x, y, z + c), new Vector3(x + c, y, z + c), new Vector3(x + c, y, z), 4f);
                // The bank under the water: a dark bed, so that the water is not a hole.
                if (s == TzWorld.Surface.Water)
                    batch.For(Mat("ground-bed", new Color(0.2f, 0.22f, 0.18f)), new Vector3(x, 0, z))
                        .Quad(new Vector3(x, -1.2f, z), new Vector3(x, -1.2f, z + c), new Vector3(x + c, -1.2f, z + c), new Vector3(x + c, -1.2f, z), 4f);
            }
        var go = new GameObject("Ground");
        go.transform.SetParent(root, false);
        batch.Emit(go.transform, "ground");
        // One flat collider under everything: the ground at y = 0.
        var box = go.AddComponent<BoxCollider>();
        box.center = new Vector3((float)(w.MinX + w.MaxX) / 2, -0.5f, (float)(w.MinZ + w.MaxZ) / 2);
        box.size = new Vector3((float)(w.MaxX - w.MinX), 1f, (float)(w.MaxZ - w.MinZ));
    }

    static void Paths(TzWorld w, Transform root)
    {
        var batch = new Batch();
        foreach (var p in w.Paths)
        {
            var a = W(p.A.Pos); var b = W(p.B.Pos);
            var dir = (b - a); dir.y = 0;
            if (dir.sqrMagnitude < 0.01f) continue;
            dir.Normalize();
            float hw = (float)p.Width / 2;
            var side = new Vector3(dir.z, 0, -dir.x) * hw;
            a -= dir * hw * 0.5f; b += dir * hw * 0.5f;
            var up = Vector3.up * Lift(p.Surface);
            batch.For(SurfaceMat(p.Surface), (a + b) / 2).Quad(a - side + up, b - side + up, b + side + up, a + side + up, 4f);
        }
        // Clearings of the places.
        foreach (var p in w.Places.Where(p => !p.Indoor))
        {
            var s = p.Kind == TzWorld.Kind.Town ? TzWorld.Surface.Cobble : p.Kind == TzWorld.Kind.Pier ? TzWorld.Surface.Planks
                : p.Kind == TzWorld.Kind.Road ? TzWorld.Surface.Road : TzWorld.Surface.Dirt;
            float r = p.Kind == TzWorld.Kind.Town || p.Kind == TzWorld.Kind.Pier ? 7 : 5;
            var c = W(p.Pos, Lift(s) + 0.005f);
            var bld = batch.For(SurfaceMat(s), c);
            const int n = 14;
            for (int k = 0; k < n; k++)
            {
                float a0 = k * Mathf.PI * 2 / n, a1 = (k + 1) * Mathf.PI * 2 / n;
                var p0 = c + new Vector3(Mathf.Sin(a0), 0, Mathf.Cos(a0)) * r;
                var p1 = c + new Vector3(Mathf.Sin(a1), 0, Mathf.Cos(a1)) * r;
                bld.Quad(c, p0, p1, c, 4f);
            }
        }
        var go = new GameObject("Paths");
        go.transform.SetParent(root, false);
        batch.Emit(go.transform, "path");
    }

    static readonly Dictionary<string, (Color wall, Color roof)> BuildingColors = new Dictionary<string, (Color, Color)>
    {
        ["home"] = (new Color(0.78f, 0.72f, 0.62f), new Color(0.45f, 0.22f, 0.16f)),
        ["house"] = (new Color(0.8f, 0.74f, 0.6f), new Color(0.5f, 0.25f, 0.18f)),
        ["shop"] = (new Color(0.74f, 0.62f, 0.45f), new Color(0.3f, 0.35f, 0.45f)),
        ["tavern"] = (new Color(0.6f, 0.45f, 0.32f), new Color(0.35f, 0.2f, 0.12f)),
        ["hall"] = (new Color(0.75f, 0.75f, 0.78f), new Color(0.28f, 0.3f, 0.38f)),
        ["barn"] = (new Color(0.55f, 0.42f, 0.3f), new Color(0.4f, 0.3f, 0.2f)),
    };

    static void Buildings(TzWorld w, Transform root)
    {
        var parent = new GameObject("Buildings").transform;
        parent.SetParent(root, false);
        var batch = new Batch();
        var roofMesh = Roof();
        int signs = 0;
        foreach (var b in w.Buildings)
        {
            var colors = BuildingColors.TryGetValue(b.Kind, out var cl) ? cl : BuildingColors["home"];
            var wall = Mat("wall-" + b.Kind, colors.wall);
            var roof = Mat("roof-" + b.Kind, colors.roof);
            var at = W(b.Pos);
            float rot = (float)b.Rot, bw = (float)b.W, bd = (float)b.D, bh = (float)b.H;
            float front = bd / 2;
            var model = ModelFor(b.Kind, at);
            if (model != null)
            {
                // The model fitted into the building's footprint, its front to the street.
                bool turned = Mathf.Abs(Mathf.Repeat(model.Front, 180f) - 90f) < 1f;
                float mx = turned ? model.Size.z : model.Size.x, mz = turned ? model.Size.x : model.Size.z;
                float k = Mathf.Min(bw / mx, bd / mz);
                bh = model.Size.y * k;
                front = mz * k / 2;
                var mgo = new GameObject("Model " + model.Name);
                mgo.transform.SetParent(parent, false);
                mgo.transform.SetPositionAndRotation(at - Vector3.up * model.Bottom * k, Quaternion.Euler(0, rot + model.Front, 0));
                mgo.transform.localScale = Vector3.one * k;
                mgo.AddComponent<MeshFilter>().sharedMesh = model.Mesh;
                mgo.AddComponent<MeshRenderer>().sharedMaterial = model.Material;
                GameObjectUtility.SetStaticEditorFlags(mgo, StaticEditorFlags.BatchingStatic);
            }
            else
            {
                batch.For(wall, at).Add(cube, TRS(at + Vector3.up * bh / 2, rot, new Vector3(bw, bh, bd)));
                batch.For(roof, at).Add(roofMesh, TRS(at + Vector3.up * bh, rot, new Vector3(bw + 0.6f, Mathf.Min(bw, bd) * 0.45f, bd + 0.6f)));
            }
            // The collider: a box of the building.
            var go = new GameObject(string.IsNullOrEmpty(b.Name) ? "House" : b.Name);
            go.transform.SetParent(parent, false);
            go.transform.SetPositionAndRotation(at + Vector3.up * bh / 2, Quaternion.Euler(0, rot, 0));
            go.layer = NotGround;
            go.AddComponent<BoxCollider>().size = new Vector3(bw, bh, bd);
            if (b.Rooms.Count == 0) continue;
            // A door and a sign over it, on the side to the street (+Z of the building).
            var fwd = Quaternion.Euler(0, rot, 0) * Vector3.forward;
            if (model == null)
                batch.For(Mat("door", new Color(0.25f, 0.15f, 0.08f)), at)
                    .Add(cube, TRS(at + fwd * (bd / 2 + 0.03f) + Vector3.up * 1.1f, rot, new Vector3(1.4f, 2.2f, 0.1f)));
            var sign = new GameObject("Sign " + b.Name, typeof(RectTransform));
            sign.transform.SetParent(go.transform, true);
            sign.transform.position = at + fwd * (front + 0.15f) + Vector3.up * Mathf.Min(bh - 0.6f, 3.2f);
            sign.transform.rotation = Quaternion.LookRotation(-fwd);
            var text = sign.AddComponent<TextMeshPro>();
            text.text = b.Name;
            text.fontSize = 5;
            text.alignment = TextAlignmentOptions.Center;
            text.color = new Color(1f, 0.92f, 0.7f);
            text.rectTransform.sizeDelta = new Vector2(Mathf.Max(6, bw), 1.5f);
            signs++;
        }
        batch.Emit(parent, "building");
    }

    /// <summary>A gable roof of unit size: ridge along Z, from y = 0 to 1, x from −0.5 to 0.5.</summary>
    static Mesh Roof()
    {
        var m = new Mesh { name = "roof" };
        Vector3 a = new Vector3(-0.5f, 0, -0.5f), b = new Vector3(0.5f, 0, -0.5f), c = new Vector3(0.5f, 0, 0.5f), d = new Vector3(-0.5f, 0, 0.5f);
        Vector3 r0 = new Vector3(0, 1, -0.5f), r1 = new Vector3(0, 1, 0.5f);
        m.vertices = new[] { a, r0, r1, d, b, c, r1, r0, a, b, r0, d, r1, c };
        m.triangles = new[] { 0, 2, 1, 0, 3, 2, 4, 6, 5, 4, 7, 6, 8, 10, 9, 11, 13, 12 };
        m.uv = new Vector2[m.vertices.Length];
        m.RecalculateNormals();
        return m;
    }

    static void Props(TzWorld w, Transform root)
    {
        var batch = new Batch();
        var colliders = new Batch();
        var bark = Mat("bark", new Color(0.36f, 0.26f, 0.18f));
        var leaves = Mat("leaves", new Color(0.22f, 0.42f, 0.2f));
        var bushes = Mat("bush", new Color(0.28f, 0.48f, 0.24f));
        var dead = Mat("deadwood", new Color(0.3f, 0.27f, 0.24f));
        var stone = Mat("stone", new Color(0.5f, 0.5f, 0.5f));
        var grave = Mat("grave", new Color(0.62f, 0.62f, 0.6f));
        var wood = Mat("wood", new Color(0.5f, 0.35f, 0.2f));
        var trees = new GameObject("Trees").transform;
        trees.SetParent(root, false);
        foreach (var p in w.Props)
        {
            var at = W(p.Pos);
            float s = (float)p.Scale, r = (float)p.Rot;
            // A model of the kind (not trees: they have their own far shape): one shared mesh, culled when small.
            if (p.Kind != "tree" && PropHeight.TryGetValue(p.Kind, out var height) && ModelFor(p.Kind, at) is TzModels.Model pm)
            {
                Instance(pm, at, r, height * s, trees, height < 2f ? 0.03f : 0.012f);
                if (PropCollider.TryGetValue(p.Kind, out var box))
                    colliders.For(null, at).Add(cube, TRS(at + Vector3.up * box.y / 2, r, Vector3.Scale(box, new Vector3(s, 1, s))));
                continue;
            }
            switch (p.Kind)
            {
                case "tree":
                    var treeModel = ModelFor("tree", at);
                    if (treeModel != null)
                        Tree(treeModel, at, r, s, trees, bark, leaves);
                    else
                    {
                        batch.For(bark, at).Add(cylinder, TRS(at + Vector3.up * 1.2f * s, r, new Vector3(0.45f, 1.2f, 0.45f) * s));
                        batch.For(leaves, at).Add(sphere, TRS(at + Vector3.up * 3.3f * s, r, new Vector3(3.2f, 3f, 3.2f) * s));
                    }
                    colliders.For(null, at).Add(cube, TRS(at + Vector3.up * 1.5f, r, new Vector3(0.7f, 3f, 0.7f) * s));
                    break;
                case "tree-dead":
                    batch.For(dead, at).Add(cylinder, TRS(at + Vector3.up * 1.6f * s, r, new Vector3(0.35f, 1.6f, 0.35f) * s));
                    batch.For(dead, at).Add(cylinder, Matrix4x4.TRS(at + Vector3.up * 2.4f * s, Quaternion.Euler(0, r, 40), new Vector3(0.15f, 0.8f, 0.15f) * s));
                    batch.For(dead, at).Add(cylinder, Matrix4x4.TRS(at + Vector3.up * 2.0f * s, Quaternion.Euler(0, r + 120, -35), new Vector3(0.12f, 0.6f, 0.12f) * s));
                    colliders.For(null, at).Add(cube, TRS(at + Vector3.up * 1.5f, r, new Vector3(0.6f, 3f, 0.6f) * s));
                    break;
                case "bush":
                    batch.For(bushes, at).Add(sphere, TRS(at + Vector3.up * 0.45f * s, r, new Vector3(1.6f, 1.0f, 1.4f) * s));
                    break;
                case "rock":
                    batch.For(stone, at).Add(sphere, TRS(at + Vector3.up * 0.3f * s, r, new Vector3(1.8f, 1.1f, 1.4f) * s));
                    colliders.For(null, at).Add(cube, TRS(at + Vector3.up * 0.5f, r, new Vector3(1.5f, 1.2f, 1.1f) * s));
                    break;
                case "grave":
                    batch.For(grave, at).Add(cube, TRS(at + Vector3.up * 0.4f, r, new Vector3(0.6f, 0.8f, 0.15f)));
                    break;
                case "barrel":
                    batch.For(wood, at).Add(cylinder, TRS(at + Vector3.up * 0.5f, r, new Vector3(0.8f, 0.5f, 0.8f)));
                    colliders.For(null, at).Add(cube, TRS(at + Vector3.up * 0.5f, r, new Vector3(0.8f, 1f, 0.8f)));
                    break;
                case "crates":
                    batch.For(wood, at).Add(cube, TRS(at + Vector3.up * 0.5f, r, Vector3.one));
                    batch.For(wood, at).Add(cube, TRS(at + new Vector3(0.3f, 1.3f, 0.2f), r + 20, Vector3.one * 0.6f));
                    colliders.For(null, at).Add(cube, TRS(at + Vector3.up * 0.5f, r, Vector3.one));
                    break;
                case "sign":
                    batch.For(wood, at).Add(cylinder, TRS(at + Vector3.up * 1.1f, r, new Vector3(0.15f, 1.1f, 0.15f)));
                    batch.For(wood, at).Add(cube, TRS(at + Vector3.up * 1.9f, r, new Vector3(1.4f, 0.5f, 0.08f)));
                    break;
            }
        }
        var go = new GameObject("Props");
        go.transform.SetParent(root, false);
        batch.Emit(go.transform, "props");
        colliders.Emit(go.transform, "props", true, NotGround);

        // The names of the exits out of the map on their signs.
        foreach (var e in w.Exits)
        {
            var sign = new GameObject("Exit " + e.Target, typeof(RectTransform));
            sign.transform.SetParent(go.transform, false);
            sign.transform.position = W(e.Pos, 1.9f) + Quaternion.Euler(0, (float)e.Rot, 0) * Vector3.back * 0.06f;
            sign.transform.rotation = Quaternion.Euler(0, (float)e.Rot, 0);
            var t = sign.AddComponent<TextMeshPro>();
            t.text = e.TargetName;
            t.fontSize = 2.2f;
            t.alignment = TextAlignmentOptions.Center;
            t.rectTransform.sizeDelta = new Vector2(1.4f, 0.5f);
            t.color = new Color(1f, 0.95f, 0.8f);
        }
    }

    static Mesh farTree;

    /// <summary>How tall a prop of a kind stands (× its scale), when a model draws it.</summary>
    static readonly Dictionary<string, float> PropHeight = new Dictionary<string, float>
    {
        ["tree-dead"] = 7f, ["bush"] = 1.4f, ["rock"] = 1.1f, ["barrel"] = 1.1f, ["crates"] = 1.3f, ["grave"] = 1.0f,
    };

    /// <summary>The box that stops walkers at a prop of a kind (none — walk through).</summary>
    static readonly Dictionary<string, Vector3> PropCollider = new Dictionary<string, Vector3>
    {
        ["tree-dead"] = new Vector3(0.6f, 3f, 0.6f), ["rock"] = new Vector3(1.5f, 1.2f, 1.1f),
        ["barrel"] = new Vector3(0.8f, 1f, 0.8f), ["crates"] = new Vector3(1f, 1f, 1f),
    };

    /// <summary>One prop drawn by a model [height] m tall, hidden when it takes less than [cull] of the screen.</summary>
    static void Instance(TzModels.Model m, Vector3 at, float rot, float height, Transform parent, float cull)
    {
        float k = height / Mathf.Max(0.01f, m.Size.y);
        var go = new GameObject(m.Name);
        go.transform.SetParent(parent, false);
        go.transform.SetPositionAndRotation(at - Vector3.up * m.Bottom * k, Quaternion.Euler(0, rot + m.Front, 0));
        go.transform.localScale = Vector3.one * k;
        go.AddComponent<MeshFilter>().sharedMesh = m.Mesh;
        var r = go.AddComponent<MeshRenderer>(); r.sharedMaterial = m.Material;
        var lod = go.AddComponent<LODGroup>();
        lod.SetLODs(new[] { new LOD(cull, new Renderer[] { r }) });
        lod.RecalculateBounds();
    }

    /// <summary>
    /// A tree of the model, ~8.5 m × scale tall, and far away the simple shape (a trunk and a ball) by a LOD group:
    /// thousands of trees share the model's mesh, the phone draws the model only near the camera.
    /// </summary>
    static void Tree(TzModels.Model m, Vector3 at, float rot, float scale, Transform parent, Material bark, Material leaves)
    {
        if (farTree == null)
        {
            var trunk = new Builder(); trunk.Add(cylinder, TRS(Vector3.up * 1.2f, 0, new Vector3(0.45f, 1.2f, 0.45f)));
            var crown = new Builder(); crown.Add(sphere, TRS(Vector3.up * 3.3f, 0, new Vector3(3.2f, 3f, 3.2f)));
            farTree = new Mesh { name = "tree-far" };
            farTree.CombineMeshes(new[] { new CombineInstance { mesh = trunk.Make("t"), transform = Matrix4x4.identity }, new CombineInstance { mesh = crown.Make("c"), transform = Matrix4x4.identity } }, false, true);
            AssetDatabase.CreateAsset(farTree, $"{Dir}/Meshes/tree-far.asset");
        }
        float k = 8.5f * scale / Mathf.Max(0.01f, m.Size.y);
        var go = new GameObject("Tree");
        go.transform.SetParent(parent, false);
        go.transform.SetPositionAndRotation(at, Quaternion.Euler(0, rot, 0));
        var near = new GameObject("Near");
        near.transform.SetParent(go.transform, false);
        near.transform.localPosition = -Vector3.up * m.Bottom * k;
        near.transform.localRotation = Quaternion.Euler(0, m.Front, 0);
        near.transform.localScale = Vector3.one * k;
        near.AddComponent<MeshFilter>().sharedMesh = m.Mesh;
        var nr = near.AddComponent<MeshRenderer>(); nr.sharedMaterial = m.Material;
        var far = new GameObject("Far");
        far.transform.SetParent(go.transform, false);
        far.transform.localScale = Vector3.one * (8.5f * scale / 4.8f);
        far.AddComponent<MeshFilter>().sharedMesh = farTree;
        var fr = far.AddComponent<MeshRenderer>(); fr.sharedMaterials = new[] { bark, leaves };
        var lod = go.AddComponent<LODGroup>();
        lod.SetLODs(new[] { new LOD(0.12f, new Renderer[] { nr }), new LOD(0.015f, new Renderer[] { fr }) });
        lod.RecalculateBounds();
    }

    /// <summary>Invisible walls: over the water (rows of water cells as long boxes) and around the map.</summary>
    static void Walls(TzWorld w, Transform root)
    {
        var batch = new Batch();
        float c = (float)TzWorld.Cell;
        int ni = w.Ground.GetLength(0), nj = w.Ground.GetLength(1);
        for (int j = 0; j < nj; j++)
            for (int i = 0; i < ni; i++)
            {
                if (w.Ground[i, j] != TzWorld.Surface.Water) continue;
                int i0 = i;
                while (i + 1 < ni && w.Ground[i + 1, j] == TzWorld.Surface.Water) i++;
                float x0 = (float)w.MinX + i0 * c, x1 = (float)w.MinX + (i + 1) * c, z = (float)w.MinZ + (j + 0.5f) * c;
                var centre = new Vector3((x0 + x1) / 2, 0.5f, z);
                batch.For(null, centre).Add(cube, TRS(centre, 0, new Vector3(x1 - x0, 3f, c)));
            }
        var go = new GameObject("Walls");
        go.transform.SetParent(root, false);
        batch.Emit(go.transform, "water", true, WaterLayer);
        go.layer = WaterLayer;
        float minX = (float)w.MinX, maxX = (float)w.MaxX, minZ = (float)w.MinZ, maxZ = (float)w.MaxZ;
        void Edge(Vector3 centre, Vector3 size)
        {
            var e = go.AddComponent<BoxCollider>();
            e.center = centre;
            e.size = size;
        }
        Edge(new Vector3((minX + maxX) / 2, 5, minZ - 1), new Vector3(maxX - minX + 4, 12, 2));
        Edge(new Vector3((minX + maxX) / 2, 5, maxZ + 1), new Vector3(maxX - minX + 4, 12, 2));
        Edge(new Vector3(minX - 1, 5, (minZ + maxZ) / 2), new Vector3(2, 12, maxZ - minZ + 4));
        Edge(new Vector3(maxX + 1, 5, (minZ + maxZ) / 2), new Vector3(2, 12, maxZ - minZ + 4));
    }
}
