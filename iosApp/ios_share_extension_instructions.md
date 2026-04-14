# Instructions for Integrating the iOS Share Extension

The core source code and configuration for the iOS Share Extension have been created. To finish the integration, you need to use Xcode to create the extension target and link the generated files.

**This is a required manual step because creating new targets and linking frameworks in an Xcode project is a complex process that must be done through the Xcode graphical interface.**

### 1. Open the Xcode Project

First, open the main iOS project in Xcode. You can do this by running the following command in your terminal:

```sh
open AIDetectorApp/iosApp/iosApp.xcodeproj
```

### 2. Create a New Share Extension Target

1.  In Xcode, with your project open, go to the menu bar and select **File -> New -> Target...**.
2.  A sheet will appear. In the "Application" section for iOS, select the **Share Extension** template.
3.  Click **Next**.
4.  Name the product `ShareExtension` (or a name of your choice).
5.  Ensure the "Language" is set to **Swift**.
6.  Click **Finish**.
7.  A dialog will appear asking if you want to "Activate the new scheme". Click **Activate**.

### 3. Replace Template Files with Generated Files

Xcode has just created a new folder in your project navigator (e.g., `ShareExtension`) with a template `ShareViewController.swift` and an `Info.plist`. We need to replace these with the files that were already generated.

1.  Locate the `ShareExtension` folder that was created earlier in the file system at `AIDetectorApp/iosApp/ShareExtension/`.
2.  Drag the `ShareViewController.swift` and `Info.plist` files from this folder into the `ShareExtension` group in your Xcode Project Navigator.
3.  When prompted, make sure to:
    *   Check **"Copy items if needed"**.
    *   Select **"Create groups"**.
    *   Ensure the target is set to your new `ShareExtension` target.
4.  Xcode will warn you that files with the same name already exist. Choose to **Replace** them.

### 4. Link the Shared KMM Framework

The Share Extension needs to access the `AIDetectorApi` from your shared Kotlin code. To do this, you must link the `shared.framework`.

1.  In the Project Navigator, select the top-level project file.
2.  In the main editor window, select your new `ShareExtension` target from the list of targets.
3.  Go to the **General** tab.
4.  Scroll down to the **"Frameworks, Libraries, and Embedded Content"** section.
5.  Click the **"+"** button.
6.  In the list that appears, find and select `shared.framework`.
7.  Click **Add**.

### 5. Build and Run

You have now configured your Share Extension. To test it:

1.  Select the `ShareExtension` scheme from the scheme selector at the top of the Xcode window.
2.  Choose an iOS Simulator or a connected device.
3.  Click the **Run** button (or `Cmd+R`).
4.  Xcode will ask you to choose an app to run. Select **Photos**.
5.  Once the Photos app is running on the simulator/device, select a photo.
6.  Tap the **Share** button.
7.  In the share sheet, you should see your app's icon, labeled "ShareExtension" (or the name you chose). Tap it.
8.  Your Share Extension's UI should appear and begin analyzing the image.
