using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Tz.Rules;
using UnityEditor;
using UnityEngine;
using Object = UnityEngine.Object;

/// <summary>
/// Monsters with a rigged model (content/art/models/models.json entries with "rig": &lt;name&gt;.glb is the skinned
/// creature, &lt;name&gt;@walk.glb its walk from Tripo). The demo monster's capsule stays as the invisible
/// "CapsuleModel" the demo's clips move (idle sway, the blow's lunge, the flinch, the fall on death): our model
/// hangs under it as "Body", so the creature does all that too, and its own walk clip replaces the demo's.
/// glTF → Unity: x mirrored (positions, rotations (x, −y, −z, w), matrices S·M·S), triangles turned round.
/// </summary>
public static class TzCreatures
{
    const string Dir = "Assets/Tz/Generated/Creatures";

    class Def { public string Name, File, Walk; public float Front, Size; }
    static Dictionary<string, Def> byMonster = new Dictionary<string, Def>();

    /// <summary>Reads which monsters get which creature.</summary>
    public static void Load(string repoRoot)
    {
        byMonster.Clear();
        string folder = Path.Combine(repoRoot, "content", "art", "models");
        string index = Path.Combine(folder, "models.json");
        AssetDatabase.DeleteAsset(Dir);
        Directory.CreateDirectory(Dir);
        if (!File.Exists(index)) return;
        foreach (var kv in (Dictionary<string, object>)TzJson.Parse(File.ReadAllText(index)))
        {
            if (!(kv.Value is Dictionary<string, object> o) || !o.ContainsKey("rig")) continue;
            var d = new Def
            {
                Name = kv.Key, File = Path.Combine(folder, kv.Key + ".glb"),
                Front = o.TryGetValue("front", out var f) && f is double fd ? (float)fd : 0f,
                Size = o.TryGetValue("size", out var s) && s is double sd ? (float)sd : 1.5f,
            };
            string walk = Path.Combine(folder, kv.Key + "@walk.glb");
            if (File.Exists(walk)) d.Walk = walk;
            if (!File.Exists(d.File)) continue;
            if (o.TryGetValue("for", out var u) && u is List<object> ul)
                foreach (var m in ul.OfType<string>()) byMonster[m] = d;
        }
    }

    /// <summary>Puts the creature of [monsterId] (if any) into the opened monster prefab [root]. Returns whether it did.</summary>
    public static bool Dress(GameObject root, string monsterId)
    {
        if (!byMonster.TryGetValue(monsterId, out var def)) return false;
        var capsule = root.GetComponentsInChildren<Transform>(true).FirstOrDefault(t => t.name == "CapsuleModel");
        var model = FindModel(root);
        if (capsule == null || model == null) { Debug.LogError($"[TzImport] creature {def.Name}: no CapsuleModel or character model in {root.name}"); return false; }
        foreach (var r in capsule.GetComponents<Renderer>()) Object.DestroyImmediate(r);
        foreach (var mf in capsule.GetComponents<MeshFilter>()) Object.DestroyImmediate(mf);
        // The capsule's "face" (a flat cylinder in front) goes with it.
        for (int i = capsule.childCount - 1; i >= 0; i--) Object.DestroyImmediate(capsule.GetChild(i).gameObject);

        var g = new Gltf(def.File);
        var body = new GameObject("Body").transform;
        body.SetParent(capsule, false);
        body.localPosition = -capsule.localPosition;   // feet on the ground: the capsule's centre is above it
        body.localRotation = Quaternion.Euler(0, def.Front, 0);
        var nodes = g.Build(body, def.Name, Dir, out var smr);
        // Size: the longest horizontal side of the posed mesh (in the body's space) becomes def.Size metres.
        var baked = new Mesh();
        smr.BakeMesh(baked);
        var toBody = body.worldToLocalMatrix * smr.transform.localToWorldMatrix;
        var bv = baked.vertices.Select(v => toBody.MultiplyPoint3x4(v)).ToArray();
        Object.DestroyImmediate(baked);
        var min = new Vector3(bv.Min(v => v.x), bv.Min(v => v.y), bv.Min(v => v.z));
        var max = new Vector3(bv.Max(v => v.x), bv.Max(v => v.y), bv.Max(v => v.z));
        float k = def.Size / Mathf.Max(0.01f, Mathf.Max(max.x - min.x, max.z - min.z));
        body.localScale = Vector3.one * k;
        body.localPosition = -capsule.localPosition - Vector3.up * min.y * k;
        smr.localBounds = new Bounds((min + max) / 2, (max - min) * 1.5f);

        var so = new SerializedObject(model);
        var smrProp = so.FindProperty("skinnedMeshRenderer");
        if (smrProp != null) smrProp.objectReferenceValue = smr;
        if (def.Walk != null)
        {
            var byName = new Dictionary<string, Transform>();
            foreach (var t in nodes) if (t != null && !byName.ContainsKey(t.name)) byName[t.name] = t;
            var clip = new Gltf(def.Walk).Clip(root.transform, byName, def.Name + "-walk", Dir);
            var fwd = so.FindProperty("defaultAnimations.moveStates.forwardState.clip");
            if (clip != null && fwd != null) fwd.objectReferenceValue = clip;
        }
        so.ApplyModifiedPropertiesWithoutUndo();
        Debug.Log($"[TzImport] creature {def.Name} on {monsterId}: {smr.sharedMesh.triangles.Length / 3} triangles, {smr.bones.Length} bones, ×{k:0.00}, walk {(def.Walk != null ? "yes" : "no")}");
        return true;
    }

    // The framework's character model component (PlayableCharacterModel) without naming its namespace here.
    static Component FindModel(GameObject root) =>
        root.GetComponents<Component>().FirstOrDefault(c => c != null && c.GetType().Name == "PlayableCharacterModel");

    /// <summary>A small reader of the glTF parts we need: nodes, one skinned mesh, its texture, animations.</summary>
    class Gltf
    {
        readonly byte[] b;
        readonly Dictionary<string, object> j;
        readonly int bin;
        public Gltf(string path)
        {
            b = File.ReadAllBytes(path);
            int len = BitConverter.ToInt32(b, 12);
            j = (Dictionary<string, object>)TzJson.Parse(System.Text.Encoding.UTF8.GetString(b, 20, len));
            bin = 20 + len + 8;
        }

        static int I(object o) => (int)(double)o;
        static Dictionary<string, object> D(object o) => (Dictionary<string, object>)o;
        List<object> L(string k) => j.TryGetValue(k, out var v) ? (List<object>)v : new List<object>();

        float[] Acc(int i, out int comps)
        {
            var a = D(L("accessors")[i]);
            var v = D(L("bufferViews")[I(a["bufferView"])]);
            int off = bin + (v.TryGetValue("byteOffset", out var vo) ? I(vo) : 0) + (a.TryGetValue("byteOffset", out var ao) ? I(ao) : 0);
            string type = (string)a["type"];
            comps = type == "SCALAR" ? 1 : type == "VEC2" ? 2 : type == "VEC3" ? 3 : type == "VEC4" ? 4 : 16;
            int count = I(a["count"]), ct = I(a["componentType"]);
            bool norm = a.TryGetValue("normalized", out var nz) && nz is bool nb && nb;
            int size = ct == 5126 || ct == 5125 ? 4 : ct == 5123 || ct == 5122 ? 2 : 1;
            int stride = v.TryGetValue("byteStride", out var st) ? I(st) : comps * size;
            var r = new float[count * comps];
            for (int e = 0; e < count; e++)
                for (int c = 0; c < comps; c++)
                {
                    int p = off + e * stride + c * size;
                    float x = ct == 5126 ? BitConverter.ToSingle(b, p) : ct == 5125 ? BitConverter.ToUInt32(b, p)
                        : ct == 5123 ? BitConverter.ToUInt16(b, p) : ct == 5122 ? BitConverter.ToInt16(b, p) : ct == 5121 ? b[p] : (sbyte)b[p];
                    if (norm) x /= ct == 5123 ? 65535f : ct == 5121 ? 255f : 1f;
                    r[e * comps + c] = x;
                }
            return r;
        }

        static Vector3 P(float[] a, int i) => new Vector3(-a[i * 3], a[i * 3 + 1], a[i * 3 + 2]);
        static Quaternion Q(float[] a, int i) => new Quaternion(a[i * 4], -a[i * 4 + 1], -a[i * 4 + 2], a[i * 4 + 3]);

        /// <summary>Builds the scene's nodes under [parent]; returns them by glTF index, the skinned renderer out.</summary>
        public Transform[] Build(Transform parent, string name, string dir, out SkinnedMeshRenderer smr)
        {
            var nodes = L("nodes");
            var t = new Transform[nodes.Count];
            var used = new HashSet<string>();
            void Make(int i, Transform under)
            {
                var n = D(nodes[i]);
                string nm = n.TryGetValue("name", out var nn) && nn is string ns && ns.Length > 0 ? ns : "node" + i;
                if (!used.Add(under.GetInstanceID() + "/" + nm)) nm += "_" + i;
                var go = new GameObject(nm).transform;
                go.SetParent(under, false);
                if (n.TryGetValue("translation", out var tr)) { var a = ((List<object>)tr).Select(x => (float)(double)x).ToArray(); go.localPosition = P(a, 0); }
                if (n.TryGetValue("rotation", out var ro)) { var a = ((List<object>)ro).Select(x => (float)(double)x).ToArray(); go.localRotation = Q(a, 0); }
                if (n.TryGetValue("scale", out var sc)) { var a = ((List<object>)sc).Select(x => (float)(double)x).ToArray(); go.localScale = new Vector3(a[0], a[1], a[2]); }
                if (n.TryGetValue("matrix", out var mx))
                {
                    var a = ((List<object>)mx).Select(x => (float)(double)x).ToArray();
                    var m = Mirror(new Matrix4x4(new Vector4(a[0], a[1], a[2], a[3]), new Vector4(a[4], a[5], a[6], a[7]), new Vector4(a[8], a[9], a[10], a[11]), new Vector4(a[12], a[13], a[14], a[15])));
                    go.localPosition = m.GetColumn(3); go.localRotation = m.rotation; go.localScale = m.lossyScale;
                }
                t[i] = go;
                if (n.TryGetValue("children", out var ch))
                    foreach (var c in (List<object>)ch) Make(I(c), go);
            }
            var scene = D(L("scenes")[j.TryGetValue("scene", out var sc0) ? I(sc0) : 0]);
            foreach (var r in (List<object>)scene["nodes"]) Make(I(r), parent);

            smr = null;
            for (int i = 0; i < nodes.Count; i++)
            {
                var n = D(nodes[i]);
                if (!n.ContainsKey("mesh") || !n.ContainsKey("skin")) continue;
                var prim = D(((List<object>)D(L("meshes")[I(n["mesh"])])["primitives"])[0]);
                var at = D(prim["attributes"]);
                var pos = Acc(I(at["POSITION"]), out _);
                var nor = at.ContainsKey("NORMAL") ? Acc(I(at["NORMAL"]), out _) : null;
                var uv = at.ContainsKey("TEXCOORD_0") ? Acc(I(at["TEXCOORD_0"]), out _) : null;
                var jo = Acc(I(at["JOINTS_0"]), out _);
                var we = Acc(I(at["WEIGHTS_0"]), out _);
                var idx = Acc(I(prim["indices"]), out _);
                int vc = pos.Length / 3;
                var mesh = new Mesh { name = name, indexFormat = vc > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16 };
                var verts = new Vector3[vc]; var norms = new Vector3[vc]; var uvs = new Vector2[vc]; var bw = new BoneWeight[vc];
                for (int v = 0; v < vc; v++)
                {
                    verts[v] = P(pos, v);
                    if (nor != null) norms[v] = P(nor, v);
                    if (uv != null) uvs[v] = new Vector2(uv[v * 2], 1 - uv[v * 2 + 1]);
                    float s = we[v * 4] + we[v * 4 + 1] + we[v * 4 + 2] + we[v * 4 + 3];
                    if (s <= 0) s = 1;
                    bw[v] = new BoneWeight
                    {
                        boneIndex0 = (int)jo[v * 4], weight0 = we[v * 4] / s, boneIndex1 = (int)jo[v * 4 + 1], weight1 = we[v * 4 + 1] / s,
                        boneIndex2 = (int)jo[v * 4 + 2], weight2 = we[v * 4 + 2] / s, boneIndex3 = (int)jo[v * 4 + 3], weight3 = we[v * 4 + 3] / s,
                    };
                }
                var tris = idx.Select(x => (int)x).ToArray();
                for (int k = 0; k + 2 < tris.Length; k += 3) { int x = tris[k + 1]; tris[k + 1] = tris[k + 2]; tris[k + 2] = x; }
                mesh.vertices = verts; mesh.uv = uvs; mesh.triangles = tris; mesh.boneWeights = bw;
                if (nor != null) mesh.normals = norms; else mesh.RecalculateNormals();
                var skin = D(L("skins")[I(n["skin"])]);
                var joints = ((List<object>)skin["joints"]).Select(I).ToArray();
                var ibm = skin.ContainsKey("inverseBindMatrices") ? Acc(I(skin["inverseBindMatrices"]), out _) : null;
                var binds = new Matrix4x4[joints.Length];
                for (int k = 0; k < joints.Length; k++)
                {
                    if (ibm == null) { binds[k] = Matrix4x4.identity; continue; }
                    var a = new float[16]; Array.Copy(ibm, k * 16, a, 0, 16);
                    binds[k] = Mirror(new Matrix4x4(new Vector4(a[0], a[1], a[2], a[3]), new Vector4(a[4], a[5], a[6], a[7]), new Vector4(a[8], a[9], a[10], a[11]), new Vector4(a[12], a[13], a[14], a[15])));
                }
                mesh.bindposes = binds;
                mesh.RecalculateBounds();
                AssetDatabase.CreateAsset(mesh, $"{dir}/{name}.asset");
                smr = t[i].gameObject.AddComponent<SkinnedMeshRenderer>();
                smr.sharedMesh = mesh;
                smr.bones = joints.Select(x => t[x]).ToArray();
                smr.rootBone = t[joints[0]];
                smr.sharedMaterial = Material(prim, name, dir);
                smr.updateWhenOffscreen = false;
                break;
            }
            if (smr == null) throw new Exception("no skinned mesh in " + name);
            return t;
        }

        /// <summary>The glTF matrix in Unity's mirrored space: S·M·S, S = diag(−1, 1, 1).</summary>
        static Matrix4x4 Mirror(Matrix4x4 m)
        {
            var s = Matrix4x4.Scale(new Vector3(-1, 1, 1));
            return s * m * s;
        }

        Material Material(Dictionary<string, object> prim, string name, string dir)
        {
            var mat = new Material(Shader.Find("Standard")) { name = name };
            mat.SetFloat("_Glossiness", 0.15f);
            if (!prim.TryGetValue("material", out var mi)) { AssetDatabase.CreateAsset(mat, $"{dir}/{name}.mat"); return mat; }
            var md = D(L("materials")[I(mi)]);
            Texture2D Tex(int texIndex, string suffix, bool normal)
            {
                var src = D(L("images")[I(D(L("textures")[texIndex])["source"])]);
                var v = D(L("bufferViews")[I(src["bufferView"])]);
                int off = bin + (v.TryGetValue("byteOffset", out var vo) ? I(vo) : 0), len = I(v["byteLength"]);
                string ext = src.TryGetValue("mimeType", out var mt) && (string)mt == "image/png" ? "png" : "jpg";
                string tp = $"{dir}/{name}-{suffix}.{ext}";
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
            if (md.TryGetValue("pbrMetallicRoughness", out var pbr) && D(pbr).TryGetValue("baseColorTexture", out var bct))
                mat.mainTexture = Tex(I(D(bct)["index"]), "color", false);
            if (md.TryGetValue("normalTexture", out var nt)) { mat.SetTexture("_BumpMap", Tex(I(D(nt)["index"]), "normal", true)); mat.EnableKeyword("_NORMALMAP"); }
            AssetDatabase.CreateAsset(mat, $"{dir}/{name}.mat");
            return mat;
        }

        /// <summary>
        /// The first animation as a clip on the skeleton built from the rigged model (bones match by name),
        /// paths from [root] (the animator's object). Looped.
        /// </summary>
        public AnimationClip Clip(Transform root, Dictionary<string, Transform> byName, string name, string dir)
        {
            var anims = L("animations");
            if (anims.Count == 0) return null;
            var a = D(anims[0]);
            var samplers = (List<object>)a["samplers"];
            var clip = new AnimationClip { name = name, frameRate = 30, legacy = false };
            foreach (var chObj in (List<object>)a["channels"])
            {
                var ch = D(chObj);
                var target = D(ch["target"]);
                if (!target.ContainsKey("node")) continue;
                int node = I(target["node"]);
                var nd = D(L("nodes")[node]);
                string nodeName = nd.TryGetValue("name", out var nn) && nn is string ns && ns.Length > 0 ? ns : "node" + node;
                if (!byName.TryGetValue(nodeName, out var bone)) continue;
                string pathName = (string)target["path"];
                var smp = D(samplers[I(ch["sampler"])]);
                var times = Acc(I(smp["input"]), out _);
                var vals = Acc(I(smp["output"]), out int comps);
                bool cubic = smp.TryGetValue("interpolation", out var ip) && (string)ip == "CUBICSPLINE";
                string path = AnimationUtility.CalculateTransformPath(bone, root);
                int n = times.Length;
                float V(int key, int c) => cubic ? vals[(key * 3 + 1) * comps + c] : vals[key * comps + c];
                AnimationCurve[] curves;
                string[] props;
                if (pathName == "translation") { props = new[] { "localPosition.x", "localPosition.y", "localPosition.z" }; curves = new AnimationCurve[3]; }
                else if (pathName == "rotation") { props = new[] { "localRotation.x", "localRotation.y", "localRotation.z", "localRotation.w" }; curves = new AnimationCurve[4]; }
                else if (pathName == "scale") { props = new[] { "localScale.x", "localScale.y", "localScale.z" }; curves = new AnimationCurve[3]; }
                else continue;
                for (int c = 0; c < curves.Length; c++) curves[c] = new AnimationCurve();
                for (int key = 0; key < n; key++)
                {
                    if (pathName == "translation") { var p = new Vector3(-V(key, 0), V(key, 1), V(key, 2)); for (int c = 0; c < 3; c++) curves[c].AddKey(new Keyframe(times[key], p[c])); }
                    else if (pathName == "rotation") { var q = new Quaternion(V(key, 0), -V(key, 1), -V(key, 2), V(key, 3)); for (int c = 0; c < 4; c++) curves[c].AddKey(new Keyframe(times[key], q[c])); }
                    else for (int c = 0; c < 3; c++) curves[c].AddKey(new Keyframe(times[key], V(key, c)));
                }
                for (int c = 0; c < curves.Length; c++)
                {
                    for (int key = 0; key < curves[c].length; key++)
                    {
                        AnimationUtility.SetKeyLeftTangentMode(curves[c], key, AnimationUtility.TangentMode.Linear);
                        AnimationUtility.SetKeyRightTangentMode(curves[c], key, AnimationUtility.TangentMode.Linear);
                    }
                    clip.SetCurve(path, typeof(Transform), props[c], curves[c]);
                }
            }
            clip.EnsureQuaternionContinuity();
            var settings = AnimationUtility.GetAnimationClipSettings(clip);
            settings.loopTime = true;
            AnimationUtility.SetAnimationClipSettings(clip, settings);
            AssetDatabase.CreateAsset(clip, $"{dir}/{name}.anim");
            return clip;
        }
    }
}
