# What and why

<!-- What changes, and which problem does it solve? -->

## Checked by

<!-- What did you actually run? Please tick only what you really ran. -->

- [ ] `./gradlew test` (backend) is green
- [ ] `npm run lint` and `npm test` (frontend) pass
- [ ] `npm run build` after a `--fix` over several files
- [ ] Tried in the running application (own database, not real data)

## Checklist

- [ ] New UI texts are German, new identifiers, comments and docs English
- [ ] Industry-neutral: no hotels, guests or a particular trade in code, prompts or UI texts
- [ ] Backend change: Spock specs for the happy path, validation, tenant isolation and roles are included
- [ ] The affected file in `docs/architecture/` is updated
- [ ] A new or updated dependency: `THIRD-PARTY.md` (and `NOTICE`, if it asks for it) is updated
- [ ] No real data in the diff - no company documents, chat histories, names, keys or domains, not in specs or
      comments either
