# Bundled Miuix navigation (Android)

Source: compose-miuix-ui/miuix, miuix-nav-android 0.9.4-5c91d5e5-SNAPSHOT.
The exact published sources are bundled under the upstream Apache-2.0 license.
Android expect/actual declarations are resolved to ordinary Android functions.

Local changes: optional per-host predictive-back release policy, frozen for each
session; peak and applied (anchored) progress tracking. BiliPai enables this only for
video card returns. Floating cards can accept completion or cancellation signals on
release; explicit expansion back toward detail cancels. The opt-in adapter drains
queued progress before cancellation decisions, and stale session work cannot drive
or pop a newer gesture. Other routes retain the upstream cancellation/completion
behavior, and discrete back actions still complete immediately.

The app uses this module instead of the published miuix-nav artifact so the patch
is reproducible in CI and local builds. Keep the other Miuix modules at the matching
version when updating these sources.
