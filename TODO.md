# TODO — review later

From the code review of 2026-10-13. The 8 confirmed issues are fixed in this working tree.
`bb test` runs 175 tests and 869 assertions: 0 failures. `bb lint` exits 0.

This file lists what is left. Each item is open, not done. No item below has a fix in this tree.

## 1. Open questions for the owner

These need a decision, not code.

1. `core/run-turns` now returns `:status :incomplete` when the provider marked the reply
   incomplete. Confirm the interactive loop should print the note and continue, and that
   `-p` mode must exit 1.
2. `ref` state is saved and restored. Confirm an `agent` should stay out of the checkpoint,
   because an agent has a live thread and a queue.
3. `printable?` now caps the print at 1000 elements and 20 levels. A value past the cap is
   refused as state and bba warns. Confirm that refusal is better than truncation.
4. `printable?` runs on the caller thread, outside the adapter timeout. Confirm a bound of
   1000 is enough, or move the check into the attempt timeout.
5. `store/valid-name?` now reserves the name `temp`. A world folder named `temp` that exists
   on disk becomes unreachable by `/world temp`. Confirm, or add a migration.
6. A temporary world is now deleted on a switch away. Confirm the deletion must warn, and
   must not fail the switch.

## 2. Suspected defects that are not confirmed

These came from agent text. The review did not test them. Test each one first.

1. `store/fork!` treats a negative revision as an empty fork, because it tests `(pos? n)`.
   `/world fork foo -1` gives an empty world and no error.
2. `world/switch!` and `world/create!` catch `clojure.lang.ExceptionInfo` only. A plain I/O
   failure after `open!` resets the state and escapes.
3. `world/create!` returns `{:ok dir}` for a new world but `{:ok {..}}` for a temporary one,
   against its own docstring. Callers read `(:name i)`.
4. `ui/enable!` reads `System/getenv "NO_COLOR"`. An empty value is truthy in Clojure, so an
   empty `NO_COLOR` turns colour off. The convention wants a non-empty value only.
5. `mcp/clip-name` cuts a tool name at 64 characters. `slug` maps every other character to
   `_`, so `a.b` and `a_b` give one name and one tool silently replaces the other.
   Confirmed by probe: `(tool-name "a.b" "x.y")` and `(tool-name "a_b" "x_y")` are equal.
6. `skills/scope-dirs` reads `System/getProperty "user.home"` and `System/getenv`, not
   `:home` and `:env` from ctx. `BBA_HOME` does not move the user skill roots, so tests are
   not hermetic.
7. `mcp/register-all!` runs only on the `:session-start` hook. `/reload` does re-emit that
   event, so the tools return. Confirm `--no-extensions` and a later `/new` behave the same.
8. `tui/add-history` appends with no cap. `load-history` caps at 500 on read, so the file and
   the in-memory vector grow without a limit in one long session.
9. `toolview/detail-md` and `main/detail-file` write one detail file per tool call under
   `.bba/`. Confirm an age or count limit, or the folder grows without a bound.
10. `world/rollback!` calls `publish-snapshot!` with `{:state (:state target)}`. Confirm the
    `:unprintable` list of the target revision is carried over.

## 3. Test-coverage gaps

Each line names a behaviour with no test. Add the test before the fix.

### world.clj

- `error-result` `:data` branch: the `ex-data` value, bounded to 1000 characters.
- `new-vars` and `redefined` run for `:execute` only. A `develop` that removes a var, and a
  `:roots` entry whose var is gone, are unasserted.
- `replay!` when a logged form throws during a replay. The state after a partial replay is
  untested.
- `bounded`: the `*print-length* 100` and `*print-level* 10` caps. Test `s10` covers
  `max-chars` only.
- `run-checks`: a form that throws, not a form that returns false. Assert `:reason
  :invariant` and that `:failed` carries `:error`.
- The mixed removal list: one valid name and one unknown. The error lists both.
- A start-up invariant warning for a function that does not exist yet (R-WORLD-22).

### world_store.clj

- `read-adapter`: a `world.edn` that is not a map.
- `current` on a corrupt `CURRENT`.
- `next-rev` when orphan revisions sit above `CURRENT`.
- `publish!` on a duplicate revision.
- `migrate!`: the in-use throw, and the both-folders case.
- `fork!`: an unknown revision, and `n = 0`.
- `delete-tmp!`.
- `log-op!` with `BBA_DEBUG` set.
- `lock!`: a dead pid, a malformed `LOCK`, and same-process re-entry.
- `unlock!` by a different pid leaves the `LOCK` in place.
- `ops.jsonl` and `ACTIVE` have no test at all (R-STORE-11, R-WORLD-12).

### core.clj

- `session-world` with a damaged line: the `catch` branch is never asserted.
- A handler that returns a map without `:content`.
- The `InterruptedException` re-throw in `run-tool`.
- A `:tool-call` hook that returns `{:block true}` with no reason.
- `parse-lines`: a damaged line in the middle, and a damaged first line.

### Commands and the TUI

- `/status`, `/history` and `/describe` output (R-CMD-6 to R-CMD-8).
- `/abort`.
- `Ctrl-O` paging of a very long tool result.

### Core extensions

- No dedicated test file exists. SPEC §8 admits this. About 1300 lines depend on indirect
  calls.
- MCP: `server/discover` to `initialize` fallback, `_meta` on a modern call, `nextCursor`
  paging, a repeated cursor, 64-character clipping, and a `tools/call` retry.
- MCP: `/reload` restores the `mcp__*` tools.
- Compaction: string `:content`, `pair-safe-tail`, a throwing summarizer, the cache, and the
  protected first message in `plan`.
- Extensions: unloader order (newest first), and a throwing unloader.
- Skills: trust gating, and name-collision precedence.
- `--no-extensions` with a core extension.

## 4. Documentation drift

1. `SPEC.md` §2.3 per-file line counts. Done in this tree: the table matches the real
   counts and now lists `src/bba/toolview.clj`. `bb test` asserts no line counts,
   so a future edit can drift again. Consider a test that checks the table.
2. `README.md` "Limits" and `SPEC.md` D-1 now agree on 3600000 ms. Re-read D-1 after any
   further change to the timeout.
3. `SPEC.md` R-WORLD-3. Done in this tree: `temp` is now in the rejected list.
4. `SPEC.md` R-EVAL-4, R-EVAL-6, R-EVAL-7 and R-EVAL-19. Done in this tree: they now
   say interned, not public, and cover private vars, `defn-` and refs.
5. `SPEC.md` R-EVAL-21a. Done in this tree: it records the bounded state print.
   Confirm the bound of 1000 elements and 20 levels is the right policy.
6. `README.md` "Commands" lists `/abort`. `SPEC.md` R-CMD and §5.3 do not. Fix one side.
7. `SPEC.md`: done in this tree. R-TUI-9a now covers the one-line tool call, and the
   module map lists `src/bba/toolview.clj`. The `Ctrl-O` pager still has no
   requirement; add one, or drop the README paragraph.
8. `README.md` "Options" documents `BBA_PRICE`. `SPEC.md` §5.2 omits it.
9. `world/system-prompt` tells the model about `/reload`. Confirm the text matches the
   extension API in `ext.clj` after any change.

## 5. Working-tree hygiene

These paths are untracked. Decide each one: keep, commit, or delete.

- `test/review_regression_test.clj` — kept, and now in the `bb test` task. Commit it.
- `tmp/` — probe scripts and logs from the review. Delete.
- `try/` — video files from an earlier task. Delete or move out of the repo.
- `ai-agent-control-explainer.html` and `ai-agent-proposal-explainer.html` — review artefacts.
- `.playwright-cli/` — a tool cache. Add it to `.gitignore`.

## 6. Not a defect

- The removal list is all-or-nothing. `SPEC.md` R-EVAL-4 requires exactly that: an unknown
  name fails the whole removal and unmaps nothing. It only lacked a test.
- `/reload` does not lose the `mcp__*` tools. `main.clj:285` calls `core/load-extensions!`
  and then `ext/emit :session-start`, and `mcp.clj:336` registers every server on that hook.
  Verified by probe.