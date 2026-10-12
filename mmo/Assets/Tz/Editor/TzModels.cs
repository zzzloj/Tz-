using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Tz.Rules;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Our 3D models: content/art/models/&lt;name&gt;.glb (made light by tools/models/prepare.py) and
/// content/art/models/models.json — which shapes of the map each replaces and which way its front looks.
/// A small glTF reader of our own (one mesh, one material: colour and normal textures) instead of a package:
/// the mesh, the textures and a Standard material become assets in Assets/Tz/Generated/Models.
/// glTF is right-handed with the image's top-left as uv (0, 0); Unity is left-handed with the bottom-left:
/// x is mirrored, triangles turned round, v flipped.
/// </summary>
public static class TzModels
{
    const string Dir = "Assets/Tz/Generated/Models";

    public class Model
    {
        public string Name;
        public Mesh Mesh;
        public Material Material;
        /// <summary>Size of the mesh (Unity axes, model units) and the lowest point.</summary>
        public Vector3 Size;
        public float Bottom;
        /// <summary>Degrees around Y that turn the model's front to +Z.</summary>
        public float Front;
        public string[] For = new string[0];
    }

    /// <summary>The models by what they replace (a building kind or a prop kind of TzWorld); several for one kind take turns.</summary>
    public static Dictionary<string, List<Model>> Load(string repoRoot)
    {
        var byUse = new Dictionary<string, List<Model>>();
        string folder = Path.Combine(repoRoot, "content", "art", "models");
        string index = Path.Combine(folder, "models.json");
        if (!File.Exists(index)) return byUse;
        AssetDatabase.DeleteAsset(Dir);
        Directory.CreateDirectory(Dir);
        var list = TzJson.Parse(File.ReadAllText(index)) as Dictionary<string, object>;
        foreach (var kv in list)
        {
            if (kv.Key.StartsWith("_") || !(kv.Value is Dictionary<string, object> o)) continue;
            // Rigged creatures (monsters) come later: they need the skin and the animations.
            if (o.ContainsKey("rig")) { Debug.Log($"[TzImport] model {kv.Key}: rigged, not used yet"); continue; }
            string file = Path.Combine(folder, kv.Key + ".glb");
            if (!File.Exists(file)) { Debug.LogWarning("[TzImport] models: no " + file); continue; }
            try
            {
                var m = Read(file, kv.Key);
                m.Front = o.TryGetValue("front", out var f) && f is double fd ? (float)fd : 0f;
                m.For = o.TryGetValue("for", out var u) && u is List<object> ul ? ul.OfType<string>().ToArray() : new string[0];
                foreach (var use in m.For) { if (!byUse.TryGetValue(use, out var l)) byUse[use] = l = new List<Model>(); l.Add(m); }
                Debug.Log($"[TzImport] model {kv.Key}: {m.Mesh.triangles.Length / 3} triangles, size {m.Size}, for {string.Join(", ", m.For)}");
            }
            catch (Exception e) { Debug.LogError($"[TzImport] model {kv.Key}: {e}"); }
        }
        return byUse;
    }

    static Model Read(string path, string name)
    {
        var b = File.ReadAllBytes(path);
        if (BitConverter.ToUInt32(b, 0) != 0x46546C67) throw new Exception("not a .glb");
        int jsonLen = BitConverter.ToInt32(b, 12);
        var j = (Dictionary<string, object>)TzJson.Parse(System.Text.Encoding.UTF8.GetString(b, 20, jsonLen));
        int binStart = 20 + jsonLen + 8;
        var accessors = (List<object>)j["accessors"];
        var views = (List<object>)j["bufferViews"];
        int I(object o) => (int)(double)o;
        Dictionary<string, object> D(object o) => (Dictionary<string, object>)o;

        (int offset, int count, int comps, int type) Acc(int i)
        {
            var a = D(accessors[i]);
            var v = D(views[I(a["bufferView"])]);
            int off = binStart + (v.TryGetValue("byteOffset", out var vo) ? I(vo) : 0) + (a.TryGetValue("byteOffset", out var ao) ? I(ao) : 0);
            int comps = (string)a["type"] == "VEC3" ? 3 : (string)a["type"] == "VEC2" ? 2 : (string)a["type"] == "VEC4" ? 4 : 1;
            return (off, I(a["count"]), comps, I(a["componentType"]));
        }
        float[] Floats(int i) { var a = Acc(i); var r = new float[a.count * a.comps]; Buffer.BlockCopy(b, a.offset, r, 0, r.Length * 4); return r; }
        int[] Ints(int i)
        {
            var a = Acc(i); var r = new int[a.count];
            for (int k = 0; k < a.count; k++)
                r[k] = a.type == 5125 ? (int)BitConverter.ToUInt32(b, a.offset + k * 4) : a.type == 5123 ? BitConverter.ToUInt16(b, a.offset + k * 2) : b[a.offset + k];
            return r;
        }

        var prim = D(((List<object>)D(((List<object>)j["meshes"])[0])["primitives"])[0]);
        var attrs = D(prim["attributes"]);
        var p = Floats(I(attrs["POSITION"]));
        var n = attrs.ContainsKey("NORMAL") ? Floats(I(attrs["NORMAL"])) : null;
        var uv = attrs.ContainsKey("TEXCOORD_0") ? Floats(I(attrs["TEXCOORD_0"])) : null;
        var idx = Ints(I(prim["indices"]));
        int vc = p.Length / 3;
        var verts = new Vector3[vc]; var norms = new Vector3[vc]; var uvs = new Vector2[vc];
        for (int k = 0; k < vc; k++)
        {
            verts[k] = new Vector3(-p[k * 3], p[k * 3 + 1], p[k * 3 + 2]);
            if (n != null) norms[k] = new Vector3(-n[k * 3], n[k * 3 + 1], n[k * 3 + 2]);
            if (uv != null) uvs[k] = new Vector2(uv[k * 2], 1 - uv[k * 2 + 1]);
        }
        for (int k = 0; k + 2 < idx.Length; k += 3) { int t = idx[k + 1]; idx[k + 1] = idx[k + 2]; idx[k + 2] = t; }
        var mesh = new Mesh { name = name, indexFormat = vc > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16 };
        mesh.vertices = verts; mesh.uv = uvs; mesh.triangles = idx;
        if (n != null) mesh.normals = norms; else mesh.RecalculateNormals();
        mesh.RecalculateTangents();
        mesh.RecalculateBounds();
        AssetDatabase.CreateAsset(mesh, $"{Dir}/{name}.asset");

        // Textures: the image bytes as they are, imported by Unity.
        Texture2D Texture(int texIndex, string suffix, bool normal)
        {
            var src = D(((List<object>)j["images"])[I(D(((List<object>)j["textures"])[texIndex])["source"])]);
            var v = D(views[I(src["bufferView"])]);
            int off = binStart + (v.TryGetValue("byteOffset", out var vo) ? I(vo) : 0), len = I(v["byteLength"]);
            string ext = src.TryGetValue("mimeType", out var mt) && (string)mt == "image/png" ? "png" : "jpg";
            string tp = $"{Dir}/{name}-{suffix}.{ext}";
            var bytes = new byte[len];
            Buffer.BlockCopy(b, off, bytes, 0, len);
            File.WriteAllBytes(tp, bytes);
            AssetDatabase.ImportAsset(tp);
            var imp = (TextureImporter)AssetImporter.GetAtPath(tp);
            imp.textureType = normal ? TextureImporterType.NormalMap : TextureImporterType.Default;
            imp.maxTextureSize = normal ? 512 : 1024;
            imp.SaveAndReimport();
            return AssetDatabase.LoadAssetAtPath<Texture2D>(tp);
        }
        var mat = new Material(Shader.Find("Standard")) { name = name, enableInstancing = true };
        mat.SetFloat("_Glossiness", 0.12f);
        var matDef = D(((List<object>)j["materials"])[prim.TryGetValue("material", out var mi) ? I(mi) : 0]);
        if (matDef.TryGetValue("pbrMetallicRoughness", out var pbr) && D(pbr).TryGetValue("baseColorTexture", out var bct))
            mat.mainTexture = Texture(I(D(bct)["index"]), "color", false);
        if (matDef.TryGetValue("normalTexture", out var nt))
        {
            mat.SetTexture("_BumpMap", Texture(I(D(nt)["index"]), "normal", true));
            mat.EnableKeyword("_NORMALMAP");
        }
        AssetDatabase.CreateAsset(mat, $"{Dir}/{name}.mat");
        return new Model { Name = name, Mesh = mesh, Material = mat, Size = mesh.bounds.size, Bottom = mesh.bounds.min.y };
    }
}
