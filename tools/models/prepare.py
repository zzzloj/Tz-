"""Makes a model from a generator (Tripo, Meshy: a .glb with 4K textures and ~10k triangles) light enough
for the phone and the repository, and puts it into content/art/models/<name>.glb, which the MMO's
build turns into the map's buildings, trees and creatures (mmo/Assets/Tz/Editor/TzModels.cs).

    python3 tools/models/prepare.py <source.glb> <name> [triangles]

- the mesh is simplified to [triangles] (default by the name in TARGETS) keeping its texture coordinates
  (pymeshlab: quadric edge collapse with texture);
- the colour texture becomes 1024 px JPEG, the normal map 512 px PNG; the metal/roughness map goes;
- a skin and animations (a rigged creature) are kept as they are: such a model is not simplified.
Needs: pip install pymeshlab pillow numpy (and libopengl0 on Linux)."""
import io, json, os, struct, sys
import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TARGETS = {"tree": 4500, "tree-dead": 3000, "bush": 1200, "rock": 800}
DEFAULT = 5000


def read_glb(path):
    b = open(path, "rb").read()
    n = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + n])
    bin_ = b[20 + n + 8:]
    return j, bin_


def accessor(j, bin_, i):
    a = j["accessors"][i]
    bv = j["bufferViews"][a["bufferView"]]
    comps = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
    dt = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}[a["componentType"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    arr = np.frombuffer(bin_, dtype=dt, count=a["count"] * comps, offset=off)
    return arr.reshape(a["count"], comps) if comps > 1 else arr


def image(j, bin_, tex_index):
    src = j["images"][j["textures"][tex_index]["source"]]
    bv = j["bufferViews"][src["bufferView"]]
    o = bv.get("byteOffset", 0)
    return Image.open(io.BytesIO(bin_[o:o + bv["byteLength"]]))


def simplify(P, N, UV, I, target):
    import pymeshlab
    if len(I) <= target:
        return P, N, UV, I
    ms = pymeshlab.MeshSet()
    m = pymeshlab.Mesh(vertex_matrix=P.astype(np.float64), face_matrix=I.astype(np.int32),
                       v_tex_coords_matrix=UV.astype(np.float64))
    ms.add_mesh(m)
    ms.compute_texcoord_transfer_vertex_to_wedge()
    ms.meshing_decimation_quadric_edge_collapse_with_texture(targetfacenum=int(target), preserveboundary=False, qualitythr=0.1,
                                                              optimalplacement=True, preservenormal=True)
    ms.compute_normal_per_vertex()
    m = ms.current_mesh()
    V = m.vertex_matrix(); F = m.face_matrix(); W = m.wedge_tex_coord_matrix().reshape(-1, 3, 2); VN = m.vertex_normal_matrix()
    # Split vertices by their texture coordinates (glTF keeps them per vertex).
    keys = {}
    P2, N2, UV2, I2 = [], [], [], []
    for f in range(len(F)):
        tri = []
        for k in range(3):
            v = int(F[f, k]); uv = (round(float(W[f, k, 0]), 6), round(float(W[f, k, 1]), 6))
            key = (v, uv)
            if key not in keys:
                keys[key] = len(P2)
                P2.append(V[v]); N2.append(VN[v]); UV2.append(uv)
            tri.append(keys[key])
        I2.append(tri)
    return (np.array(P2, np.float32), np.array(N2, np.float32), np.array(UV2, np.float32), np.array(I2, np.uint32))


def jpeg(img, size):
    out = io.BytesIO()
    img.convert("RGB").resize((size, size), Image.LANCZOS).save(out, "JPEG", quality=88)
    return out.getvalue()


def png(img, size):
    out = io.BytesIO()
    img.convert("RGB").resize((size, size), Image.LANCZOS).save(out, "PNG", optimize=True)
    return out.getvalue()


def write_glb(path, P, N, UV, I, color, normal):
    chunks, views, accs = [], [], []
    def add(data, target=None):
        while sum(len(c) for c in chunks) % 4:
            chunks.append(b"\0")
        off = sum(len(c) for c in chunks)
        chunks.append(data)
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target: v["target"] = target
        views.append(v)
        return len(views) - 1
    def acc(arr, typ, ctype, target):
        view = add(arr.tobytes(), target)
        a = {"bufferView": view, "componentType": ctype, "count": len(arr), "type": typ}
        if typ == "VEC3" and ctype == 5126:
            a["min"] = arr.min(0).tolist(); a["max"] = arr.max(0).tolist()
        accs.append(a)
        return len(accs) - 1
    ip = acc(P, "VEC3", 5126, 34962); inn = acc(N, "VEC3", 5126, 34962); iuv = acc(UV, "VEC2", 5126, 34962)
    ii = acc(I.reshape(-1).astype(np.uint32), "SCALAR", 5125, 34963)
    images = [{"bufferView": add(color), "mimeType": "image/jpeg"}]
    mat = {"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 0.85}}
    if normal is not None:
        images.append({"bufferView": add(normal), "mimeType": "image/png"})
        mat["normalTexture"] = {"index": 1}
    binary = b"".join(chunks)
    binary += b"\0" * ((4 - len(binary) % 4) % 4)
    j = {"asset": {"version": "2.0", "generator": "tools/models/prepare.py"}, "scene": 0, "scenes": [{"nodes": [0]}],
         "nodes": [{"mesh": 0}], "meshes": [{"primitives": [{"attributes": {"POSITION": ip, "NORMAL": inn, "TEXCOORD_0": iuv}, "indices": ii, "material": 0}]}],
         "materials": [mat], "textures": [{"source": k} for k in range(len(images))], "images": images,
         "accessors": accs, "bufferViews": views, "buffers": [{"byteLength": len(binary)}]}
    js = json.dumps(j, separators=(",", ":")).encode()
    js += b" " * ((4 - len(js) % 4) % 4)
    out = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(binary))
    out += struct.pack("<II", len(js), 0x4E4F534A) + js + struct.pack("<II", len(binary), 0x004E4942) + binary
    open(path, "wb").write(out)


def main():
    src, name = sys.argv[1], sys.argv[2]
    target = int(sys.argv[3]) if len(sys.argv) > 3 else TARGETS.get(name, DEFAULT)
    j, bin_ = read_glb(src)
    if j.get("skins") or j.get("animations"):
        sys.exit("a rigged model: not handled yet (keep it as it is)")
    prim = j["meshes"][0]["primitives"][0]
    P = accessor(j, bin_, prim["attributes"]["POSITION"]).astype(np.float32)
    N = accessor(j, bin_, prim["attributes"]["NORMAL"]).astype(np.float32)
    UV = accessor(j, bin_, prim["attributes"]["TEXCOORD_0"]).astype(np.float32)
    I = accessor(j, bin_, prim["indices"]).reshape(-1, 3)
    mat = j["materials"][prim.get("material", 0)]
    color = image(j, bin_, mat["pbrMetallicRoughness"]["baseColorTexture"]["index"])
    normal = image(j, bin_, mat["normalTexture"]["index"]) if "normalTexture" in mat else None
    P2, N2, UV2, I2 = simplify(P, N, UV, I, target)
    out = os.path.join(ROOT, "content", "art", "models", name + ".glb")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    write_glb(out, P2, N2, UV2, I2, jpeg(color, 1024), png(normal, 512) if normal is not None else None)
    print(f"{name}: {len(I)} → {len(I2)} triangles, {len(P2)} vertices, {os.path.getsize(src) // 1024} → {os.path.getsize(out) // 1024} KB, size {np.round(P2.max(0) - P2.min(0), 3).tolist()}")


main()
