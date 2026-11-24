# Summit: rules for agents

## rekordbox is off-limits except through `summit.rbguard`

- Every read or change of rekordbox's own files (its library `master.db`, anything under `~/Library/Pioneer` or
  `~/Library/Application Support/Pioneer`) goes through `src/summit/rbguard.py`: `snapshot()` to read, `write()` /
  `restore()` to change. No other module may import pyrekordbox or name those paths; `tests/test_rbguard.py` fails if
  one does. Run `uv run --with pytest pytest tests` after touching anything rekordbox-related.
- The lock is the user's. It is set from the app (Tools › rekordbox My Tags) and defaults to locked. Never unlock it
  yourself: don't call `POST /api/rekordbox/lock` with `locked: false`, edit `data/rekordbox_lock.json`, or call
  `rbguard.set_locked(False)`. Locking (`summit rekordbox lock`) is always fine.
- Never call the sync or restore endpoints, or `rbsync.write`, against the real library while testing. Test against
  a copy of `master.db` in a scratch directory.
- Never write rekordbox's Rating (stars) or Colour.

## UI first

Every feature must be usable, visible and debuggable from the app (trigger, progress, and a "why" view). A CLI-only
feature counts as missing.

## Don't break usability (desktop and phone)

A UI change must not cover, push off screen or squash anything else, at desktop width (~1440 px) or phone width
(~390 px). Before calling a UI change done, screenshot the affected pages at both widths (headless Chrome against a test
server) with the help layer off and on, and look at them: nothing overlapping controls, nothing off screen, no
horizontal page scroll.

- Anything that floats over other UI (hints, help boxes, side lists, panels) must be hideable: a × or a toggle,
  remembered across reloads. Only things the user must act on (a confirm dialog) may stay put.
- Popups close on Esc and on a click outside.
- No keyboard shortcuts in the Tag view: answers are clicked, so a stray key never
  records one.
- Don't reuse an existing `Summit` (`H.*`) name for something new: `grep` for it first. (`H.help` was already the
  help-layer switch; overwriting it forced every hint on.)

## Reload the app after every change

Don't leave the user to reload. After changing anything under `src/summit/web/`, run `uv run summit reload`; after
changing Python (server, pipeline, anything the service imports), run `uv run summit reload --server`, which restarts
the service and waits for the window to come back. Both print whether the open window(s) actually reloaded (and open
Summit.app if no window is open). If it reports a window that didn't pick up the change, fix that before saying
you're done.
