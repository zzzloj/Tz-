// Builds the MMO client and server without anyone opening the editor (CI: .github/workflows/mmo.yml).
// Step 2: the demo scenes of MmoKitCE (Assets/Tz/BaseDemo, MIT) — init, home (login, characters)
// and one map — with the client pointed at our VPS.
#if UNITY_EDITOR
using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;

public static class MmoBuild
{
    static readonly string[] SceneList =
    {
        "Assets/Tz/BaseDemo/Scenes/BaseInit-CE.unity",
        "Assets/Tz/BaseDemo/Scenes/BaseHome-CE.unity",
        "Assets/Tz/BaseDemo/Scenes/BaseMap-CE.unity",
    };

    /// <summary>The address players connect to (the login server, UDP 7500); TZ_MMO_HOST overrides it.</summary>
    static string Host => Environment.GetEnvironmentVariable("TZ_MMO_HOST") is string h && h.Length > 0 ? h : "217.177.74.66";

    static string[] Scenes()
    {
        Capture();
        EnsureTextMeshPro();
        CopyContent();
        TzImport.Run(Path.GetFullPath(Path.Combine(Root(), "..")));
        EditorBuildSettings.scenes = Array.ConvertAll(SceneList, s => new EditorBuildSettingsScene(s, true));
        PlayerSettings.companyName = "Amulet";
        PlayerSettings.productName = "Амулет дракона MMO";
        PlayerSettings.bundleVersion = "0.0.2";
        AssetDatabase.SaveAssets();
        return SceneList;
    }

    /// <summary>
    /// The game's data the rules read at run time, copied from content/ (the one source of it) into
    /// Resources/Tz before every build: content/logic/balance.json → Resources/Tz/balance.json.
    /// </summary>
    static void CopyContent()
    {
        var from = Path.Combine(Root(), "..", "content", "logic", "balance.json");
        var to = Path.Combine(Application.dataPath, "Tz", "Resources", "Tz", "balance.json");
        if (!File.Exists(from)) throw new FileNotFoundException("No balance to build with", from);
        Directory.CreateDirectory(Path.GetDirectoryName(to));
        File.Copy(from, to, true);
        AssetDatabase.Refresh();
        Debug.Log("Balance copied: " + to);
    }

    /// <summary>The demo's text uses TextMesh Pro's default font, which comes from its essential resources.</summary>
    static void EnsureTextMeshPro()
    {
        if (AssetDatabase.IsValidFolder("Assets/TextMesh Pro")) return;
        // AssetDatabase.ImportPackage finishes only after this method returns in batch mode, so the
        // package (a tar.gz of <guid>/asset, asset.meta, pathname) is unpacked here by hand.
        var ugui = UnityEditor.PackageManager.PackageInfo.FindForAssetPath("Packages/com.unity.ugui");
        var package = Path.Combine(ugui != null ? ugui.resolvedPath : Path.GetFullPath("Packages/com.unity.ugui"),
            "Package Resources", "TMP Essential Resources.unitypackage");
        int files = Unpack(package, Root());
        AssetDatabase.Refresh(ImportAssetOptions.ForceSynchronousImport);
        Debug.Log($"Unpacked TMP Essential Resources ({files} files): " + AssetDatabase.IsValidFolder("Assets/TextMesh Pro"));
    }

    /// <summary>Writes the assets of a .unitypackage under [projectRoot]; returns how many.</summary>
    static int Unpack(string package, string projectRoot)
    {
        var entries = new System.Collections.Generic.Dictionary<string, System.Collections.Generic.Dictionary<string, byte[]>>();
        using (var gz = new System.IO.Compression.GZipStream(File.OpenRead(package), System.IO.Compression.CompressionMode.Decompress))
        {
            var header = new byte[512];
            while (true)
            {
                if (ReadFull(gz, header, 512) < 512) break;
                string name = Field(header, 0, 100);
                if (name.Length == 0) break;
                string prefix = Field(header, 345, 155);
                if (prefix.Length > 0) name = prefix + "/" + name;
                string octal = Field(header, 124, 12).Trim();
                long size = octal.Length == 0 ? 0 : Convert.ToInt64(octal, 8);
                var data = new byte[size];
                ReadFull(gz, data, (int)size);
                int pad = (int)((512 - size % 512) % 512);
                if (pad > 0) ReadFull(gz, new byte[pad], pad);
                if (name.StartsWith("./")) name = name.Substring(2);
                int slash = name.IndexOf('/');
                if (slash <= 0 || header[156] == (byte)'5') continue;
                string guid = name.Substring(0, slash), part = name.Substring(slash + 1);
                if (!entries.TryGetValue(guid, out var e)) entries[guid] = e = new System.Collections.Generic.Dictionary<string, byte[]>();
                e[part] = data;
            }
        }
        int count = 0;
        foreach (var e in entries.Values)
        {
            if (!e.TryGetValue("pathname", out var pn)) continue;
            string path = System.Text.Encoding.UTF8.GetString(pn).Split('\n')[0].Trim();
            string full = Path.Combine(projectRoot, path);
            if (e.TryGetValue("asset", out var asset))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(full));
                File.WriteAllBytes(full, asset);
                count++;
            }
            else Directory.CreateDirectory(full);
            if (e.TryGetValue("asset.meta", out var meta)) File.WriteAllBytes(full + ".meta", meta);
        }
        return count;
    }

    /// <summary>A NUL-terminated text field of a tar header.</summary>
    static string Field(byte[] header, int at, int length)
    {
        int end = Array.IndexOf(header, (byte)0, at, length);
        return System.Text.Encoding.UTF8.GetString(header, at, (end < 0 ? at + length : end) - at);
    }

    static int ReadFull(Stream s, byte[] buffer, int count)
    {
        int read = 0;
        while (read < count) { int n = s.Read(buffer, read, count - read); if (n <= 0) break; read += n; }
        return read;
    }

    /// <summary>Points the demo's server entry at our VPS.</summary>
    static void PointAtServer()
    {
        foreach (var guid in AssetDatabase.FindAssets("t:MmoNetworkSetting"))
        {
            var asset = AssetDatabase.LoadMainAssetAtPath(AssetDatabase.GUIDToAssetPath(guid));
            var so = new SerializedObject(asset);
            so.FindProperty("networkAddress").stringValue = Host;
            var title = so.FindProperty("defaultTitle");
            if (title != null) title.stringValue = "Амулет дракона";
            so.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(asset);
            Debug.Log($"Server entry {AssetDatabase.GUIDToAssetPath(guid)} → {Host}");
        }
        AssetDatabase.SaveAssets();
    }

    /// <summary>Copies everything the editor logs during the build to build/editor-log.txt (CI turns its errors into annotations).</summary>
    static bool capturing;

    static void Capture()
    {
        if (capturing) return;
        capturing = true;
        var file = Path.Combine(Root(), "build", "editor-log.txt");
        Directory.CreateDirectory(Path.GetDirectoryName(file));
        Application.logMessageReceived += (text, stack, type) =>
        {
            try { File.AppendAllText(file, $"[{type}] {text}\n" + (type == LogType.Log ? "" : stack + "\n")); } catch (Exception) { }
        };
    }

    static void Run(BuildPlayerOptions options)
    {
        if (!TryRun(options)) EditorApplication.Exit(1);
    }

    static bool TryRun(BuildPlayerOptions options)
    {
        Capture();
        Directory.CreateDirectory(Path.GetDirectoryName(options.locationPathName));
        var report = BuildPipeline.BuildPlayer(options);
        Debug.Log($"Build {options.target}/{options.subtarget}: {report.summary.result}, {report.summary.totalSize} bytes, {report.summary.totalErrors} errors → {options.locationPathName}");
        return report.summary.result == BuildResult.Succeeded;
    }

    /// <summary>The Linux server: one build for every role (central, map spawn, map, database), chosen by command-line flags; runs with -batchmode -nographics.</summary>
    public static void BuildServer()
    {
        var scenes = Scenes();
        // A stack trace under every log line buries the server log; keep them for errors only.
        PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.None);
        PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.None);
        var options = new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = Path.Combine(Root(), "build", "Server", "tz-mmo-server.x86_64"),
            target = BuildTarget.StandaloneLinux64,
            // Development: symbols in the native stack if it crashes again.
            options = BuildOptions.Development,
        };
        // The ordinary player crashed every first frame on the VPS (07.10: SIGSEGV in
        // sdl::IsX11VideoDriver ← InputReadMousePosition — it polls the mouse with no display).
        // The Dedicated Server player has no window, input or rendering; it needs the
        // "Linux Dedicated Server Build Support" module in the image, and the editor started with
        // -standaloneBuildSubtarget Server (mmo.yml): the kit's types differ under UNITY_SERVER, and
        // a server build from a player-compiled editor fails on the serialized layouts.
        var playbackEngines = BuildPipeline.GetPlaybackEngineDirectory(BuildTargetGroup.Standalone, BuildTarget.StandaloneLinux64, BuildOptions.None);
        var variations = Path.Combine(playbackEngines, "Variations");
        Debug.Log("Linux variations: " + (Directory.Exists(variations) ? string.Join(", ", Directory.GetDirectories(variations).Select(Path.GetFileName)) : "none at " + variations));
        PlayerSettings.SetScriptingBackend(NamedBuildTarget.Server, ScriptingImplementation.Mono2x);
        options.subtarget = (int)StandaloneBuildSubtarget.Server;
        if (TryRun(options)) return;
        // No server module: the ordinary player, with the old input manager switched off so nothing polls the mouse.
        Debug.LogWarning("Dedicated Server build failed; falling back to the player with the Input System only.");
        var settings = new SerializedObject(AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/ProjectSettings.asset")[0]);
        settings.FindProperty("activeInputHandler").intValue = 1;
        settings.ApplyModifiedPropertiesWithoutUndo();
        PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.Mono2x);
        options.subtarget = (int)StandaloneBuildSubtarget.Player;
        Run(options);
    }

    public static void BuildAndroid()
    {
        var scenes = Scenes();
        PointAtServer();
        PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "ru.amulet.reborn.mmo");
        PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
        PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
        PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
        PlayerSettings.Android.forceInternetPermission = true;
        PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
        PlayerSettings.allowedAutorotateToPortrait = false;
        PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
        PlayerSettings.allowedAutorotateToLandscapeLeft = true;
        PlayerSettings.allowedAutorotateToLandscapeRight = true;
        PlayerSettings.Android.useCustomKeystore = false;   // the kit's settings name a keystore we do not have; debug-signed for now
        EditorUserBuildSettings.buildAppBundle = false;
        Run(new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = Path.Combine(Root(), "build", "Android", "tz-mmo.apk"),
            target = BuildTarget.Android,
            options = BuildOptions.None,
        });
    }

    static string Root() => Path.GetDirectoryName(Application.dataPath);
}
#endif
