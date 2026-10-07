# bba

**Grow a live Clojure program by talking to it.**

bba is a small agent for the terminal. It runs on [babashka](https://github.com/babashka/babashka). bba opens one live Clojure namespace, the *world*, in the folder where you start it. You ask for something. The model adds functions to the world, runs them and keeps the result. There is no build step. Each accepted change is saved, so the program is still there when you come back.

The design comes from two projects:

- [Jiti](https://ghuntley.com/lisp/) by Geoffrey Huntley: "the product builds the product". The model has two tools only: one to change the program and one to run it.
- [pi](https://pi.dev/): a minimal agent core, and every other feature is an extension.

The command is `bba`, so it does not hide the babashka `bb` command.

> Status: spike. It works end to end, but it has no sandbox. Read [Safety](#safety) before you use it.

## Quick start

1. Install babashka 1.13 or later:

   ```bash
   bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install) --dir ~/.local/bin
   ```

2. Get bba and put it on your PATH:

   ```bash
   git clone git@github.com:o3-cloud/bba.git
   ln -s "$PWD/bba/bin/bba" ~/.local/bin/bba
   ```

3. Set a key for one provider. OpenRouter gives you many models with one key:

   ```bash
   export OPENROUTER_API_KEY=...
   ```

4. Start bba in a project folder:

   ```bash
   cd my-project
   bba --provider openrouter
   ```

5. Ask for something:

   ```
   › How many lines are in notes.txt? Then make a function line-count for it.
   → execute_form {"code":"(sys/sh \"wc -l notes.txt\")"}
   → develop_form {"code":"(defn line-count [path] (count (str/split-lines (slurp path))))"}
   → execute_form {"code":"(line-count \"notes.txt\")"}
   notes.txt has 3 lines. I defined line-count, and (line-count "notes.txt") returns 3.
   ```

The next time you start bba in this folder, `line-count` is still there.

## Try it without a model

You do not need an API key to use the world. Without a key, bba starts with the manual commands only:

```
› /develop (defn reverse-string "Reverse s." [s] (apply str (reverse s)))
accepted: revision 1 develop 12 ms defined reverse-string
› /execute (reverse-string "hello")
accepted: revision 2 execute 1 ms
=> "olleh"
› /preview (reverse-string "abc")
=> "cba" (preview: not kept)
› /describe reverse-string
```

## How it works

```
 you ──► model ──► develop_form  (add, change or remove functions)
                   execute_form  (run functions, read files, run commands)
                        │
                        ▼
              checkpoint ─► evaluate in the world ─► check invariants
                        │                                │
                  fail: restore                     pass: save revision
                  and report the error              .bba/world/revisions/
```

- **Two tools.** `develop_form` adds, changes or removes definitions (`defn`, `def`, `defonce`). `execute_form` runs code. It must not define anything.
- **The world is the only tool.** To read a file, the model uses `slurp`. To write a file, it uses `spit`. To run a command, it uses `(sys/sh "cmd")`. The world has these aliases: `str` (clojure.string), `set`, `fs` (babashka.fs) and `sys`.
- **Checkpoints.** Each attempt starts from a checkpoint. If the attempt fails, bba restores the checkpoint and gives the error to the model. The model then fixes the code and tries again.
- **Revisions.** Each accepted attempt becomes a revision file, and bba never changes a revision file. A `CURRENT` file points to the live revision. bba moves that pointer only after the revision file is complete, so a crash cannot leave a half-written revision as the live one.
- **Invariants and goals.** You write them in `.bba/world/world.edn`. A change that breaks an invariant is rejected. Goals only show progress.

## Rules for your world

Put checks in `.bba/world/world.edn`:

```clojure
{:ns world
 :invariants [{:name "reverse works" :form (= "cba" (reverse-string "abc"))}]
 :goals      [{:name "has shout" :form (ifn? shout)}]
 :timeout-ms 30000
 :max-result-chars 4000}
```

If a change breaks an invariant, bba rejects the change and keeps the old version:

```
› /develop (defn reverse-string [s] s)
rejected: invariant reverse works {:reason :invariant, :failed [{:name "reverse works"}], :restored-to 1}
```

NOTE: An invariant about a function that you have not built yet shows a warning at start. The warning stops when the function exists.

## Commands

| Command | What it does |
|---|---|
| `/develop CODE` | Add or change definitions. `/develop -NAME` removes one. |
| `/execute CODE` | Run code and keep the result. |
| `/preview CODE` | Run code, show the result and keep nothing. |
| `/rollback N` | Make a new revision with the state of revision N. History stays. |
| `/functions`, `/describe NAME` | List the functions, or show one with its arguments, docstring and source. |
| `/history`, `/status` | Show the revisions, or the state of the world and its checks. |
| `/provider [NAME [MODEL]]`, `/model [NAME]` | Show or change the provider, with an optional model. The conversation stays. |
| `/reload` | Load new or changed extensions. |
| `/new` | Start a new session: a new log file and an empty conversation. The world stays. |
| `/clear`, `/help`, `/quit` | Clear the screen, list the commands, leave. |

## Keys

| Key | What it does |
|---|---|
| Enter | Send. |
| `\` then Enter, or Alt+Enter | Start a new line. Pasted text keeps its line breaks. |
| ↑ ↓ | Go through your history. bba saves it in `~/.bba/history`. |
| ← → Home End, Ctrl-A, Ctrl-E | Move the cursor. |
| Ctrl-W, Ctrl-U, Ctrl-K | Delete a word, to the start of the line, or to the end of the line. |
| Ctrl-C | While the model works: stop the turn, stop any running command and restore the world. At the prompt: clear the line. |
| Ctrl-D, `/quit` | Leave. |

Answers stream as the model writes them. When a line is complete, bba shows it with simple markdown formatting. A spinner shows `thinking...` or `running <tool>...`.

## Options

```bash
bba                                  # interactive session
bba -p "question"                    # print mode: print the answer and exit (0 = success)
bba -c                               # continue the last session in this folder
bba --provider ollama --model qwen3.5:0.8b
bba --max-turns 20                   # stop after 20 model calls in one turn (default 50)
bba --no-extensions                  # do not load extensions
```

If stdin is a pipe, bba reads plain lines and prints only whole answers, so you can use it in scripts.

| Variable | Use |
|---|---|
| `BBA_PROVIDER`, `BBA_MODEL` | Same as `--provider` and `--model`. |
| `BBA_HOME` | Folder for history and user extensions. The default is `~/.bba`. |
| `NO_COLOR` | Plain output with no colors. |
| `BBA_DEBUG=1` | Show stack traces. |

## Providers

| `--provider` | Key | Default model |
|---|---|---|
| `anthropic` (default) | `ANTHROPIC_API_KEY` | `claude-opus-5-5` |
| `openrouter` | `OPENROUTER_API_KEY` | `anthropic/claude-opus-5.5` |
| `openai` | `OPENAI_API_KEY` | `gpt-5` |
| `ollama` | none | `gpt-oss` |

To use another server with the OpenAI Chat Completions API, set `OPENAI_BASE_URL`. To use a remote Ollama server, set `OLLAMA_HOST`.

Tested live: OpenRouter, with the world tools. Ollama was tested live before the world became the only mode. Anthropic and OpenAI are tested only against a local fake server.

## Extensions

An extension is a Clojure file in `~/.bba/extensions/` or in `.bba/extensions/` in your project. bba loads extensions at start and when you type `/reload`.

```clojure
(ns my.ext (:require [bba.ext :as ext]))

;; A new tool for the model:
(ext/register-tool! {:name "reverse" :description "Reverse a string."
                     :input-schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
                     :handler (fn [{:keys [text]} ctx] (clojure.string/reverse text))})

;; A new command (/hello):
(ext/register-command! "hello" (fn [args ctx] (println "hello" args)))

;; A guard. Return {:block true :reason "..."} to stop a tool call:
(ext/on! :tool-call (fn [{:keys [name input]} ctx] nil))
```

The `extensions/` folder has two examples: `reverse.clj` and `block_rm_rf.clj`. To use one, copy it into `.bba/extensions/`.

bba also adds `AGENTS.md` from `~/.bba/` and from your project to the model's instructions.

## Files that bba makes

| Path | Contents | In git? |
|---|---|---|
| `.bba/world/world.edn` | Your world settings, invariants and goals. | Yes, if you add it. |
| `.bba/world/revisions/`, `CURRENT`, `ops.jsonl`, `LOCK` | The saved world and the attempt log. | No. bba adds a `.gitignore`. |
| `.bba/sessions/` | One log file for each session. | No. bba adds a `.gitignore`. |
| `~/.bba/history` | Your input history. | — |

## Safety

WARNING: bba has no sandbox. Code in the world runs with your user rights. It can read, change and delete any file that you can, and it can run any command.

- Do not start bba in a folder that you do not trust. At start, bba runs that folder's extensions, its `world.edn` checks and its saved revisions. `--no-extensions` stops only the extensions.
- Read files that the model writes to `.bba/extensions/` before you type `/reload`.
- A `:tool-call` guard is a check, not a sandbox. The `block_rm_rf.clj` example looks for the text `rm -rf` only.
- Session logs can contain secrets from files that the model read. bba never writes your API key.
- bba sends your conversation and world data only to the provider that you select.

## Limits

- **No restarts.** Clojure has no condition/restart system like Common Lisp. When an attempt fails, bba restores the checkpoint, and the model fixes the code in a new attempt.
- **Effects outside the world stay.** If a failed attempt wrote a file or ran a command, that file or command stays done.
- **Restores replay code.** bba rebuilds the world from the saved source. Keep `develop_form` code to definitions, and put side effects in `execute_form`.
- **30-second timeout.** The world stops an attempt after `:timeout-ms` (30 s by default). For long builds or tests, raise `:timeout-ms` in `world.edn`.
- **Runaway loops.** A timeout cannot stop a busy loop that does not wait. That thread uses CPU until you quit bba. `/status` counts these threads.
- **One bba for each folder.** A lock stops a second bba in the same folder.

## Development

```bash
cd bba
bb test        # all tests, offline, with a fake provider
bb agent       # run bba from source
```

| File | Part |
|---|---|
| `src/bba/core.clj`, `ext.clj` | Kernel: the agent loop and the extension API. The tests keep it under 500 lines. |
| `src/bba/world.clj`, `world_store.clj`, `sys.clj` | The world: evaluator, checkpoints, revisions and `sys/sh`. |
| `src/bba/provider.clj`, `openai.clj` | Providers and streaming. |
| `src/bba/main.clj`, `tui.clj`, `ui.clj` | Command line, line editor, spinner and colors. |

## Credits

- [babashka](https://github.com/babashka/babashka) by Michiel Borkent: the runtime.
- [Jiti](https://github.com/ghuntley/jiti) and ["Growing Lisp applications"](https://ghuntley.com/lisp/) by Geoffrey Huntley: the world, the two tools, checkpoints and revisions.
- [pi](https://pi.dev/): the small core and the extension model.
