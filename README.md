# bba — a babashka agent harness (spike)

A small, terminal-first coding agent in babashka, modelled on [pi](https://pi.dev/).
The command is `bba` so it never shadows the babashka `bb` binary.

- Core: agent loop + 4 tools (`read`, `write`, `edit`, `bash`), under 500 lines in `src/bba/`.
- Everything else is an extension: a plain Clojure file that uses `bba.ext`.
- Self-improvement: the agent writes a file to `.bba/extensions/`, you type `/reload`, and the new tool or command works in the same session.

## Run

```bash
export ANTHROPIC_API_KEY=...          # needed for the default Anthropic provider
bb/bin/bba                            # interactive
bb/bin/bba -p "list the files here"   # print mode: prints the answer, exit 0 / 1
cd bb && bb agent -p "..."            # same, as a bb task
cd bb && bb test                      # offline tests (fake provider, no key)
```

Options: `--max-turns N` (default 50, or `BBA_MAX_TURNS`), `--no-extensions`.
Env: `BBA_MODEL` (default `claude-opus-5-5`), `BBA_HOME` (default `~/.bba`), `BBA_DEBUG=1` (stack traces).
Interactive commands: `/reload`, `/help`, `/quit`, `/exit`, plus extension commands.

The live Anthropic path is untested on the build host (no API key); the request shape is unit-tested.

## Extensions

Loaded at start and on `/reload`, in this order: `$BBA_HOME/extensions/*.clj`, then `.bba/extensions/*.clj` in the working folder (each sorted by name). A file that fails to load prints a warning and is skipped.

```clojure
(ns my.ext (:require [bba.ext :as ext]))

(ext/register-tool! {:name "reverse" :description "Reverse a string."
                     :input-schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
                     :handler (fn [{:keys [text]} ctx] (clojure.string/reverse text))})
(ext/register-command! "hello" (fn [args ctx] (println "hello" args)))
(ext/on! :tool-call (fn [{:keys [name input]} ctx] (when (= name "bash") nil)))  ; {:block true :reason ".."} blocks
(ext/on! :session-start (fn [_ ctx] ...))
(ext/set-provider! (fn [{:keys [messages system tools]}] {:role "assistant" :content [...]}))
```

- A later registration with the same name wins, with a warning. Extensions may replace built-ins.
- A `:tool-call` hook that throws **blocks** the call (fail-closed). Other hooks that throw only warn.
- Examples in `extensions/` (not auto-loaded): `reverse.clj`, `block_rm_rf.clj`. Copy one into `.bba/extensions/` to enable it.

Project context: `AGENTS.md` in `$BBA_HOME` and in the working folder is added to the system prompt.
Sessions: one JSONL file per run in `.bba/sessions/`. The API key is never written.

## Trust model

`bash` runs with your user rights and no sandbox. Extensions are ordinary code that runs in the harness process.

- **Project extensions run on start.** bba loads every `.clj` in `<cwd>/.bba/extensions/` when it starts, and adds `<cwd>/AGENTS.md` to the system prompt. Do not start bba in a folder you do not trust, or start it with `--no-extensions` (AGENTS.md is still read).
- **Agent-written extensions** take effect when you type `/reload` or when you next start bba. Check `.bba/extensions/` before you do either.
- **Hooks are guards, not a sandbox.** A `:tool-call` hook that throws blocks the call (fail-closed). The `block_rm_rf.clj` example matches the text `rm -rf` only; `rm -fr` or `rm -r -f` gets past it.
- **Sessions** in `.bba/sessions/` hold full tool output, which can include secrets read from files. bba writes a `.gitignore` there so they are not committed. The API key is never written.
