# Gboard Multiswipe

**Gboard Multiswipe** brings two-finger swipe typing (**Nintype-style multi-touch gestures**) and a **2D Matrix suggestion bar** to Google Gboard.

This project builds on two foundations:
- Its Morphe patch and plugin structure is based on **[Gboard Patches](https://github.com/jasonwu1994/Gboard-patches)** by `jasonwu1994`.
- Its multi-touch gesture decoding algorithm comes from **Kinetica**, whose two-finger chorded typing workflow is inspired by **Nintype**.

Because Gboard Multiswipe is isolated into its own namespace and settings menu (**Multiswipe & Matrix**), you can use it standalone or combine it with upstream Gboard patch bundles without conflicts.

---

## Features

### 1. Nintype Multi-Touch Swipe
- **Simultaneous two-finger swiping**: Glide both thumbs at once to type faster with fluid multi-finger gestures.
- **Tap + Swipe chording**: Tap opening letters with one finger while swiping the rest of the word with the other (or vice versa).
- **Native Gboard dictionaries**: Uses Gboard's installed language dictionaries and supports accent/umlaut folding out of the box.
- **Material You trail styling**: Draws multi-touch trails with a distinct dynamic Material You accent color while keeping Matrix suggestions aligned with your active Gboard theme.
- **In-app tuning**: Adjust gesture timing, beam sensitivity, autospace behavior, and debug log export time windows directly via **Gboard Settings > Multiswipe & Matrix**.

### 2. Matrix Word Suggestions
- **Vertical suggestion wheels**: Swipe up or down inside the top suggestion bar to browse alternative word predictions and sentence variants in a 2D grid.
- **Sentence branch history**: Flick back through earlier sentence contexts and restore previous wording cleanly.
- **Jump-to-end shortcut**: Quickly jump back to the latest active suggestions with a single tap.

### 3. Package Rename (Side-by-Side Installation)
- Enabled by default so non-rooted phones can install the patched keyboard **alongside** official Play Store Gboard (`dev.srwi.com.google.android.inputmethod.latin`), complete with a customizable app name (`Gboard Multiswipe` by default).
- Can be disabled if you prefer an in-place replacement (requires root/mount or matching signatures).

---

## Installation

### Requirements
- **Morphe Manager** or **Morphe CLI**
- A clean copy of Gboard **`18.5.4.993608717-release-arm64-v8a`** (`.apk`, `.xapk`, `.apkm`, or `.apks`)

### Using Morphe Manager (Android)
1. Download the latest `patches-*.mpp` bundle from the [Releases](https://github.com/srwi/gboard-multiswipe/releases) page (or import the source repository in Morphe Manager).
2. Select a clean Gboard **`18.5.4.993608717-release-arm64-v8a`** installer file.
3. Choose the patches you want (**Matrix Word Suggestions**, **Nintype Multi-Touch Swipe**, and keep **Package Rename** enabled on non-rooted devices).
4. Patch and install the generated APK.
5. Enable the keyboard in **Android Settings > System > Keyboard > On-screen keyboard**, switch to it, and open **Gboard Settings > Multiswipe & Matrix** to customize your setup.

### Using `./patch-gboard.sh` (PC / Termux)
```bash
# Side-by-side install (recommended for non-rooted phones)
./patch-gboard.sh --rename -i /path/to/gboard-18.5.4.xapk -o ./gboard-multiswipe-patched.apk
```

---

## Developer & AI Agent Documentation

Build instructions, Morphe CLI examples, ADB/Termux deployment workflows, and module architecture details are kept in:
- [`AGENTS.md`](AGENTS.md) — Building, testing, patching, and portable ADB/Termux deployment
- [`patches/AGENTS.md`](patches/AGENTS.md) — Morphe patch registry, target bindings compiler, bytecode hooks, and runtime ABI
- [`extensions/AGENTS.md`](extensions/AGENTS.md) — Gboard runtime extension, Nintype/Kinetica decoder, Matrix suggestion bar, and unit tests

---

## Credits & License

- **Plugin & Patch Architecture**: [Gboard Patches](https://github.com/jasonwu1994/Gboard-patches) (`jasonwu1994`)
- **Multi-Touch Swipe Algorithm**: **Kinetica**, inspired by **Nintype**
- **Patcher Framework**: [Morphe](https://github.com/MorpheApp)

Licensed under **GPLv3**. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.
