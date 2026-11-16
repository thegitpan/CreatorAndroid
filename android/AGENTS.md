# Creator Android development

Read `docs/CONTRACT.md` before changing module APIs; read `docs/PLAN.md` for product scope. Android work lives under this directory. Keep upstream iOS code and notices intact.

- Package root: `it.panebarco.creator`. Modules: `app`, `domain`, `data`, `media`. Owners coordinate API changes before editing another owner's files.
- Source media is immutable. Edits, exports and cache cleanup preserve originals and every source reachable from history. Ordinary recording additions survive editor Undo/Redo.
- Persist a completed operation before reporting success. Recovery preserves existing valid revisions; report corruption rather than silently replacing it with an empty project.
- Test actual public workflows and meaningful failure cases. Emulator evidence covers UI, media fixtures and lifecycle; Samsung camera acceptance remains pending until physical preview and saved file are checked.
- Use Italian UI text and actionable errors. Expose completed features; diagnostic/experimental capabilities are clearly labelled.
- No account, analytics or media uploads. Diagnostics contain technical metadata only. Keys, local paths and generated media stay out of Git.
- Agents own only assigned files; report changes, executed checks and remaining limits. Root integrates, audits and commits. Request a contract change rather than improvising conflicting signatures.

User authorization: implement autonomously, use Sol 6.1 Max agents for code, root reviews and requests fixes. Continue independent work while physical phone tests are unavailable. Never erase user projects to run tests.
