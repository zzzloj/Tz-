using UnityEngine;
using UnityEngine.Rendering;

/// <summary>
/// Draws many copies of one model (trees, bushes, rocks of a map chunk, TzMap) with GPU instancing instead of a
/// game object each: near the camera the model, farther the simple far shape (or nothing), none outside the
/// view. The server draws nothing (no camera) and keeps only the matrices.
/// </summary>
public class TzInstancer : MonoBehaviour
{
    public Mesh nearMesh;
    public Material nearMaterial;
    [Tooltip("Shown beyond nearDistance; none — nothing")]
    public Mesh farMesh;
    public Material[] farMaterials = new Material[0];
    public float nearDistance = 70f;
    public float farDistance = 250f;
    public Matrix4x4[] matrices = new Matrix4x4[0];
    public Bounds bounds;

    static readonly Plane[] planes = new Plane[6];

    void Update()
    {
        var cam = Camera.main;
        if (cam == null || matrices == null || matrices.Length == 0) return;
        GeometryUtility.CalculateFrustumPlanes(cam, planes);
        if (!GeometryUtility.TestPlanesAABB(planes, bounds)) return;
        float d = Mathf.Sqrt(bounds.SqrDistance(cam.transform.position));
        if (d <= nearDistance)
            Draw(nearMesh, new[] { nearMaterial });
        else if (d <= farDistance && farMesh != null)
            Draw(farMesh, farMaterials);
    }

    void Draw(Mesh mesh, Material[] materials)
    {
        if (mesh == null) return;
        for (int s = 0; s < mesh.subMeshCount && s < materials.Length; s++)
        {
            if (materials[s] == null) continue;
            var rp = new RenderParams(materials[s]) { shadowCastingMode = ShadowCastingMode.On, receiveShadows = true, worldBounds = bounds };
            for (int start = 0; start < matrices.Length; start += 1000)
                Graphics.RenderMeshInstanced(rp, mesh, s, matrices, Mathf.Min(1000, matrices.Length - start), start);
        }
    }
}
