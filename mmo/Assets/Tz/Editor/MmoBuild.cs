// Builds the MMO client and server without anyone opening the editor (CI: .github/workflows/mmo.yml).
// Step 2: the demo scenes of MmoKitCE (Assets/Tz/BaseDemo, MIT) — init, home (login, characters)
// and one map — with the client pointed at our VPS.
#if UNITY_EDITOR
using System;
using System.IO;
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
        EnsureTextMeshPro();
        EditorBuildSettings.scenes = Array.ConvertAll(SceneList, s => new EditorBuildSettingsScene(s, true));
        PlayerSettings.companyName = "Amulet";
        PlayerSettings.productName = "Амулет дракона MMO";
        PlayerSettings.bundleVersion = "0.0.2";
        AssetDatabase.SaveAssets();
        return SceneList;
    }

    /// <summary>The demo's text uses TextMesh Pro's default font, which comes from its essential resources.</summary>
    static void EnsureTextMeshPro()
    {
        if (AssetDatabase.IsValidFolder("Assets/TextMesh Pro")) return;
        AssetDatabase.ImportPackage("Packages/com.unity.ugui/Package Resources/TMP Essential Resources.unitypackage", false);
        AssetDatabase.Refresh();
        Debug.Log("Imported TMP Essential Resources: " + AssetDatabase.IsValidFolder("Assets/TextMesh Pro"));
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
    static void Capture()
    {
        var file = Path.Combine(Root(), "build", "editor-log.txt");
        Directory.CreateDirectory(Path.GetDirectoryName(file));
        Application.logMessageReceived += (text, stack, type) =>
        {
            try { File.AppendAllText(file, $"[{type}] {text}\n" + (type == LogType.Log ? "" : stack + "\n")); } catch (Exception) { }
        };
    }

    static void Run(BuildPlayerOptions options)
    {
        Capture();
        Directory.CreateDirectory(Path.GetDirectoryName(options.locationPathName));
        var report = BuildPipeline.BuildPlayer(options);
        Debug.Log($"Build {options.target}: {report.summary.result}, {report.summary.totalSize} bytes, {report.summary.totalErrors} errors → {options.locationPathName}");
        if (report.summary.result != BuildResult.Succeeded) EditorApplication.Exit(1);
    }

    /// <summary>The Linux server: one build for every role (central, map spawn, map, database), chosen by command-line flags; runs with -batchmode -nographics.</summary>
    public static void BuildServer()
    {
        var scenes = Scenes();
        PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.Mono2x);
        Run(new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = Path.Combine(Root(), "build", "Server", "tz-mmo-server.x86_64"),
            target = BuildTarget.StandaloneLinux64,
            // Development: the server crashed natively at start (07.10) and a release player has no symbols to say where.
            options = BuildOptions.Development,
        });
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
