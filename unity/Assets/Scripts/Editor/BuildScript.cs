// Builds the Unity client without anyone opening the editor (CI: .github/workflows/unity.yml,
// GameCI with -executeMethod BuildScript.BuildAndroid). The only scene is empty: Boot makes the
// camera, the canvas and the screens from code, so nothing has to be laid out by hand.
#if UNITY_EDITOR
using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;

public static class BuildScript
{
    const string ScenePath = "Assets/Scenes/Main.unity";

    static void Prepare()
    {
        Directory.CreateDirectory("Assets/Scenes");
        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
        EditorSceneManager.SaveScene(scene, ScenePath);
        EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };

        PlayerSettings.companyName = "Amulet";
        PlayerSettings.productName = "Амулет дракона";
        PlayerSettings.bundleVersion = "0.1.0";
        // Landscape either way round (owner 07.10, like his reference screenshot).
        PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
        PlayerSettings.allowedAutorotateToPortrait = false;
        PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
        PlayerSettings.allowedAutorotateToLandscapeLeft = true;
        PlayerSettings.allowedAutorotateToLandscapeRight = true;
        PlayerSettings.SplashScreen.show = false;
        PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "ru.amulet.reborn.unity");
        PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
        PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
        PlayerSettings.Android.forceInternetPermission = true;
        PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
        var icon = AssetDatabase.LoadAssetAtPath<Texture2D>("Assets/Resources/Art/brand/icon.png");
        if (icon != null) PlayerSettings.SetIcons(NamedBuildTarget.Unknown, new[] { icon }, IconKind.Any);
        // The old Input Manager: the screens use StandaloneInputModule and the project has no Input System package.
        var settings = AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/ProjectSettings.asset");
        if (settings.Length > 0)
        {
            var so = new SerializedObject(settings[0]);
            var input = so.FindProperty("activeInputHandler");
            if (input != null) { input.intValue = 0; so.ApplyModifiedProperties(); }
        }
        AssetDatabase.SaveAssets();
    }

    public static void BuildAndroid()
    {
        Prepare();
        // unity/build/Android/amulet-unity.apk, next to Assets (the workflow uploads it from there).
        var output = Environment.GetEnvironmentVariable("TZ_APK")
                     ?? Path.Combine(Path.GetDirectoryName(Application.dataPath), "build", "Android", "amulet-unity.apk");
        Directory.CreateDirectory(Path.GetDirectoryName(output));
        EditorUserBuildSettings.buildAppBundle = false;
        var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
        {
            scenes = new[] { ScenePath },
            locationPathName = output,
            target = BuildTarget.Android,
            options = BuildOptions.None,
        });
        Debug.Log($"Build: {report.summary.result}, {report.summary.totalSize} bytes, {report.summary.totalErrors} errors → {output}");
        if (report.summary.result != BuildResult.Succeeded) EditorApplication.Exit(1);
    }
}
#endif
