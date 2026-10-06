// Client of the game server (the same REST and WebSocket the Android and iOS apps use, see
// engine/shared/GameApi.kt). Calls are awaited on Unity's main thread.
using System;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using UnityEngine;
using UnityEngine.Networking;

namespace Amulet
{
    public class ApiError : Exception
    {
        public readonly string Code;
        public ApiError(string code) : base(Errors.Text(code)) { Code = code; }
    }

    public class Api
    {
        public const string DefaultServer = "https://217-177-74-66.sslip.io";
        const string TokenKey = "amulet.token";

        public string BaseUrl = DefaultServer;
        public string Token
        {
            get => PlayerPrefs.GetString(TokenKey, "");
            set { PlayerPrefs.SetString(TokenKey, value ?? ""); PlayerPrefs.Save(); }
        }

        static readonly JsonSerializerSettings Json = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Ignore,
            MissingMemberHandling = MissingMemberHandling.Ignore,
        };

        async Task<T> Send<T>(string method, string path, object body)
        {
            using (var req = new UnityWebRequest(BaseUrl.TrimEnd('/') + path, method))
            {
                req.downloadHandler = new DownloadHandlerBuffer();
                if (body != null)
                {
                    req.uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(JsonConvert.SerializeObject(body, Json)));
                    req.SetRequestHeader("Content-Type", "application/json");
                }
                if (!string.IsNullOrEmpty(Token)) req.SetRequestHeader("Authorization", "Bearer " + Token);
                req.timeout = 20;
                await Done(req.SendWebRequest());
                var text = req.downloadHandler.text;
                if (req.result == UnityWebRequest.Result.Success)
                    return typeof(T) == typeof(string) || string.IsNullOrEmpty(text) ? default : JsonConvert.DeserializeObject<T>(text, Json);
                if (req.result == UnityWebRequest.Result.ConnectionError) throw new ApiError("no_connection");
                string code;
                try { code = JsonConvert.DeserializeObject<ErrorResponse>(text, Json)?.error; }
                catch (Exception) { code = null; }
                throw new ApiError(code ?? (req.responseCode == 401 ? "unauthorized" : "http_" + req.responseCode));
            }
        }

        /// <summary>A UnityWebRequest's operation as a Task (continues on Unity's main thread).</summary>
        static Task Done(UnityWebRequestAsyncOperation op)
        {
            var done = new TaskCompletionSource<bool>();
            if (op.isDone) done.SetResult(true);
            else op.completed += _ => done.TrySetResult(true);
            return done.Task;
        }

        Task<T> Get<T>(string path) => Send<T>("GET", path, null);
        Task<T> Post<T>(string path, object body) => Send<T>("POST", path, body);

        public async Task<AuthResponse> Login(string login, string password)
        {
            var r = await Post<AuthResponse>("/api/auth/login", new Credentials { login = login, password = password });
            Token = r.token;
            return r;
        }

        public async Task<AuthResponse> Register(string login, string password)
        {
            var r = await Post<AuthResponse>("/api/auth/register", new Credentials { login = login, password = password });
            Token = r.token;
            return r;
        }

        public async Task Logout()
        {
            try { await Post<string>("/api/auth/logout", null); }
            catch (ApiError) { }
            Token = "";
        }

        public Task<MeView> Me() => Get<MeView>("/api/me");
        public Task<CharacterView> CreateCharacter(string name, string sex) => Post<CharacterView>("/api/characters", new NewCharacter { name = name, sex = sex });
        public Task<GameView> Game() => Get<GameView>("/api/game");
        public Task<GameView> Move(string target) => Post<GameView>("/api/game/move", new MoveRequest { target = target });
        public Task<GameView> Attack(string npc) => Post<GameView>("/api/game/attack", new TargetRequest { target = npc });
        public Task<GameView> Take(string item) => Post<GameView>("/api/game/take", new ItemRequest { item = item });
        public Task<GameView> Equip(string item) => Post<GameView>("/api/game/equip", new ItemRequest { item = item });
        public Task<GameView> Unequip(string item) => Post<GameView>("/api/game/unequip", new ItemRequest { item = item });
        public Task<GameView> Loot(string corpse, string item) => Post<GameView>("/api/game/loot", new LootRequest { corpse = corpse, item = item });
        public Task<GameView> Resurrect() => Post<GameView>("/api/game/resurrect", null);

        /// <summary>
        /// Listens to the server's "your screen changed" signals until [cancel] or the connection drops;
        /// [onChange] runs on the main thread for each one.
        /// </summary>
        public async Task Events(Action onChange, CancellationToken cancel)
        {
            var url = BaseUrl.TrimEnd('/').Replace("https://", "wss://").Replace("http://", "ws://") + "/api/events";
            using (var ws = new ClientWebSocket())
            {
                ws.Options.SetRequestHeader("Authorization", "Bearer " + Token);
                await ws.ConnectAsync(new Uri(url), cancel);
                var buffer = new ArraySegment<byte>(new byte[4096]);
                while (ws.State == WebSocketState.Open && !cancel.IsCancellationRequested)
                {
                    var r = await ws.ReceiveAsync(buffer, cancel);
                    if (r.MessageType == WebSocketMessageType.Close) break;
                    if (r.EndOfMessage) onChange();
                }
            }
        }
    }

}
