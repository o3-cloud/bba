# BDR-001: Jev Guardrail Blocks Dangerous Tool Calls

## Status

Proposed

## Behavior

When the guardrail is enabled, BBA evaluates each tool call before execution and blocks calls with a Jev score of 69 or more. The guardrail is enabled by default.

## Context

BBA extensions can observe tool calls before BBA executes them. Users need a guardrail that can stop actions with a high danger score. The guardrail uses Jev through the configured OpenRouter provider. The guardrail is on by default, because a guardrail that a user must turn on does not protect a user who does not know about it. A user turns the guardrail off with `BBA_GUARDRAIL_ENABLED=false`. The guardrail must fail closed when it cannot obtain a valid decision.

## Acceptance Criteria

- AC-1: When the guardrail is enabled, BBA sends each tool call to Jev before the tool runs.
- AC-2: BBA sends the tool name and tool input to Jev for classification.
- AC-3: If Jev returns a valid score from 69 through 100, BBA does not execute the tool call.
- AC-4: If Jev returns a valid score from 0 through 68, BBA permits the tool call to continue.
- AC-5: If Jev returns invalid JSON, BBA does not execute the tool call.
- AC-6: If Jev omits the score, BBA does not execute the tool call.
- AC-7: If Jev returns a score below 0 or above 100, BBA does not execute the tool call.
- AC-8: If OpenRouter returns an error or does not respond, BBA does not execute the tool call.
- AC-9: When BBA blocks a tool call, BBA returns a clear block reason to the caller.
- AC-10: The block result identifies the Jev score when a valid score exists.
- AC-11: When the guardrail is disabled, BBA preserves the existing tool-call behavior.
- AC-12: The guardrail uses the configured Jev model and configured OpenRouter credentials.
- AC-13: The guardrail does not expose the OpenRouter credential in a block result or error message.
- AC-14: The guardrail is enabled when `BBA_GUARDRAIL_ENABLED` is absent or is not the exact value `false`.

## Verification

### Scenario 1: A high-risk tool call is blocked

- **Given** the guardrail is enabled
- **And** Jev returns a valid score of 95
- **When** BBA receives a tool call
- **Then** BBA does not run the tool
- **And** BBA returns a block result with score 95
- **And** BBA returns the Jev reason

### Scenario 2: A tool call just below the threshold is allowed

- **Given** the guardrail is enabled
- **And** Jev returns a valid score of 68
- **When** BBA receives a tool call
- **Then** BBA permits the tool call to run

### Scenario 3: A low-risk tool call is allowed

- **Given** the guardrail is enabled
- **And** Jev returns a valid score of 10
- **When** BBA receives a tool call
- **Then** BBA permits the tool call to run

### Scenario 4: Jev returns invalid data

- **Given** the guardrail is enabled
- **And** Jev returns invalid data
- **When** BBA receives a tool call
- **Then** BBA does not run the tool
- **And** BBA returns a classifier error
- **And** BBA does not expose the OpenRouter credential

### Scenario 5: OpenRouter is unavailable

- **Given** the guardrail is enabled
- **And** OpenRouter returns an error or times out
- **When** BBA receives a tool call
- **Then** BBA does not run the tool
- **And** BBA returns a classifier error

### Scenario 6: The guardrail is disabled

- **Given** the guardrail is disabled
- **When** BBA receives a tool call
- **Then** BBA does not call Jev
- **And** BBA preserves the existing tool-call behavior
