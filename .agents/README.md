# Shared Coding Agent Skills

This directory contains reusable skill files for coding agents working on the StreamsHub MCP project.
The skills are agent-agnostic markdown files. Each agent references them via symlinks or thin pointers.

## Skills

| Skill | Description |
|---|---|
| `code-review.md` | PR/code review checklist covering architecture, tools, services, DTOs, style, security, tests, docs |
| `test-coverage-check.md` | Identify untested code, verify test patterns, check McpDiscoveryTest registration |
| `add-mcp-tool.md` | Step-by-step guide for adding a new MCP tool |
| `add-diagnostic-tool.md` | Step-by-step guide for adding a composite diagnostic tool |
| `add-system-test.md` | Step-by-step guide for writing e2e system tests in systemtest/ |

## Agent Integration

All content lives here in `.agents/skills/`. Each agent points to these files:

| Agent | How it references skills |
|---|---|
| **Claude Code** | Symlinks in `.claude/commands/` |
| **Bob** | Thin SKILL.md wrappers in `.bob/skills/` with "read .agents/skills/X.md" |
| **Cursor** | Thin `.mdc` rules in `.cursor/rules/` with "read .agents/skills/X.md" |
| **GitHub Copilot** | `.github/copilot-instructions.md` points to this directory |
| **Windsurf** | `.windsurfrules` points to this directory |
| **Codex** | Reads `AGENTS.md` automatically — update it when adding new patterns or conventions (no separate integration file needed) |

## Adding a New Skill

1. Create the skill file in `.agents/skills/`
2. Add a Claude Code symlink: `ln -s ../../.agents/skills/<name>.md .claude/commands/<name>.md`
3. Create a Bob wrapper in `.bob/skills/streamshub-<name>/SKILL.md`
4. Create a Cursor rule in `.cursor/rules/<name>.mdc`
5. Update this README, `CONTRIBUTING.md`, `.github/copilot-instructions.md`, and `.windsurfrules`
