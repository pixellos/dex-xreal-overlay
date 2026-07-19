# Redesign Menu to Cyberpunk Quickhacks Style

This plan outlines the UI refactoring of the main activity to match the Cyberpunk 2077 "Quickhack" menu aesthetic and updates the HUD to remove the battery percentage number.

## Proposed Changes

### [app](file:///C:/Dev/DeXOverlayPoC/app)

#### [MODIFY] [MainActivity.kt](file:///C:/Dev/DeXOverlayPoC/app/src/main/java/com/example/dexoverlay/MainActivity.kt)
- Create a custom `QuickhackBackground` drawable class to draw the notched/slanted boxes.
- Refactor `createQuickhackCard` to match the Cyberpunk layout:
    - Title on the top left.
    - "READY" status tag in a small box below the title.
    - RAM cost (placeholder number) on the right side.
    - Icon placeholder on the far right.
- Update the colors and fonts to be more "Quickhack" cyan/neon.
- Ensure the overall layout spacing matches the "Quickhack" menu feel.

#### [MODIFY] [OverlayService.kt](file:///C:/Dev/DeXOverlayPoC/app/src/main/java/com/example/dexoverlay/OverlayService.kt)
- Update the `batteryReceiver` to remove the percentage number (`$batteryPct%`) from the HUD display.
- Keep the battery segments (▮▮▮▮▮) and the charging icon (⚡).

## Verification Plan

### Automated Tests
- Build and run the app to verify the new UI layout.

### Manual Verification
- **Menu Redesign**: Verify the "Quickhack" cards in `MainActivity` have the notched background and correct layout.
- **HUD Update**: Start the HUD and verify that the battery indicator shows segments only, without the percentage number.
- **Interactions**: Ensure SeekBars and RadioButtons within the new "Quickhack" cards still function correctly.
