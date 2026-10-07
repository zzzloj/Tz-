// Stubs of the Unity and Newtonsoft APIs the client uses, only so that `dotnet build` can check the
// C# of unity/Assets/Scripts/Runtime without Unity. Shapes follow Unity 6; bodies do nothing.
#pragma warning disable CS0067, CS0626, CS0114
using System;
using System.Collections;

namespace UnityEngine
{
    public class Object
    {
        public static void Destroy(Object o) { }
        public static void DestroyImmediate(Object o) { }
        public static implicit operator bool(Object o) => o != null;
    }
    public class Component : Object
    {
        public GameObject gameObject => null;
        public Transform transform => null;
        public T GetComponent<T>() => default;
    }
    public class Behaviour : Component { public bool enabled { get; set; } }
    public class MonoBehaviour : Behaviour { public Coroutine StartCoroutine(IEnumerator routine) => null; }
    public class Coroutine { }
    public class YieldInstruction { }
    public class WaitForSeconds : YieldInstruction { public WaitForSeconds(float s) { } }
    public class GameObject : Object
    {
        public GameObject(string name) { }
        public GameObject(string name, params Type[] components) { }
        public Transform transform => null;
        public T AddComponent<T>() where T : Component => default;
        public T GetComponent<T>() => default;
        public void SetActive(bool value) { }
    }
    public class Transform : Component, IEnumerable
    {
        public void SetParent(Transform parent, bool worldPositionStays) { }
        public Transform parent => null;
        public int childCount => 0;
        public Transform GetChild(int i) => null;
        public void SetAsFirstSibling() { }
        public Vector3 localScale { get; set; }
        public Quaternion localRotation { get; set; }
        public IEnumerator GetEnumerator() => null;
    }
    public class RectTransform : Transform
    {
        public Vector2 anchorMin { get; set; }
        public Vector2 anchorMax { get; set; }
        public Vector2 offsetMin { get; set; }
        public Vector2 offsetMax { get; set; }
        public Vector2 pivot { get; set; }
        public Vector2 sizeDelta { get; set; }
        public Vector2 anchoredPosition { get; set; }
        public Rect rect => default;
        public void GetWorldCorners(Vector3[] corners) { }
    }
    public struct Rect
    {
        public Rect(float x, float y, float w, float h) { xMin = x; yMin = y; xMax = x + w; yMax = y + h; }
        public float xMin, yMin, xMax, yMax;
        public float width => xMax - xMin;
        public float height => yMax - yMin;
    }
    public struct Vector2
    {
        public float x, y;
        public Vector2(float x, float y) { this.x = x; this.y = y; }
        public static Vector2 zero => default;
        public static Vector2 operator +(Vector2 a, Vector2 b) => default;
        public static Vector2 operator *(Vector2 a, float b) => default;
        public static Vector2 Lerp(Vector2 a, Vector2 b, float t) => default;
        public float magnitude => 0;
        public static Vector2 ClampMagnitude(Vector2 v, float max) => v;
    }
    public struct Vector3
    {
        public float x, y, z;
        public Vector3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
        public static Vector3 one => default;
        public static Vector3 operator *(Vector3 a, float b) => default;
    }
    public struct Quaternion { public static Quaternion Euler(float x, float y, float z) => default; }
    public struct Vector4 { public Vector4(float x, float y, float z, float w) { } public static Vector4 zero => default; }
    public struct Color
    {
        public float r, g, b, a;
        public Color(float r, float g, float b, float a = 1) { this.r = r; this.g = g; this.b = b; this.a = a; }
        public static Color white => default;
        public static Color Lerp(Color a, Color b, float t) => default;
    }
    public struct Color32 { public Color32(byte r, byte g, byte b, byte a) { } }
    public static class Mathf
    {
        public const float PI = 3.14159f;
        public static float Max(float a, float b) => a; public static int Max(int a, int b) => a;
        public static float Min(float a, float b) => a; public static int Min(int a, int b) => a;
        public static float Clamp01(float v) => v;
        public static float Abs(float v) => v;
        public static float Lerp(float a, float b, float t) => a;
        public static float Sin(float v) => v;
        public static float Sqrt(float v) => v;
        public const float Rad2Deg = 57.29578f, Deg2Rad = 0.01745329f;
        public static float Atan2(float y, float x) => 0;
        public static float Cos(float v) => v;
        public static float Round(float v) => v;
        public static float DeltaAngle(float a, float b) => 0;
    }
    public static class RectTransformUtility
    {
        public static bool ScreenPointToLocalPointInRectangle(RectTransform rt, Vector2 screen, Camera cam, out Vector2 local) { local = default; return true; }
    }
    public static class Time { public static float deltaTime => 0; public static float time => 0; }
    public static class Random { public static float Range(float a, float b) => a; public static float value => 0; }
    public static class Screen { public static Rect safeArea => default; public static int width => 0; public static int height => 0; public static int sleepTimeout { get; set; } }
    public static class SleepTimeout { public const int NeverSleep = -1; }
    public static class Application { public static int targetFrameRate { get; set; } }
    public enum CameraClearFlags { SolidColor }
    public class Camera : Behaviour { public CameraClearFlags clearFlags; public Color backgroundColor; public bool orthographic; }
    public enum RenderMode { ScreenSpaceOverlay }
    public class Canvas : Behaviour { public RenderMode renderMode; public float scaleFactor => 1; }
    public class TextAsset : Object { public string text => ""; }
    public static class TouchScreenKeyboard { public static bool visible => false; public static Rect area => default; }
    public class CanvasGroup : Behaviour { public float alpha; public bool blocksRaycasts; }
    public enum SpriteMeshType { FullRect, Tight }
    public class Sprite : Object
    {
        public static Sprite Create(Texture2D t, Rect r, Vector2 pivot, float ppu) => null;
        public static Sprite Create(Texture2D t, Rect r, Vector2 pivot, float ppu, uint extrude, SpriteMeshType mesh, Vector4 border) => null;
    }
    public enum TextureFormat { RGBA32 }
    public enum FilterMode { Bilinear }
    public enum TextureWrapMode { Clamp }
    public class Texture2D : Object
    {
        public Texture2D(int w, int h, TextureFormat f, bool mips) { }
        public int width => 0; public int height => 0;
        public FilterMode filterMode { get; set; }
        public TextureWrapMode wrapMode { get; set; }
        public void SetPixels32(Color32[] px) { }
        public void Apply() { }
    }
    public class Font : Object { public static Font CreateDynamicFontFromOSFont(string name, int size) => null; }
    public static class Resources { public static T Load<T>(string path) where T : Object => null; }
    public enum TextAnchor { UpperLeft, UpperCenter, UpperRight, MiddleLeft, MiddleCenter, MiddleRight, LowerLeft, LowerCenter, LowerRight }
    public enum HorizontalWrapMode { Wrap, Overflow }
    public enum VerticalWrapMode { Truncate, Overflow }
    public static class PlayerPrefs
    {
        public static string GetString(string k, string d) => d;
        public static void SetString(string k, string v) { }
        public static void Save() { }
    }
    public enum RuntimeInitializeLoadType { AfterSceneLoad }
    [AttributeUsage(AttributeTargets.Method)] public class RuntimeInitializeOnLoadMethodAttribute : Attribute { public RuntimeInitializeOnLoadMethodAttribute(RuntimeInitializeLoadType t) { } }
    public static class Debug { public static void Log(object o) { } }
    public class AsyncOperation : YieldInstruction { public bool isDone => true; public event Action<AsyncOperation> completed; }
}

namespace UnityEngine.Events
{
    public delegate void UnityAction();
    public class UnityEvent { public void AddListener(UnityAction a) { } }
}

namespace UnityEngine.EventSystems
{
    public class EventSystem : MonoBehaviour { public static EventSystem current => null; public GameObject currentSelectedGameObject => null; }
    public class StandaloneInputModule : MonoBehaviour { }
    public class PointerEventData { public Vector2 position; }
    public interface IPointerDownHandler { void OnPointerDown(PointerEventData e); }
    public interface IDragHandler { void OnDrag(PointerEventData e); }
    public interface IPointerUpHandler { void OnPointerUp(PointerEventData e); }
}

namespace UnityEngine.Networking
{
    public class DownloadHandler : IDisposable { public string text => ""; public void Dispose() { } }
    public class DownloadHandlerBuffer : DownloadHandler { }
    public class UploadHandler : IDisposable { public void Dispose() { } }
    public class UploadHandlerRaw : UploadHandler { public UploadHandlerRaw(byte[] data) { } }
    public class UnityWebRequestAsyncOperation : AsyncOperation { }
    public class UnityWebRequest : IDisposable
    {
        public enum Result { InProgress, Success, ConnectionError, ProtocolError, DataProcessingError }
        public UnityWebRequest(string url, string method) { }
        public DownloadHandler downloadHandler { get; set; }
        public UploadHandler uploadHandler { get; set; }
        public int timeout { get; set; }
        public Result result => Result.Success;
        public long responseCode => 200;
        public void SetRequestHeader(string k, string v) { }
        public UnityWebRequestAsyncOperation SendWebRequest() => null;
        public void Dispose() { }
    }
}

namespace UnityEngine.UI
{
    using UnityEngine.Events;
    public class UIBehaviour : MonoBehaviour { }
    public class Graphic : UIBehaviour
    {
        public virtual Color color { get; set; }
        public bool raycastTarget { get; set; }
        public RectTransform rectTransform => null;
    }
    public class MaskableGraphic : Graphic { }
    public class Image : MaskableGraphic
    {
        public enum Type { Simple, Sliced, Tiled, Filled }
        public enum FillMethod { Horizontal, Vertical, Radial90, Radial180, Radial360 }
        public Sprite sprite { get; set; }
        public Type type { get; set; }
        public FillMethod fillMethod { get; set; }
        public float fillAmount { get; set; }
        public bool preserveAspect { get; set; }
    }
    public class Text : MaskableGraphic
    {
        public Font font { get; set; }
        public int fontSize { get; set; }
        public TextAnchor alignment { get; set; }
        public string text { get; set; }
        public bool supportRichText { get; set; }
        public HorizontalWrapMode horizontalOverflow { get; set; }
        public VerticalWrapMode verticalOverflow { get; set; }
    }
    public struct ColorBlock { public Color pressedColor, disabledColor; }
    public class Selectable : UIBehaviour
    {
        public enum Transition { None, ColorTint }
        public Graphic targetGraphic { get; set; }
        public ColorBlock colors { get; set; }
        public Transition transition { get; set; }
        public bool interactable { get; set; }
        public Image image { get; set; }
    }
    public class Button : Selectable
    {
        public class ButtonClickedEvent : UnityEvent { }
        public ButtonClickedEvent onClick { get; } = new ButtonClickedEvent();
    }
    public class InputField : Selectable
    {
        public enum LineType { SingleLine, MultiLineSubmit }
        public enum ContentType { Standard, Password }
        public string text { get; set; }
        public bool isFocused => false;
        public Text textComponent { get; set; }
        public Graphic placeholder { get; set; }
        public LineType lineType { get; set; }
        public ContentType contentType { get; set; }
    }
    public class BaseMeshEffect : UIBehaviour { }
    public class Shadow : BaseMeshEffect { public Color effectColor { get; set; } public Vector2 effectDistance { get; set; } }
    public class CanvasScaler : UIBehaviour
    {
        public enum ScaleMode { ConstantPixelSize, ScaleWithScreenSize }
        public ScaleMode uiScaleMode { get; set; }
        public Vector2 referenceResolution { get; set; }
        public float matchWidthOrHeight { get; set; }
    }
    public class GraphicRaycaster : UIBehaviour { }
    public class RectMask2D : UIBehaviour { }
    public class AspectRatioFitter : UIBehaviour
    {
        public enum AspectMode { None, EnvelopeParent }
        public AspectMode aspectMode { get; set; }
        public float aspectRatio { get; set; }
    }
    public class LayoutGroup : UIBehaviour { public TextAnchor childAlignment { get; set; } }
    public class HorizontalOrVerticalLayoutGroup : LayoutGroup
    {
        public float spacing { get; set; }
        public bool childControlWidth { get; set; }
        public bool childControlHeight { get; set; }
        public bool childForceExpandWidth { get; set; }
        public bool childForceExpandHeight { get; set; }
    }
    public class HorizontalLayoutGroup : HorizontalOrVerticalLayoutGroup { }
    public class VerticalLayoutGroup : HorizontalOrVerticalLayoutGroup { }
    public class GridLayoutGroup : LayoutGroup { public Vector2 cellSize { get; set; } public Vector2 spacing { get; set; } }
    public class ContentSizeFitter : UIBehaviour
    {
        public enum FitMode { Unconstrained, PreferredSize }
        public FitMode verticalFit { get; set; }
    }
    public class ScrollRect : UIBehaviour
    {
        public bool horizontal { get; set; }
        public RectTransform content { get; set; }
        public RectTransform viewport { get; set; }
    }
}

namespace Newtonsoft.Json
{
    public enum NullValueHandling { Include, Ignore }
    public enum MissingMemberHandling { Ignore, Error }
    public class JsonSerializerSettings { public NullValueHandling NullValueHandling { get; set; } public MissingMemberHandling MissingMemberHandling { get; set; } }
    public static class JsonConvert
    {
        public static string SerializeObject(object o, JsonSerializerSettings s) => "";
        public static T DeserializeObject<T>(string s, JsonSerializerSettings settings) => default;
        public static T DeserializeObject<T>(string s) => default;
    }
    [AttributeUsage(AttributeTargets.Field | AttributeTargets.Property)] public class JsonPropertyAttribute : Attribute { public JsonPropertyAttribute(string name) { } }
}
