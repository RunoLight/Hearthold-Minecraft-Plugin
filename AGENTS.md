# Hearthold — rules for agents

## Design document

The design document is [`docs/design.md`](docs/design.md) — the single source of truth for game mechanics; it is not duplicated anywhere else. It is written in English.

## Commits

Every commit message ends with a `Design-doc:` line, using one of:

- `Design-doc: updated — <sections>` — the change affects game mechanics or rules, and they are reflected in `docs/design.md` in the same commit.
- `Design-doc: not needed — <why>` — the change does not touch the design: minor UI details (button order, texts, icons), refactoring, bug fixes that don't change the rules, developer tooling (launch scripts, build).
- `Design-doc: pending — <what is not reflected>` — only when it can't be reflected right away (e.g. the decision hasn't been made yet); the next commit must close it.

What counts as design: player rules and rights, costs and starting values, limits, new items and blocks, menus as a way to access a mechanic, behaviour during war and raids. When in doubt, reflect it.

Do not add `Co-Authored-By:` trailers to commit messages.

Example:

```
Chest access: enemies cannot open chests even during war

Design-doc: updated — «War», «Clans: roles and tags»
```

## Code conventions

- **UI first.** Every player-facing command must also be reachable from an in-game menu (compass or base heart menu).
- **Localization.** No hard-coded player-visible text. Use `Lang.tr("bundle.key", args...)` (`dev.hearthold.i18n.Lang`) and add the key to **both** `src/main/resources/lang/en/<bundle>.properties` and `src/main/resources/lang/ru/<bundle>.properties`. English is the default language. Placeholders are positional (`{0}`, `{1}`); apostrophes are literal. Console log messages are written in English directly.
- Code comments and Javadoc are in English.
