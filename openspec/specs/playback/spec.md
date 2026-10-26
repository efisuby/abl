# playback Specification

## Purpose
Plays an audiobook chapter by chapter, exposes playback session controls, saves the listening position and lets the user add timecoded bookmarks.

## Requirements

### Requirement: Continuous chapter playback

The app MUST play chapters back to back and MUST advance to the next chapter automatically, including between chapters inside a single m4b file, without stopping and without user action.

#### Scenario: Chapter ends in an mp3 folder

- **WHEN** playback of chapter 3 from an mp3 folder finishes
- **THEN** chapter 4 starts immediately and the notification shows its title

#### Scenario: Chapter ends inside an m4b

- **WHEN** playback reaches a chapter boundary inside an m4b
- **THEN** playback continues from the next chapter of the same file

### Requirement: Playback controls

The user MUST be able to pause and resume playback, seek 15 seconds back and forward, move to the previous and next chapter, and seek by dragging the progress bar.

#### Scenario: Seeking by 15 seconds

- **WHEN** the user taps "back 15 seconds" at 40 seconds into a chapter
- **THEN** playback continues from 25 seconds

#### Scenario: Moving to the next chapter

- **WHEN** the user taps "next chapter"
- **THEN** playback starts from the beginning of the next chapter

### Requirement: Playback speed

The user MUST be able to change the speed in the 0.5–3.0× range in steps of no more than 0.1 without changing voice pitch. The chosen speed is kept between app launches.

#### Scenario: Speed is kept

- **WHEN** the user sets 1.5× and restarts the app
- **THEN** the new session starts at 1.5×

### Requirement: Sleep timer

The user MUST be able to start a sleep timer for 5, 10, 15, 30, 45 or 60 minutes, or until the end of the current chapter. When the timer expires, playback MUST pause.

#### Scenario: Timer until end of chapter

- **WHEN** the user chooses "until end of chapter"
- **THEN** playback stops at the chapter boundary and does not advance further

### Requirement: Position saving

The app MUST save the position at least once every 5 seconds of playback, and also on pause, on going to the background and at chapter end, and MUST restore it the next time the book is opened.

#### Scenario: Restore after restart

- **WHEN** the user pauses at minute 37, closes the app and opens the book again
- **THEN** playback starts at minute 37 of the same chapter

#### Scenario: Forced termination

- **WHEN** the app is killed while playing
- **THEN** after launch the position differs from the last saved one by no more than 5 seconds

### Requirement: Continue listening

The home screen MUST show the most recently played book with its chapter title, position and a continue button; tapping it opens the player at the saved position.

#### Scenario: Last book card

- **WHEN** the user opens the app after listening
- **THEN** the book with its chapter and time is shown at the top, and tapping it resumes playback

### Requirement: Background playback

The app MUST keep playing while it is in the background or the screen is locked, and MUST publish a media session and a notification with the cover, chapter title and buttons for pause, seeking and chapter navigation.

#### Scenario: Screen locked

- **WHEN** the user locks the screen during playback
- **THEN** audio continues and playback controls are available on the lock screen

#### Scenario: Stopping from the notification

- **WHEN** the user taps pause in the notification
- **THEN** playback stops and the position is saved

### Requirement: Bookmarks

The user MUST be able to add a bookmark at the current position with an optional note, view the book's bookmark list and jump to a bookmark's timecode.

#### Scenario: Jumping to a bookmark

- **WHEN** the user taps a bookmark at 1:12:30
- **THEN** playback starts from that position in the corresponding chapter

#### Scenario: Deleting a bookmark

- **WHEN** the user deletes a bookmark
- **THEN** it disappears from the list and does not come back after synchronization
