# UCII operations (spec: https://github.com/stackable-specs/ucii, v0.1, level 2).
# Make is the single execution engine: people, agents, hooks and CI call these
# targets, never bb or the lint tools directly. ucii.json advertises them;
# `make describe` prints it and `make verify` fails when the two disagree.
# Each target the manifest advertises is tagged `## [ucii]`.

.PHONY: describe test verify fmt agent help

# The prompt reaches the recipe through the environment, so the shell never
# parses its text.
export PROMPT

help: ## Show available commands
	@awk 'BEGIN {FS = ":.*## "}; /^[a-zA-Z_-]+:.*## / {printf "%-10s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

describe: ## [ucii] Print the UCII manifest
	@cat ucii.json

test: ## [ucii] Run the offline test suite
	bb test

verify: ## [ucii] Lint, format check, and check ucii.json against this Makefile
	bb lint
	bb ucii-check

fmt: ## [ucii] Rewrite the Clojure sources with cljfmt
	bb fmt

agent: ## [ucii] Run bba from source (PROMPT=<text> answers once and exits)
	bb agent $(if $(PROMPT),-p "$$PROMPT")
