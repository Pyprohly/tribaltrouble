# Copilot instructions

This repository's agent guidance lives in [AGENTS.md](../AGENTS.md) at the root, with
module-specific notes in `common/AGENTS.md`, `tt/AGENTS.md`, and `server/AGENTS.md`. Read
those before suggesting changes.

The rules most often broken:

1. The project targets **Java 26** and formatting is enforced by Spotless. Run
   `./gradlew spotlessApply`.
2. **Never bump `API_VERSION` or `SIM_VERSION`** in
   `common/src/main/java/com/oddlabs/util/Compatibility.java`. Flag that a bump looks needed
   and let a maintainer decide.
3. Gameplay simulation code (`tt/.../model`, `behaviour`, `pathfinder`, `landscape`,
   `player`, `trigger`) must stay deterministic: no wall-clock time, no unseeded randomness,
   no hash-order iteration, no branching on local settings or hardware.
4. **Creative content stays human.** No AI-generated translations, art, or sound, ever. New
   user-visible strings go in the **English `.properties` base file only**; locale files are
   written by human translators. Generated code is a draft until a person reviews it.
5. Keep diffs minimal and comments sparse. Match the surrounding code instead of modernizing
   it in passing.
6. Never add yourself as a commit author, co-author or trailer. Commits belong to the person
   running you.
