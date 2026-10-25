# Spike: m4b chapter and tag extraction

Task 2.4 of change `001-android-audiobook-player`. Date: 2026-10-25. Media3 1.11.1, API 36 emulator (x86_64).

## Question

Which of the chapter sources from design D5 actually work on real files, and in what order should the importer try them?

## Sample

| File | Size | Duration | Chapter storage | Tag storage |
| --- | --- | --- | --- | --- |
| `01._Возвышение_Хоруса.m4b` | 970 MB | 12 h 39 min | `chpl` (75) + QuickTime text track (75) | QuickTime `keys`/`ilst` (`mdta`), `covr` |
| `test_50_…e2a.sml.m4b` | 4.2 MB | 3 min 24 s | `chpl` (1) + text track (1) | `mdta`, `covr` |
| `[voice__…].m4b` | 0.6 MB | 26 s | `chpl` (1) + text track (1) | `mdta`, `covr` |
| `eres_horosa.m4a` | 348 MB | 10 h 28 min | none | none |
| fixtures `chapters*.m4b` (ffmpeg `-f ipod`) | 26 KB | 6 s | both / text track only / `chpl` only | iTunes `ilst` |
| fixture `mdta-tags.m4b` (ffmpeg `use_metadata_tags`) | 10 KB | 2 s | none | `mdta` |

All three real m4b files come from ebook2audiobook, so the sample does not include an Audible or iTunes encoder. In every file that carries both sources, `chpl` and the text track agree on boundaries and titles exactly (titles differ only by trailing whitespace).

Device results come from `M4bSpikeTest`. It is skipped unless files are pushed to `/data/local/tmp/abl-spike/`.

## Findings

1. **Media3 `MetadataRetriever` (module `media3-inspector`) returns Nero `chpl` chapters** as `androidx.media3.extractor.metadata.Chapter` entries on the audio track format. The 970 MB book yields 75 chapters in ~130 ms, because only `moov` is read.
2. **QuickTime chapter tracks are not visible through `MetadataRetriever`.** `Mp4Extractor.readQuickTimeChapters` runs during sample reading, that is during playback, not during preparation. A file that has only a text track (`chapters-qt-only.m4b`) gives no chapters through Media3. That file needs our own reader `Mp4ChapterTrackReader`, which resolves `tref/chap` → `text` track → `stts`/`stsz`/`stsc`/`stco|co64` → 16-bit-length UTF-8/UTF-16 samples. It takes 6 ms on the 970 MB book.
3. **Nero `chpl` needs no parser of our own**, since Media3 already reads it (finding 1). Levels 1 and 2 of the D5 cascade therefore collapse into a single Media3 step.
4. **`MediaMetadataRetriever` misses QuickTime `keys` metadata.** ebook2audiobook writes title and artist as `mdta` keys (`title`, `artist`, …) instead of iTunes atoms (`©nam`, `©ART`). The platform retriever returns null for both, and Media3's `MediaMetadata` mapping ignores these keys too. `Mp4Tags` (originally `Mp4QuickTimeKeys`) fills the gaps. Cover art (`covr`) is read by the platform correctly.
5. **`MediaMetadataRetriever` cannot read files labelled `shell_data_file`, even through a descriptor:** SELinux blocks the media server. Imported books live in `filesDir`, so this does not affect the app. The reader still opens a descriptor in the app process instead of passing a path. The spike copies files into the app cache first.
6. **Chapter titles from TTS-generated books contain markup:** stress marks (`Т+о`), `[break]`, `[voice:…]` and very long first sentences. The importer strips this markup with `TtsMarkup` (requirement "Speech-synthesis markup cleanup", task 2.7); long titles are kept and left to the UI to ellipsize.
7. **An unreferenced `text` track is not a chapter track.** Only a track named by `tref/chap` counts, because an unreferenced one may be subtitles.

## Decision

The importer's chapter cascade (`CascadingChapterReader`) is:

1. `Media3ChapterReader` — Nero `chpl` (plus whatever Media3 adds to track metadata later);
2. `Mp4ChapterTrackReader` — QuickTime chapter track;
3. one chapter for the whole file.

The first reader that returns more than one marker wins. A single marker is used only for its title. Markers become spans in `ChapterBoundaries.build`: sorted, deduplicated, clipped to the file, with the first span starting at 0 and each span ending where the next begins.

Tags: `MediaMetadataRetriever`, then QuickTime `keys` for m4a/m4b/mp4, then the folder or file name (`Author - Title`).

## Open points

- The sample contains no Audible/iTunes-encoded m4b. If one shows chapters only in a form neither reader handles, it degrades to one chapter without losing playback.
- Series and series number: neither the platform retriever nor Media3 exposes a series tag, and no file in the sample carries one.

## Verification

- Unit: `ChapterBoundariesTest`, `Mp4ChapterTrackReaderTest` (exact boundaries on the fixtures, truncated and garbage files, cascade order), `Mp4TagsTest`.
- Instrumented: `SafImportTest.m4bWith*` — all three chapter variants import as 3 chapters at 0 / 2000 / 4500 ms; the `mdta` fixture gets its title and author.
- Real files: `M4bSpikeTest` — 75/75 chapters, `agree=true` between both readers, title and author present.
