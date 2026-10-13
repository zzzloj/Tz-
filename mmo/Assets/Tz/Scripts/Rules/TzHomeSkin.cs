using System.Collections;
using System.Collections.Generic;
using MultiplayerARPG;
using MultiplayerARPG.MMO;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// The look of the first screens (CanvasHome-CE: servers, login, registration, characters, a new character),
/// put on the canvas by TzHome at build time and done here when the game runs:
///  - the splash picture of the game behind login and registration, the name of the game over the forms on its
///    dark left side; dark windows with crimson titles and buttons, parchment letters (Alegreya Sans);
///  - Russian words instead of the demo's English (and the framework's messages, TzRussian);
///  - the character creation without the empty choices (faction, class, hair): only the look and the name;
///  - after a registration the account logs in by itself (before, it went back to the login form silently);
///  - a phone's keyboard does not hide the field being typed in: its window moves up above the keyboard.
/// </summary>
public class TzHomeSkin : MonoBehaviour
{
    public Sprite background;
    public Font font;

    static readonly Color Panel = new Color(0.07f, 0.05f, 0.05f, 0.9f);
    static readonly Color TitleBar = new Color(0.36f, 0.06f, 0.05f, 1f);
    static readonly Color Primary = new Color(0.62f, 0.12f, 0.08f, 1f);
    static readonly Color Secondary = new Color(0.2f, 0.16f, 0.14f, 0.95f);
    static readonly Color Field = new Color(0.13f, 0.11f, 0.1f, 1f);
    static readonly Color Ink = new Color(0.94f, 0.88f, 0.76f, 1f);
    static readonly Color Gold = new Color(0.86f, 0.68f, 0.32f, 1f);
    static readonly Color Hint = new Color(0.72f, 0.66f, 0.56f, 0.6f);

    // The demo's words → ours (whole texts of the labels, buttons, hints).
    static readonly Dictionary<string, string> Words = new Dictionary<string, string>
    {
        ["Servers"] = "Сервер", ["Connect"] = "Войти в мир", ["Exit"] = "Выход", ["Channels"] = "Канал",
        ["Logout"] = "Выйти", ["Disconnect"] = "Назад", ["Login"] = "Вход", ["Register"] = "Регистрация",
        ["Username"] = "Логин", ["Password"] = "Пароль", ["Enter username..."] = "Логин (латиница, цифры)",
        ["Enter password..."] = "Пароль", ["Go to Register"] = "Создать аккаунт", ["Go to Login"] = "У меня есть аккаунт",
        ["- OR -"] = "или", ["Select Character"] = "Персонажи", ["Start Game"] = "В игру", ["Delete"] = "Удалить",
        ["Create"] = "Создать", ["Back"] = "Назад", ["Entity"] = "Облик", ["Class"] = "Класс", ["Faction"] = "Сторона",
        ["Hair"] = "Причёска", ["Hair Color"] = "Цвет волос", ["Enter character name..."] = "Имя героя", ["Select"] = "Выбрать",
    };
    // Buttons that do the main thing of their screen.
    static readonly HashSet<string> MainButtons = new HashSet<string>
        { "ButtonLogin", "ButtonRegister", "ButtonConnect", "CreateButton", "StartButton" };
    // Windows of the character creation that offer nothing to choose here.
    static readonly string[] Unused = { "Window--Faction", "Window--Class", "Window--Hair", "Window--HairColor" };
    // Screens shown over the splash picture (the others show the 3D hero on the dark).
    static readonly string[] OverPicture = { "UIServerList", "UILogin", "UIRegister", "UIChannelList" };

    readonly HashSet<int> done = new HashSet<int>();
    readonly Dictionary<RectTransform, Vector2> lifted = new Dictionary<RectTransform, Vector2>();
    Canvas canvas;
    GameObject picture, gameName;
    Text toast;
    float toastUntil, nextPass;

    void Awake()
    {
        TzRussian.Apply();
        canvas = GetComponentInParent<Canvas>();
    }

    /// <summary>The look without playing (TzShots' pictures of the first screens).</summary>
    public void Preview()
    {
        canvas = GetComponentInParent<Canvas>();
        Background();
        foreach (var name in Unused)
            foreach (var t in GetComponentsInChildren<Transform>(true))
                if (t.name == name) Hide(t.gameObject);
        Style();
    }

    void Start()
    {
        var cam = Camera.main;
        if (cam != null) { cam.clearFlags = CameraClearFlags.SolidColor; cam.backgroundColor = new Color(0.06f, 0.04f, 0.04f); }
        Background();
        foreach (var name in Unused)
            foreach (var t in GetComponentsInChildren<Transform>(true))
                if (t.name == name) Hide(t.gameObject);
        foreach (var v in FindObjectsByType<VersionText>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            v.gameObject.SetActive(false);
        Toast();
        Style();
        AutoLoginAfterRegistration();
    }

    void Update()
    {
        if (Time.unscaledTime >= nextPass) { nextPass = Time.unscaledTime + 0.3f; Style(); }   // entries appear later
        bool over = false;
        foreach (var name in OverPicture)
        {
            var t = transform.Find(name);
            if (t != null && t.gameObject.activeInHierarchy) over = true;
        }
        if (picture != null) picture.SetActive(over);
        if (gameName != null) gameName.SetActive(over);
        if (toast != null && toast.gameObject.activeSelf && Time.unscaledTime > toastUntil) toast.gameObject.SetActive(false);
        KeepAboveKeyboard();
    }

    // ---- the picture and the name of the game ----
    void Background()
    {
        if (background != null)
        {
            picture = new GameObject("TzBackground", typeof(RectTransform), typeof(Image), typeof(AspectRatioFitter));
            var rt = (RectTransform)picture.transform;
            rt.SetParent(transform, false);
            rt.SetAsFirstSibling();
            var img = picture.GetComponent<Image>();
            img.sprite = background; img.raycastTarget = false;
            var fit = picture.GetComponent<AspectRatioFitter>();
            fit.aspectMode = AspectRatioFitter.AspectMode.EnvelopeParent;
            fit.aspectRatio = background.rect.width / background.rect.height;
            done.Add(img.GetInstanceID());
        }
        gameName = new GameObject("TzGameName", typeof(RectTransform), typeof(Text), typeof(Shadow));
        var nt = (RectTransform)gameName.transform;
        nt.SetParent(transform, false);
        nt.SetSiblingIndex(picture != null ? 1 : 0);
        nt.anchorMin = nt.anchorMax = new Vector2(0.25f, 0.84f);
        nt.sizeDelta = new Vector2(520, 70);
        var text = gameName.GetComponent<Text>();
        text.text = "Амулет дракона";
        text.font = font != null ? font : Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        text.fontSize = 46; text.alignment = TextAnchor.MiddleCenter; text.color = Gold; text.raycastTarget = false;
        gameName.GetComponent<Shadow>().effectColor = new Color(0, 0, 0, 0.8f);
        done.Add(text.GetInstanceID());
        // The forms of the first screens on the dark left part of the picture.
        foreach (var name in OverPicture)
        {
            var w = transform.Find(name + "/Window") as RectTransform;
            if (w == null) continue;
            w.anchorMin = w.anchorMax = new Vector2(0.25f, 0.47f);
            w.pivot = new Vector2(0.5f, 0.5f);
            w.anchoredPosition = Vector2.zero;
        }
    }

    // ---- colours and words ----
    void Style()
    {
        foreach (var g in GetComponentsInChildren<Graphic>(true))
        {
            if (!done.Add(g.GetInstanceID())) continue;
            if (g is Text t) { Letters(t); continue; }
            var go = g.gameObject;
            string n = go.name;
            var button = go.GetComponent<Button>();
            if (button != null)
            {
                g.color = MainButtons.Contains(n) ? Primary : Secondary;
                Colors(button);
            }
            else if (go.GetComponent<InputField>() != null) g.color = Field;
            else if (go.GetComponent<Toggle>() != null) { g.color = Secondary; Colors(go.GetComponent<Toggle>()); }
            else if (n == "Title") g.color = TitleBar;
            else if (n == "Window" || n.StartsWith("Window--")) g.color = Panel;
            else if (go.GetComponentInParent<Scrollbar>() != null) g.color = n == "Handle" ? new Color(0.4f, 0.3f, 0.24f) : new Color(0, 0, 0, 0.3f);
            else if (n == "Scroll View" || n == "Viewport" || n == "Info" || n == "ListContainer") g.color = new Color(0, 0, 0, 0.25f);
            else if (n.ToLowerInvariant().Contains("select") || n.ToLowerInvariant().Contains("highlight") || n.ToLowerInvariant().Contains("frame"))
                g.color = new Color(Gold.r, Gold.g, Gold.b, g.color.a);
            else if (g is Image img && img.color.r > 0.6f && img.color.g > 0.6f && img.color.b > 0.6f && img.sprite != null && img.sprite.name.StartsWith("UISprite"))
                g.color = Secondary;   // the demo's light boxes
        }
    }

    void Letters(Text t)
    {
        if (font != null) t.font = font;
        if (Words.TryGetValue(t.text.Trim(), out var ru)) t.text = ru;
        var input = t.GetComponentInParent<InputField>();
        if (input != null && input.placeholder == t) t.color = Hint;
        else if (t.transform.parent != null && t.transform.parent.name == "Title") { t.color = Gold; t.fontStyle = FontStyle.Bold; }
        else t.color = Ink;
    }

    static void Colors(Selectable s)
    {
        var c = s.colors;
        c.normalColor = Color.white;
        c.highlightedColor = new Color(1.15f, 1.1f, 1.05f);
        c.pressedColor = new Color(0.75f, 0.7f, 0.65f);
        c.selectedColor = Color.white;
        c.disabledColor = new Color(0.5f, 0.5f, 0.5f, 0.6f);
        c.colorMultiplier = 1.2f;
        s.colors = c;
    }

    static void Hide(GameObject go)
    {
        var g = go.GetComponent<CanvasGroup>();
        if (g == null) g = go.AddComponent<CanvasGroup>();
        g.alpha = 0; g.interactable = false; g.blocksRaycasts = false;
    }

    // ---- registration ----
    void AutoLoginAfterRegistration()
    {
        var reg = GetComponentInChildren<UIMmoRegister>(true);
        var login = GetComponentInChildren<UIMmoLogin>(true);
        if (reg == null || login == null) return;
        reg.onRegisterSuccess.AddListener(() =>
        {
            login.Username = reg.Username;
            login.Password = reg.Password;
            Say("Аккаунт создан. Входим…", 4);
            StartCoroutine(LoginNextFrame(login));
        });
        login.onLoginSuccess.AddListener(() => Say("Вход выполнен", 2));
    }

    static IEnumerator LoginNextFrame(UIMmoLogin login)
    {
        yield return null;
        login.OnClickLogin();
    }

    void Toast()
    {
        var go = new GameObject("TzToast", typeof(RectTransform), typeof(Image));
        var rt = (RectTransform)go.transform;
        rt.SetParent(transform, false);
        rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.93f);
        rt.sizeDelta = new Vector2(460, 48);
        var bg = go.GetComponent<Image>();
        bg.color = new Color(0.1f, 0.07f, 0.06f, 0.92f); bg.raycastTarget = false;
        done.Add(bg.GetInstanceID());
        var tgo = new GameObject("Text", typeof(RectTransform), typeof(Text));
        var trt = (RectTransform)tgo.transform;
        trt.SetParent(rt, false);
        trt.anchorMin = Vector2.zero; trt.anchorMax = Vector2.one; trt.offsetMin = trt.offsetMax = Vector2.zero;
        toast = tgo.GetComponent<Text>();
        toast.font = font != null ? font : Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        toast.fontSize = 24; toast.alignment = TextAnchor.MiddleCenter; toast.color = Gold; toast.raycastTarget = false;
        done.Add(toast.GetInstanceID());
        toast = tgo.GetComponent<Text>();
        go.SetActive(false);
    }

    void Say(string text, float seconds)
    {
        if (toast == null) return;
        toast.text = text;
        toast.transform.parent.gameObject.SetActive(true);
        toast.transform.parent.SetAsLastSibling();
        toastUntil = Time.unscaledTime + seconds;
        toast.gameObject.SetActive(true);
    }

    // ---- the phone's keyboard ----
    void KeepAboveKeyboard()
    {
        InputField focused = null;
        if (Application.isMobilePlatform)
            foreach (var f in GetComponentsInChildren<InputField>())
                if (f.isFocused) { focused = f; break; }
        RectTransform target = focused != null ? Movable(focused.transform) : null;
        // Back down whatever is not being typed in now.
        var back = new List<RectTransform>();
        foreach (var kv in lifted) if (kv.Key != target) back.Add(kv.Key);
        foreach (var rt in back) { if (rt != null) rt.anchoredPosition = lifted[rt]; lifted.Remove(rt); }
        if (target == null || canvas == null) return;
        if (!lifted.ContainsKey(target)) lifted[target] = target.anchoredPosition;
        // The keyboard's top: its area when the system tells it, else ~60 % of a landscape screen.
        float keyboard = TouchScreenKeyboard.area.height > 1 ? TouchScreenKeyboard.area.height : Screen.height * 0.6f;
        var corners = new Vector3[4];
        ((RectTransform)focused.transform).GetWorldCorners(corners);
        float bottom = corners[0].y - (target.anchoredPosition.y - lifted[target].y) * canvas.scaleFactor;   // where it was
        float need = keyboard + 16 - bottom;
        target.anchoredPosition = lifted[target] + new Vector2(0, Mathf.Max(0, need) / canvas.scaleFactor);
    }

    /// <summary>What moves with a field: its window (or, outside one, the field itself).</summary>
    RectTransform Movable(Transform field)
    {
        for (var t = field; t != null && t != transform; t = t.parent)
            if (t.name == "Window" || t.name.StartsWith("Window--")) return t as RectTransform;
        return field as RectTransform;
    }
}
