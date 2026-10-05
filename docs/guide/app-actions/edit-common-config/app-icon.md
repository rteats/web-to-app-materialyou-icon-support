# App Icon

The icon shown for your app in the app list and, after export, on the device launcher.

**Where:** the **Basic info** card at the top of the [Edit Common Config](/guide/app-actions/edit-common-config/) editor.

## Options

- **Pick an image** — choose an image from your device (`image/*`). The selected image becomes the app icon.
- **Choose from icon library** — pick a previously saved icon (`savedIconPath`). The library supports manual **Upload image**: pick from the gallery and it is saved to the library and auto-selected.
- **Default** — if you set no icon, a type-specific default icon is used.

## Notes

- At export, the icon is written into the APK's resources (the binary resource table is patched). See [APK Export Config](/guide/app-actions/edit-common-config/apk-export).


## Android themed icons

Exported APKs include a dedicated monochrome adaptive-icon layer for Android 13 and later. This is the layer Android uses when **Themed icons** are enabled on the launcher.

Open **APK Export → Themed monochrome icon** to configure it:

- **Automatic** — converts the normal app icon (including a website favicon fetched by WebToApp) into a foreground mask. The converter estimates the border background so an opaque favicon does not become a fully tinted square.
- **Contrast threshold** — controls how different a pixel must be from the detected background before it becomes foreground. For transparent artwork, the same control acts on alpha.
- **Invert foreground** — swaps the automatic foreground/background classification for artwork whose background is detected as the subject.
- **Import SVG** — uses a custom vector source instead of automatic conversion. WebToApp copies the SVG into app-private storage and parses paths plus common SVG shapes, groups, transforms, fills, and strokes. Scripted SVG and embedded raster images are rejected.

The SVG is normalized to the adaptive-icon safe zone and emitted as the monochrome drawable during APK generation. Android 8–12 continue to use the regular adaptive icon; the monochrome declaration is supplied through an API-33 resource override.
