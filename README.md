# MarkerOwnership

Current version: **2.0.6**

MarkerOwnership assigns an owner to Dynmap markers created with `/dmarker` and enforces ownership rules for delete/update actions. It stores ownership in a YAML file so LiveAtlas can show marker owners.

## Features
- Captures ownership for new `/dmarker add` (and area/circle/line variants).
- Blocks delete/update if the player is not the owner (admins bypass).
- Uses Dynmap’s `markers.yml` directly (no Dynmap API dependency).
- Persists ownership in `plugins/MarkerOwnership/ownership.yml`.

## Requirements
- Paper/Spigot 1.21.x
- Dynmap installed and generating `plugins/dynmap/markers.yml`
- Java **21** for compilation

## Permissions
- `markerownership.admin` — bypass ownership checks

## Files
- `plugins/MarkerOwnership/ownership.yml`
- Reads Dynmap markers from `plugins/dynmap/markers.yml`

## Build
```bash
javac --release 21 -cp "paper-api-1.21.11.jar:java-libs/*" -d build/classes $(find src/main/java -name '*.java')
cp src/main/resources/plugin.yml build/classes/
jar cf MarkerOwnership-2.0.6.jar -C build/classes .
```

## Notes
- Ownership keys are saved as `type:setId:markerId`.
- Labels with HTML entities are normalized for matching (e.g. `&#39;` → `'`).

