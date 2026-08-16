# GlassWA

A root/LSPosed WhatsApp UI customization project focused on a glass/morphic interface.

## Architecture

- **App module**: settings/configuration UI and Xposed entry point.
- **Scanner mode**: first milestone records WhatsApp's live view hierarchy for the exact installed build.
- **Mappings**: version-specific fingerprints and class/method/resource mappings under `mappings/`.
- **Glass engine**: later applies translucent surfaces, blur, tint, elevation and dynamic wallpaper-aware styling.
- **Magisk/Zygisk packaging**: reserved for the runtime layer once the Java/UI mappings are stable.

## Target build

Initial mapping target: WhatsApp 2.26.31.77 (`263107700`).

## Development phases

1. Verify LSPosed attachment.
2. Capture WhatsApp view hierarchy.
3. Identify composer, message bubble, header and navigation nodes.
4. Add version fingerprint/mappings.
5. Apply a single visual mutation (composer).
6. Expand to the glass engine.
7. Add version adapters for future WhatsApp updates.

## Warning

This project is for research/customization. Do not use it to bypass account security, integrity checks, or other platform protections.
