# Design

## D1. Google Drive through SAF, not the Drive API
Drive API read access to arbitrary folders needs a restricted scope (verification and a security assessment). The system picker needs nothing. The cost: Google sign-in and upload to Google are out of scope.

## D2. Provider quirks
`OpenableColumns.SIZE` may be null or -1: the byte total becomes unknown and progress indeterminate. Reads can fail halfway (an offline Drive file not yet cached): the existing cancel/cleanup path deletes the half-copied book. No md5 exists for SAF sources, so the check is that the stream ended cleanly.

## D3. Testing
`FixtureDocumentsProvider` gains modes: size -1, delayed reads, exception after N bytes, `SecurityException` on open. Real Google Drive is not needed.
