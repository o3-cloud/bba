# bba — a babashka agent harness (spike)

A small, terminal-first coding agent in babashka, modelled on [pi](https://pi.dev/).
The command is `bba` so it never shadows the babashka `bb` binary.

- Kernel: agent loop, extension API and 4 tools (`read`, `write`, `edit`, `bash`) in `core.clj`, `ext.clj`, `tools.clj`, under 500 lines. Providers (`provider.clj`, `openai.clj`) and the terminal UI (`main.clj`, `tui.clj`, `ui.clj`) sit on top.
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

Options: `-c` (continue the latest session in this folder), `--provider NAME`, `--model NAME`, `--max-turns N` (default 50, or `BBA_MAX_TURNS`), `--no-extensions`, `--world` (see World mode).
Env: `BBA_PROVIDER`, `BBA_MODEL`, `BBA_HOME` (default `~/.bba`), `BBA_DEBUG=1` (stack traces), `NO_COLOR` (plain output).

## Interactive session

On a terminal, answers stream as they arrive and each finished line is redrawn with light markdown (headers, `**bold**`, `` `code` ``, fenced blocks). A spinner shows `thinking...` while the model works and `running <tool>...` while a tool runs.

| Key | Action |
|---|---|
| Enter | send |
| `\` then Enter, or Alt+Enter | new line (multi-line message); pasted text keeps its newlines |
| ← → Home End, Ctrl-A Ctrl-E | move the cursor |
| ↑ ↓ | history (saved in `$BBA_HOME/history`) |
| Ctrl-W, Ctrl-U, Ctrl-K | delete word, to line start, to line end |
| Ctrl-C | while the agent works: stop the turn (a running bash command is killed). At the prompt: clear the line |
| Ctrl-D on an empty line, `/quit`, `/exit` | leave |
| Ctrl-L, `/clear` | clear the screen |

Commands: `/reload`, `/provider [NAME]`, `/model [NAME]`, `/clear`, `/help`, plus extension commands.
`/provider NAME` changes the provider and uses its default model; `/model NAME` changes the model. Both keep the conversation. Without NAME they show the current setting.

A stopped turn is dropped from the conversation (the model does not see it next time), but any file changes it made stay. `-c` resumes from the session file and also skips stopped turns.

When stdin is not a terminal (a pipe or a script), bba reads plain lines and prints whole answers, so it stays scriptable.

## Providers

| `--provider` | Needs | Default model | Server |
|---|---|---|---|
| `anthropic` (default) | `ANTHROPIC_API_KEY` | `claude-opus-5-5` | api.anthropic.com |
| `openai` | `OPENAI_API_KEY` | `gpt-5` | `OPENAI_BASE_URL` (default `https://api.openai.com/v1`) |
| `openrouter` | `OPENROUTER_API_KEY` | `anthropic/claude-opus-5.5` | `OPENROUTER_BASE_URL` (default `https://openrouter.ai/api/v1`) |
| `ollama` | nothing | `gpt-oss` | `OLLAMA_HOST` (default `http://localhost:11434`) |

```bash
bb/bin/bba --provider ollama --model qwen3.5:0.8b -p "list the files here"
BBA_PROVIDER=openai BBA_MODEL=gpt-5-mini bb/bin/bba
```

OpenAI, OpenRouter and Ollama use the Chat Completions API, so `openai` with `OPENAI_BASE_URL` also works with other servers that offer it. `src/bba/openai.clj` translates messages both ways.

Live status: OpenRouter (`anthropic/claude-opus-5.5`) and Ollama (`qwen3.5:0.8b`) are tested live with one bash tool call each. Anthropic and OpenAI are tested only against a local fake server.

An extension can still replace the provider with `ext/set-provider!`.

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

## World mode (`--world`)

After Geoffrey Huntley's [Jiti](https://ghuntley.com/lisp/): grow a running Clojure application by talking to it, with no build step. `bba --world` opens one live namespace (the *world*) and offers the model two tools instead of the file tools:

- `develop_form {code, remove?}` adds, redefines or removes definitions (`defn`, `def`, `defonce`).
- `execute_form {code}` calls or composes existing functions. It must not define or redefine vars.

Both use one evaluator. Every attempt runs against a checkpoint. An error, a failed invariant or a timeout restores the last accepted state and returns an EDN result (`:status`, `:message`, `:failed`, `:form`) so the model can fix and retry. An accepted attempt becomes an immutable revision.

Commands (no model or API key needed; with no key, world mode starts with manual commands only):
`/develop CODE` (`/develop -NAME` removes), `/execute CODE`, `/preview CODE` (shows the result, keeps nothing), `/rollback N`, `/functions`, `/describe NAME`, `/history`, `/status`, `/abort`. Ctrl-C stops a running attempt and restores; `/abort` at an idle prompt says there is nothing to abort.

Store, in `.bba/world/`:
- `world.edn` — the adapter: `{:ns world :invariants [{:name .. :form ..}] :goals [..] :timeout-ms 30000 :max-result-chars 4000}`. Invariants must hold after every change; goals are only reported.
- `revisions/NNNNNN.edn` — full snapshots (ordered accepted source + printable atom/volatile contents), never changed. `CURRENT` names the live one and is moved atomically after the revision is fully written; revisions above CURRENT (interrupted operations) are ignored. `/rollback N` publishes N's state as a new revision, so history is kept.
- `LOCK` (one bba process per world), `ops.jsonl` (one line per attempt). A `.gitignore` keeps all of these except `world.edn` out of git.

Differences from Common Lisp / Jiti:
- **No conditions or restarts.** SCI cannot pause a live stack. A failure restores the checkpoint, then reports the exception.
- **Restore = rebuild.** The namespace is discarded and the accepted source is replayed. So develop forms with side effects (`println`, `spit`) run again on each restore: keep develop forms to definitions and put effects in `execute_form`.
- **Runaway CPU loops cannot be killed.** A timeout restores the world and detaches the thread, but it keeps using CPU until bba exits (`/status` counts them). Blocking code (sleep, I/O) is stopped.
- Only the world namespace and its atoms/volatiles are checkpointed. Files, network and other namespaces are not. Values that cannot be printed as EDN are not saved (a warning names them).

Trust: world code runs with your user rights in the bba process, like `bash` and extensions. The adapter's invariant and goal forms are code: `--world` in an untrusted repo runs `.bba/world/world.edn` and replays any revisions committed under `.bba/world/revisions/`, and `--no-extensions` does not disable either. The adapter `:ns` must be a new name (harness and library namespaces such as `bba.*`, `clojure.*` and `user` are refused, since each restore removes and rebuilds the world namespace). World data goes only to the configured provider.

## Trust model

`bash` runs with your user rights and no sandbox. Extensions are ordinary code that runs in the harness process.

- **Project extensions run on start.** bba loads every `.clj` in `<cwd>/.bba/extensions/` when it starts, and adds `<cwd>/AGENTS.md` to the system prompt. Do not start bba in a folder you do not trust, or start it with `--no-extensions` (AGENTS.md is still read).
- **Agent-written extensions** take effect when you type `/reload` or when you next start bba. Check `.bba/extensions/` before you do either.
- **Hooks are guards, not a sandbox.** A `:tool-call` hook that throws blocks the call (fail-closed). The `block_rm_rf.clj` example matches the text `rm -rf` only; `rm -fr` or `rm -r -f` gets past it.
- **Sessions** in `.bba/sessions/` hold full tool output, which can include secrets read from files. bba writes a `.gitignore` there so they are not committed. The API key is never written.
