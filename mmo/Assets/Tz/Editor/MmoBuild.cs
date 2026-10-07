// Builds the MMO client and server without anyone opening the editor (CI: .github/workflows/mmo.yml).
// Step 1: an empty bootstrap scene, to prove the framework (Assets/OpenMMORPG) compiles and builds
// for both targets on our CI. The real scenes come in step 2.
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
    const string Bootstrap = "Assets/Scenes/Bootstrap.unity";

    static string[] Scenes()
    {
        Directory.CreateDirectory("Assets/Scenes");
        if (!File.Exists(Bootstrap))
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
            EditorSceneManager.SaveScene(scene, Bootstrap);
        }
        EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(Bootstrap, true) };
        PlayerSettings.companyName = "Amulet";
        PlayerSettings.productName = "Амулет дракона MMO";
        PlayerSettings.bundleVersion = "0.0.1";
        AssetDatabase.SaveAssets();
        return new[] { Bootstrap };
    }

    static void Run(BuildPlayerOptions options)
    {
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
            options = BuildOptions.None,
        });
    }

    public static void BuildAndroid()
    {
        var scenes = Scenes();
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
