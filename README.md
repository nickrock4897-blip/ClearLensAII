# ClearLens AI

ClearLens AI is an Android app that applies local contrast, color, and brightness adjustments to photos and videos.

## Features
- Choose a photo with Android's photo picker.
- Compare original and enhanced images side by side.
- Adjust enhancement strength.
- Save enhanced photos in **Pictures/ClearLens AI**.
- Choose a video and export an adjusted MP4 to **Movies/ClearLens AI**.
- Media processing runs locally on the phone.

## Get the APK from GitHub Actions
1. Open the repository's **Actions** tab.
2. Select **Build ClearLens AI APK**.
3. Open the latest run and wait until it finishes successfully.
4. Download the **ClearLensAI-debug-apk** artifact.
5. Extract the downloaded ZIP to get `app-debug.apk`.
6. Open the APK on your Android phone to install it. Android may ask permission to install apps from the browser or file manager.

If GitHub Actions is disabled for the repository, enable it first. You can also choose **Actions → Build ClearLens AI APK → Run workflow**.

## Limitations
This app uses conventional contrast, saturation, and brightness adjustments, not AI reconstruction. It cannot reliably restore information absent from the original file or repair condensation/physical damage inside a camera lens.

## Tech
- Kotlin and Android SDK
- Android Photo Picker
- Jetpack Media3 Transformer for video export
- GitHub Actions to build the debug APK
