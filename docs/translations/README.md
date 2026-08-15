# Translation sync

Game strings live in `.properties` bundles under `tt/src/main/resources/com/oddlabs/tt/`.
Translations are edited in a Google Sheet; a GitHub Actions workflow keeps the two in
sync and opens a PR when translations change. It runs on every push to `main` that
touches `.properties` files, on a weekly schedule (Mondays 09:00 UTC), and on demand via Run workflow.

## How it works

Every sync is a two-way merge, never a blind overwrite:

- Keys and English text always come from the code. New keys appear in the sheet as new
  rows with blank translation cells; rows for deleted keys are dropped (and reported).
- Translation cells merge value by value. A value present on only one side wins. If both
  sides changed the same cell, the sheet wins and the conflict is listed in the PR body.
- Empty cells mean "untranslated"; the game falls back to English at runtime.
- Locale files are rewritten only when their content actually changes, in the English
  base file's key order.

### Machine translation drafts

When the sync pushes rows to the sheet, empty translation cells get a `GOOGLETRANSLATE`
draft formula and a yellow tint. Non-empty cells are never touched, so human-authored
translations are never overwritten by machine output. On the next sync the computed
draft flows into the game and is frozen into plain text in the sheet; the yellow tint
stays, so machine-drafted strings remain identifiable until a human reviews the cell
(fix the text if needed and clear the tint).

So a new key added in code reaches translated `.properties` files in two runs: the
first run adds the row and the draft formulas; the next one (the weekly run, or a manual
Run workflow) imports the computed drafts and opens the PR. Cells that are still
loading, errored, or where the draft mangled a `{0}`-style placeholder are exported as
empty rather than shipped broken.

The Gradle tasks are defined in `tt/translations.gradle.kts` and work offline with any
CSV, so the manual flow (download sheet as CSV, run the task, re-import) always works too:

```
./gradlew :tt:exportTranslations [-PsheetCsv=path/to/sheet.csv]   # code (+ sheet) -> build/translations.csv
./gradlew :tt:importTranslations -PsheetCsv=path/to/sheet.csv    # CSV -> locale .properties files
```

## One-time setup

1. Create an empty Google Sheet.
2. Extensions > Apps Script, replace the default code with `sheet-sync.gs` from this
   directory, and save.
3. In the Apps Script editor: Project Settings > Script Properties > add property
   `SYNC_TOKEN` with a long random value (e.g. from a password generator).
4. Deploy > New deployment > type "Web app", Execute as: **Me**, Who has access:
   **Anyone**. Authorize when prompted and copy the web app URL (ends in `/exec`).
   Access is gated by the token, not the link.
5. In the GitHub repo: Settings > Secrets and variables > Actions > add two repository
   secrets:
   - `TRANSLATIONS_SHEET_URL` = the web app URL
   - `TRANSLATIONS_SHEET_SYNC_TOKEN` = the token from step 3
6. Run the "Sync Translations" workflow once by hand (Actions > Sync Translations >
   Run workflow). It sees the empty sheet, seeds it with all current strings, and from
   then on runs weekly.

If you later edit the Apps Script, use Deploy > Manage deployments > edit > new version;
creating a brand-new deployment changes the URL and the secret must be updated.

## Adding a language

1. Add the locale code and column name to `locales`/`localeNames` in
   `tt/translations.gradle.kts`.
2. Add the column name and Google Translate code to `LANGS` in the sheet's Apps Script
   (Deploy > Manage deployments > edit > new version; the URL stays the same). Without
   this the column still syncs, it just gets no machine drafts.
3. Game support is separate: add the locale to `tt/src/main/java/com/oddlabs/tt/gui/Languages.java`
   (code + native name) and a flag icon to the skin, and check the font atlases cover the
   language's characters (`assets/build.gradle.kts` bakes codepoints 0-1199; Latin-script
   languages are fine, other scripts need the range or `additional_chars` extended).

The sheet itself needs no manual migration: columns are matched by header name, a
missing column just reads as untranslated, and the next sync writes the sheet back with
the new column in place (machine drafts included). Translators can also start a language
by hand: a column whose header is not a registered language is passed through the sync
untouched (kept in the sheet, no machine drafts, not imported into the game) until the
language is registered as above, using the same column name. Renaming a registered
column is not supported; its values would stop syncing and be treated as an unregistered
language instead.

## Translating

Open the sheet and edit any translation cell; the next run imports the change and opens
a PR. Yellow cells are unreviewed machine drafts: filter a language column by fill
color to work through them, correct the text where needed, and clear the tint to mark
the cell human-reviewed.
