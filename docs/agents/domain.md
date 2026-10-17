# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Layout: multi-context

This repo is a **multi-context** repo. There is one domain context per sub-project, plus a root map that points at them. Concretely:

```
/
├── CONTEXT-MAP.md          ← root map; points at each context's CONTEXT.md
├── docs/adr/               ← system-wide decisions
└── newgen/
    ├── CONTEXT.md          ← the newgen (EXMenu) context
    └── docs/adr/           ← context-specific decisions
```

> None of these files exist yet, and that is deliberate. They are created lazily by `/domain-modeling` the first time a term or decision actually gets resolved — not upfront. When the first `CONTEXT.md` is written, `CONTEXT-MAP.md` is created alongside it at the root and made to point at every context.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repo root if it exists: it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. In multi-context repos, also check `newgen/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo (most repos):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo (this repo — root `CONTEXT-MAP.md` present):

```
/
├── CONTEXT-MAP.md                     ← system-wide index
├── docs/adr/                          ← system-wide decisions
└── newgen/
    ├── CONTEXT.md                     ← context-scoped glossary
    └── docs/adr/                      ← context-specific decisions
```

## Use the context's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the relevant `CONTEXT.md`. Don't drift to synonyms the context explicitly avoids.

If the concept you need isn't defined yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
