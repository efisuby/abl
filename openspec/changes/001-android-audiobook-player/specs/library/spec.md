# Spec Delta

## Purpose

Holds the user's audiobook collection: import from local files, metadata and cover extraction and editing, series, statuses, search, filtering, sorting and removal from the device.

## ADDED Requirements

### Requirement: Import a book from a folder

The user MUST be able to pick a folder of audio files through the system file picker. The app copies the files into private storage and creates a single book where each audio file becomes a chapter.

#### Scenario: Folder with mp3 files

- **WHEN** the user picks a folder containing 12 mp3 files
- **THEN** a book with 12 chapters ordered by file name is created

#### Scenario: Folder without supported files

- **WHEN** the picked folder contains no mp3, m4a, m4b or aac files
- **THEN** the app shows an error and creates no book

### Requirement: Import a book from a file

The user MUST be able to pick a single m4b, m4a, mp3 or aac file. The file is copied into private storage and becomes a book, with chapters taken from the file's embedded metadata.

#### Scenario: M4B with embedded chapters

- **WHEN** the user imports an m4b with 24 embedded chapters
- **THEN** a book with 24 chapters is created and the chapter boundaries match the file

#### Scenario: File without embedded chapters

- **WHEN** the user imports an mp3 without embedded chapters
- **THEN** a book with one chapter covering the whole file is created

### Requirement: Multi-file book grouping

The app MUST treat each picked folder as one book, MUST NOT merge different folders, and MUST walk nested subfolders recursively while preserving file order by name.

#### Scenario: Book split into subfolders

- **WHEN** the picked folder contains subfolders `part-1` and `part-2` with audio files
- **THEN** all files belong to one book and chapters follow path order

### Requirement: Metadata extraction

The app MUST fill title, author, narrator, series, series number and cover from file tags (ID3 for mp3, MP4 tags for m4a and m4b) and MUST fall back to folder and file names when tags are absent.

#### Scenario: Tags are present

- **WHEN** a file is imported with title, artist and album tags and an embedded cover
- **THEN** the book receives those values and the cover

#### Scenario: Tags are absent

- **WHEN** the folder `Ivan Yefremov - The Bull's Hour` is imported without tags
- **THEN** title and author are derived from the folder name and the cover stays empty

#### Scenario: Series in tags

- **WHEN** a file carries a series as `TXXX:SERIES` and `TXXX:SERIES-PART` (ID3), `----:com.apple.iTunes:SERIES` and `SERIES-PART` (MP4), or the iTunes movement name and number (`MVNM`/`MVIN`, `©mvn`/`©mvi`), for example `Ересь Хоруса` and `2/10`
- **THEN** the book gets the series `Ересь Хоруса` and the series number 2

### Requirement: Speech-synthesis markup cleanup

The app MUST remove speech-synthesis markup from chapter titles and text tags on import: stress marks (`+` before a stressed Cyrillic vowel) and the control tags `[break]`, `[pause]`, `[pause:N]` and `[voice:…]`, including a `[voice:` tag cut off by the end of the text. Text that only resembles markup MUST be kept. A title left empty MUST get the usual fallback.

#### Scenario: Chapter titles from a TTS-generated book

- **WHEN** an m4b is imported whose chapter titles are `Д+эн Абн+етт Возвыш+ение Х+оруса; ` and `ПРИМ+АРХИ Х+орус[break]. прим+арх`
- **THEN** the chapters are titled `Дэн Абнетт Возвышение Хоруса` and `ПРИМАРХИ Хорус. примарх`

#### Scenario: Title consisting only of markup

- **WHEN** a chapter title is `[voice:/tmp/proc-1/voice.wav` with no closing bracket
- **THEN** the chapter gets the fallback title instead of an empty one

#### Scenario: Text that only looks like markup

- **WHEN** a title is `C++ для начинающих [Часть 1]`
- **THEN** it is kept unchanged

### Requirement: Metadata editing

The user MUST be able to change a book's title, author, narrator, series, series number and cover. Changes are stored locally and take part in synchronization.

#### Scenario: Changing the cover

- **WHEN** the user picks a new image for the cover
- **THEN** the cover updates in the library and on the book screen

### Requirement: Listening statuses

The app MUST show one of the statuses "not started", "in progress" or "finished" for every book. The "finished" status is set automatically when the last chapter ends and may be changed manually without resetting the saved position.

#### Scenario: Automatic completion

- **WHEN** playback of the last chapter reaches the end
- **THEN** the book receives the "finished" status

#### Scenario: Manual status change

- **WHEN** the user manually sets the "in progress" status for a finished book
- **THEN** the status changes and the saved position stays as it was

### Requirement: Search, filtering and sorting

The user MUST be able to search books by title, author and series, filter by status and source, and sort by title, author, date added and last listened time.

#### Scenario: Search by author

- **WHEN** the user types "Yefremov" into the search field
- **THEN** the list shows only books by that author

#### Scenario: Filter by source

- **WHEN** the user selects the "in the cloud" filter
- **THEN** the list shows books whose audio is not downloaded yet

### Requirement: Book series

The app MUST group books that share a series name and MUST show them inside the series in series-number order.

#### Scenario: Order inside a series

- **WHEN** the library contains three books of one series numbered 2, 1 and 3
- **THEN** they are shown in the order 1, 2, 3

### Requirement: Deleting a book from the device

The user MUST be able to delete a book from the device. Downloaded files and the local record are removed, and the original files in the cloud are left untouched.

#### Scenario: Deleting a downloaded book

- **WHEN** the user deletes a book downloaded from Disk
- **THEN** the audio files are removed from the device, the record disappears from the library, and the files on Disk remain
