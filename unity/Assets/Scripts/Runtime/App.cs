// The Unity client's entry point (prototype, owner 06.10: more pictures, less text). The scene is
// empty: Boot makes the camera, the canvas and the app; screens are built from code (UI.cs).
// The client talks to the same server as the Android and iOS apps (Api.cs).
using System;
using System.Threading;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Amulet
{
    public static class Boot
    {
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Start()
        {
            Application.targetFrameRate = 60;
            Screen.sleepTimeout = SleepTimeout.NeverSleep;

            var cam = new GameObject("camera").AddComponent<Camera>();
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = Palette.Background;
            cam.orthographic = true;

            var es = new GameObject("events");
            es.AddComponent<EventSystem>();
            es.AddComponent<StandaloneInputModule>();

            var canvasGo = new GameObject("canvas");
            var canvas = canvasGo.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            var scaler = canvasGo.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            // Landscape (owner 07.10): the height is always 1080 reference pixels, the width follows the phone.
            scaler.referenceResolution = new Vector2(1920, 1080);
            scaler.matchWidthOrHeight = 1;
            canvasGo.AddComponent<GraphicRaycaster>();

            canvasGo.AddComponent<App>();
        }
    }

    public class App : MonoBehaviour
    {
        public readonly Api Api = new Api();
        RectTransform root;
        GameScreen game;
        CancellationTokenSource events;

        void Start()
        {
            // The safe area (notch, rounded corners) of the phone, in canvas coordinates.
            root = UI.Node("root", transform);
            var safe = Screen.safeArea;
            root.anchorMin = new Vector2(safe.xMin / Screen.width, safe.yMin / Screen.height);
            root.anchorMax = new Vector2(safe.xMax / Screen.width, safe.yMax / Screen.height);
            root.offsetMin = root.offsetMax = Vector2.zero;
            Open();
        }

        async void Open()
        {
            if (string.IsNullOrEmpty(Api.Token)) { ShowLogin(); return; }
            try
            {
                var me = await Api.Me();
                if (me.character == null) ShowCreate();
                else ShowGame();
            }
            catch (ApiError e) when (e.Code == "unauthorized") { Api.Token = ""; ShowLogin(); }
            catch (ApiError e) { ShowLogin(e.Message); }
            catch (Exception e) { ShowLogin("Нет связи с сервером: " + e.Message); }
        }

        void Show(Action<RectTransform> build)
        {
            StopEvents();
            if (game != null) { Destroy(game); game = null; }
            UI.Clear(root);
            var screen = UI.Node("screen", root).Place(0, 0, 1, 1);
            UI.Panel(screen, Palette.Background, "bg", false).rectTransform.Place(0, 0, 1, 1);
            build(screen);
        }

        // ---- sign in -------------------------------------------------------------------------------

        void ShowLogin(string error = null) => Show(s =>
        {
            // the emblem and the title on the left, the form on the right
            var hero = UI.Picture(s, Art.Get("brand/icon"), "logo");
            hero.rectTransform.At(0.28f, 0.58f, 480, 480);
            UI.Label(s, "Амулет дракона", 84, Palette.Title, TextAnchor.MiddleCenter, Art.TitleFont).rectTransform.At(0.28f, 0.17f, 900, 120);
            var login = UI.Input(s, "Логин");
            login.GetComponent<RectTransform>().At(0.7f, 0.78f, 760, 110);
            var password = UI.Input(s, "Пароль", true);
            password.GetComponent<RectTransform>().At(0.7f, 0.64f, 760, 110);
            var note = UI.Label(s, error ?? "", 32, Palette.Danger);
            note.rectTransform.At(0.7f, 0.52f, 800, 80);
            UI.Button(s, "Войти", async () => { await SignIn(note, () => Api.Login(login.text.Trim(), password.text)); })
                .GetComponent<RectTransform>().At(0.7f, 0.4f, 760, 120);
            UI.Button(s, "Новый аккаунт", async () => { await SignIn(note, () => Api.Register(login.text.Trim(), password.text)); }, Palette.Raised)
                .GetComponent<RectTransform>().At(0.7f, 0.26f, 760, 110);
            UI.Label(s, "Прототип на Unity · " + Api.BaseUrl.Replace("https://", ""), 26, Palette.Muted).rectTransform.At(0.7f, 0.06f, 900, 60);
        });

        async Task SignIn(Text note, Func<Task<AuthResponse>> call)
        {
            note.text = "";
            try { await call(); Open(); }
            catch (ApiError e) { note.text = e.Message; }
        }

        void ShowCreate(string error = null) => Show(s =>
        {
            UI.Label(s, "Новый герой", 80, Palette.Title, TextAnchor.MiddleCenter, Art.TitleFont).rectTransform.At(0.5f, 0.85f, 1000, 120);
            var name = UI.Input(s, "Имя героя");
            name.GetComponent<RectTransform>().At(0.5f, 0.68f, 860, 110);
            var sex = "m";
            Button male = null, female = null;
            void Mark() { male.image.color = sex == "m" ? Palette.Primary : Palette.Raised; female.image.color = sex == "f" ? Palette.Primary : Palette.Raised; }
            male = UI.Button(s, "Мужчина", () => { sex = "m"; Mark(); });
            male.GetComponent<RectTransform>().At(0.5f, 0.52f, 420, 110, -220);
            female = UI.Button(s, "Женщина", () => { sex = "f"; Mark(); });
            female.GetComponent<RectTransform>().At(0.5f, 0.52f, 420, 110, 220);
            Mark();
            var note = UI.Label(s, error ?? "", 34, Palette.Danger);
            note.rectTransform.At(0.5f, 0.4f, 900, 80);
            UI.Button(s, "Начать", async () =>
            {
                note.text = "";
                try { await Api.CreateCharacter(name.text.Trim(), sex); Open(); }
                catch (ApiError e) { note.text = e.Message; }
            }).GetComponent<RectTransform>().At(0.5f, 0.26f, 860, 120);
        });

        // ---- the game --------------------------------------------------------------------------------

        void ShowGame() => Show(s =>
        {
            game = gameObject.AddComponent<GameScreen>();
            game.Init(this, s);
            StartEvents();
        });

        public async void SignOut()
        {
            await Api.Logout();
            ShowLogin();
        }

        void StartEvents()
        {
            StopEvents();
            events = new CancellationTokenSource();
            Listen(events.Token);
        }

        void StopEvents()
        {
            events?.Cancel();
            events = null;
        }

        async void Listen(CancellationToken cancel)
        {
            while (!cancel.IsCancellationRequested)
            {
                try { await Api.Events(() => { if (game != null) game.Dirty = true; }, cancel); }
                catch (Exception) { }
                if (cancel.IsCancellationRequested) break;
                if (game != null) game.Dirty = true;
                await Task.Delay(3000);
            }
        }

        void OnDestroy() => StopEvents();
    }
}
