# bba

**Grow a live Clojure program by talking to it.**

bba is a small agent for the terminal. It runs on [babashka](https://github.com/babashka/babashka). bba opens a live Clojure namespace, the *world*, in the folder where you start it. A folder can have several named worlds. You ask for something. The model adds functions to the world, runs them and keeps the result. There is no build step. Each accepted change is saved, so the program is still there when you come back.

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

   If you already use the Codex CLI, you can skip the key and bill a ChatGPT
   subscription instead: `bba --provider codex`. See [Codex](#codex-use-a-chatgpt-subscription-instead-of-an-api-key).

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

## Worlds

Each folder starts with one world, `main`. You can make more, and each world has its own functions, revisions and rules. bba opens the world that you used last.

```
› /world                 # list the worlds; * marks the open one
› /world new scratch     # make an empty world
› /world fork try-2      # copy the open world at its current revision (or: /world fork try-2 5)
› /world try-2           # switch; the model is told which functions it has now
```

To start in a given world, use `bba --world NAME`. `bba -c` continues a session in the world that it used. If a folder has an old single world in `.bba/world/`, bba moves it to `.bba/worlds/main/` at start.

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
                  and report the error              .bba/worlds/<name>/revisions/
```

- **Two tools.** `develop_form` adds, changes or removes definitions (`defn`, `def`, `defonce`). `execute_form` runs code. It must not define anything.
- **The world is the only tool.** The model works in Clojure data: `slurp` and `spit` for files, `fs/glob` to find them, `http/get` and `json/parse-string` for web APIs. It uses `(sys/sh "cmd")` only for real external programs, such as `git` or a test runner. Every world has these aliases, and they survive restores: `str` (clojure.string), `set`, `fs` (babashka.fs), `sys`, `http` (babashka.http-client), `json` (cheshire), `csv`, `yaml`, `io`, `edn`, `walk`, `pp` (clojure.pprint) and `proc` (babashka.process). The list is `world-requires` in `src/bba/sys.clj`.
- **Lint first.** clj-kondo checks each form before it runs, with the world's functions as context. An error, such as an unresolved symbol or a call with the wrong number of arguments, rejects the code before it is evaluated. Warnings come back as `:lint` on the result. bba loads clj-kondo as a babashka pod and downloads it once. If the pod does not load, bba shows a warning and skips the check.
- **Checkpoints.** Each attempt starts from a checkpoint. If the attempt fails, bba restores the checkpoint and gives the error to the model. The model then fixes the code and tries again.
- **Revisions.** Each accepted attempt becomes a revision file, and bba never changes a revision file. A `CURRENT` file points to the live revision. bba moves that pointer only after the revision file is complete, so a crash cannot leave a half-written revision as the live one.
- **Invariants and goals.** You write them in the world's `world.edn`. A change that breaks an invariant is rejected. Goals only show progress.

## Rules for your world

Put checks in `.bba/worlds/<name>/world.edn` (`.bba/worlds/main/world.edn` for the first world):

```clojure
{:ns world
 :invariants [{:name "reverse works" :form (= "cba" (reverse-string "abc"))}]
 :goals      [{:name "has shout" :form (ifn? shout)}]
 :timeout-ms 30000
 :max-result-chars 4000
 :lint :reject}   ; or :warn (report only) or :off
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
| `/world [NAME \| new NAME \| fork NAME [REV]]` | List, switch, create or fork worlds. See [Worlds](#worlds). |
| `/functions`, `/describe NAME` | List the functions, or show one with its arguments, docstring and source. |
| `/history`, `/status` | Show the revisions, or the state of the world and its checks. |
| `/provider [NAME [MODEL]]`, `/model [NAME]` | Show or change the provider, with an optional model. The conversation stays. |
| `/reload` | Load new or changed extensions. Unload hooks run first. |
| `/skills`, `/mcp`, `/compact` | From the core extensions. See [Core extensions](#core-extensions). |
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
bba --world scratch                  # open the world named scratch
bba --provider ollama --model qwen3.5:0.8b
bba --max-turns 20                   # stop after 20 model calls in one turn (default 200)
bba --no-extensions                  # load only the core extensions
```

If stdin is a pipe, bba reads plain lines and prints only whole answers, so you can use it in scripts.

| Variable | Use |
|---|---|
| `BBA_PROVIDER`, `BBA_MODEL` | Same as `--provider` and `--model`. |
| `BBA_HOME` | Folder for history, user extensions and user config. The default is `~/.bba`. |
| `BBA_MAX_TURNS` | Same as `--max-turns`. |
| `BBA_SKILLS_TRUST=1` | Trust this project's skill folders (`.agents/skills`, `.bba/skills`). |
| `BBA_SKILLS_EXTRA` | More skill folders, separated by `:`. |
| `BBA_MCP_TRUST=1` | Trust this project's `.bba/mcp.json` (the MCP extension). |
| `CODEX_HOME`, `CODEX_ACCESS_TOKEN`, `CODEX_BASE_URL` | Where the `codex` provider finds its subscription login, and its endpoint. See [Codex](#codex-use-a-chatgpt-subscription-instead-of-an-api-key). |
| `NO_COLOR` | Plain output with no colors. |
| `BBA_DEBUG=1` | Show stack traces. |

## Providers

| `--provider` | Key | Default model |
|---|---|---|
| `anthropic` (default) | `ANTHROPIC_API_KEY` | `claude-opus-5-5` |
| `openrouter` | `OPENROUTER_API_KEY` | `anthropic/claude-opus-5.5` |
| `openai` | `OPENAI_API_KEY` | `gpt-5` |
| `ollama` | none | `gpt-oss` |
| `codex` | none (`codex login`) | `gpt-5.6-luna` |

To use another server with the OpenAI Chat Completions API, set `OPENAI_BASE_URL`. To use a remote Ollama server, set `OLLAMA_HOST`.

Tested live: OpenRouter and Codex, with the world tools. Ollama was tested live before the world became the only mode. Anthropic and OpenAI are tested only against a local fake server.

### Codex: use a ChatGPT subscription instead of an API key

The `codex` provider bills a ChatGPT plan instead of a metered API key. It needs no key of its own: it uses the login that the [Codex CLI](https://github.com/openai/codex) already made, read from `~/.codex/auth.json` (or `$CODEX_HOME/auth.json`).

```bash
codex login              # once, if you have not already
bba --provider codex
```

A ChatGPT plan covers the Codex endpoint but not the metered API — the two use different credentials and different billing — so `codex` is a separate provider rather than a `OPENAI_BASE_URL` setting.

What to know:

- **It reads, it never writes.** bba does not refresh the token, because refreshing rotates a single-use refresh token and would sign the Codex CLI out. When the token expires (about three days), run `codex login` again and restart bba.
- **The endpoint always streams, and it speaks the Responses API,** not Chat Completions. `bba.responses` translates between the two.
- **A reply that runs out of room is an error, not an empty answer.** The endpoint spends part of the reply budget thinking, so a hard question can come back with nothing but `incomplete: max_output_tokens`. bba says so rather than printing a blank line as if it had finished.
- **Model names differ from the API.** Use the names the Codex CLI uses (`gpt-5.6-luna`, `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.5`, `gpt-6-astra`); a metered-API name such as `gpt-5` is refused with \"not supported when using Codex with a ChatGPT account\". `bba --provider codex` shows the default.
- **`CODEX_BASE_URL` and `CODEX_ACCESS_TOKEN`** override the endpoint and the token (for a proxy, or for CI).
- This endpoint is not a published API and the plan's rate limits apply. Treat it as convenient, not contractual.

## Extensions

An extension is a Clojure file. bba loads extensions from three folders, in this order, at start and when you type `/reload`:

1. `extensions/` in the bba folder: core extensions. They ship with bba and always load, also with `--no-extensions`. See [Core extensions](#core-extensions).
2. `~/.bba/extensions/`: your user extensions.
3. `.bba/extensions/` in your project: project extensions.

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

;; Change what is sent to the provider. Return {:request req'} or nil:
(ext/on! :request (fn [{:keys [request]} ctx] nil))

;; Run at start, on /new and after /reload:
(ext/on! :session-start (fn [_ ctx] nil))

;; Cleanup, run when /reload drops the extensions (stop a child process, close a socket):
(ext/on-unload! (fn [] (println "bye")))
```

The `examples/extensions/` folder has two examples: `reverse.clj` and `block_rm_rf.clj`. To use one, copy it into `.bba/extensions/`.

bba also adds `AGENTS.md` from `~/.bba/` and from your project to the model's instructions.

## Core extensions

**Skills** (`skills.clj`). Adds [Agent Skills](https://agentskills.io/specification). A skill is a folder with a `SKILL.md` file. bba lists each skill's name and description in the `activate_skill` tool, and the model loads a whole skill only when it needs it. bba looks in `~/.agents/skills`, `~/.claude/skills`, `~/.bba/skills` and the `BBA_SKILLS_EXTRA` folders. It looks in the project folders `.agents/skills` and `.bba/skills` only when you trust them: set `BBA_SKILLS_TRUST=1` or put a `.trusted` file in the folder. A project skill replaces a user skill with the same name.

```
› /skills                     # list the skills
› /skills show NAME           # list the files in a skill
› /skills validate PATH       # check a skill against the specification
› /skills reload              # find new skills
```

**MCP** (`mcp.clj`). Adds the tools of [Model Context Protocol](https://modelcontextprotocol.io) servers as `mcp__<server>__<tool>`. bba reads `~/.bba/mcp.json`. It reads the project file `.bba/mcp.json` only when you trust it: set `BBA_MCP_TRUST=1` or add a `.bba/mcp.trusted` file. `examples/mcp.json` shows the format. A server starts with the session, stops after ten idle minutes, starts again on the next call and stops on `/reload`. Only stdio servers work. `/mcp` lists the servers and `/mcp stop` stops them all.

**Compaction** (`compaction.clj`). Keeps long sessions inside the model's context window. bba sends the model the first request, a summary of the middle of the conversation and the last turns. The session file keeps the whole conversation. Put settings in `~/.bba/compaction.edn` or `.bba/compaction.edn`; the project file wins:

```clojure
{:enabled? true :mode :continuous :threshold-tokens 60000 :keep-turns 6
 :summarizer {:provider nil :model nil}}   ; nil = the session's provider and model
```

`/compact` shows the state. `/compact on`, `/compact off` and `/compact model PROVIDER [MODEL]` change it for this session. If the summary fails, bba sends the whole conversation.

## Files that bba makes

| Path | Contents | In git? |
|---|---|---|
| `.bba/worlds/<name>/world.edn` | The settings, invariants and goals of one world. | Yes, if you add it. |
| `.bba/worlds/<name>/revisions/`, `CURRENT`, `ops.jsonl`, `LOCK` | The saved world and the attempt log. | No. bba adds a `.gitignore`. |
| `.bba/worlds/ACTIVE` | The name of the world that you used last. | — |
| `.bba/sessions/` | One log file for each session, and the compaction cache. | No. bba adds a `.gitignore`. |
| `~/.bba/history` | Your input history. | — |

## Safety

WARNING: bba has no sandbox. Code in the world runs with your user rights. It can read, change and delete any file that you can, and it can run any command.

- Do not start bba in a folder that you do not trust. At start, bba runs that folder's extensions, its `world.edn` checks and its saved revisions. `--no-extensions` stops only the user and project extensions.
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
| `src/bba/world.clj`, `world_store.clj`, `sys.clj`, `lint.clj` | The world: evaluator, checkpoints, named worlds, revisions, `sys/sh` and the clj-kondo check. |
| `src/bba/provider.clj`, `openai.clj` | Providers and streaming. |
| `src/bba/responses.clj`, `codex_auth.clj` | The `codex` provider: Responses API translation and the ChatGPT login. |
| `src/bba/main.clj`, `tui.clj`, `ui.clj` | Command line, line editor, spinner and colors. |
| `extensions/` | Core extensions: skills, MCP and compaction. |
| `examples/` | Example extensions and an example `mcp.json`. |

## Credits

- [babashka](https://github.com/babashka/babashka) by Michiel Borkent: the runtime.
- [Jiti](https://github.com/ghuntley/jiti) and ["Growing Lisp applications"](https://ghuntley.com/lisp/) by Geoffrey Huntley: the world, the two tools, checkpoints and revisions.
- [pi](https://pi.dev/): the small core and the extension model.
