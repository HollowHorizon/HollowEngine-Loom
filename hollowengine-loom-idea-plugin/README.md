# HollowEngine Loom IntelliJ Plugin

This is a standalone IntelliJ Platform plugin project for `HollowEngine Loom`.

Plugin id: `ru.hollowhorizon.hollowengine-loom`

## Current Scope

- Java and Kotlin inspections for APIs annotated with `multiversion.api.RequiresApi`
- Recognizes direct guards:
  - `if (Constants.is(1211)) { ... }`
  - `if (Constants.MINECRAFT_VERSION == 1211) { ... }`
- Recognizes enclosing declarations annotated with `@RequiresApi`
- Suppresses diagnostics when the API is available on every configured multiversion target

The current implementation is intentionally conservative. It does not try to prove arbitrary boolean helpers, but it does handle the direct `Constants` guard shapes used by the Gradle-side validator.

## Running

From this directory:

```powershell
.\gradlew.bat runIde
```

## Project Structure

- `src/main/java`: plugin code
- `src/main/resources/META-INF/plugin.xml`: plugin registration
- `src/main/resources/inspectionDescriptions`: inspection docs shown by IntelliJ

## Next Steps

- Add quick-fixes for wrapping code in `if (Constants.is(...))`
- Add support for `Constants.isAtLeast(...)` / `isAtMost(...)`
- Add Kotlin analysis
- Reuse metadata from the Gradle-side validator instead of only PSI inspection
