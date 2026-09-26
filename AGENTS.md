# Repository Guidelines

## Change Notes

After every implementation change, add or update a Chinese Markdown summary under `notes/`. Name new files with the pattern `YYYY-MM-DD-topic.md`, for example `2026-09-26-ticket-lifecycle.md`.

Each summary must include:

- implemented behavior and business rules;
- newly added classes or files and the responsibility of each;
- modified classes or files and what changed;
- API and database changes, when applicable;
- tests and manual verification performed;
- known limitations or follow-up work.

Keep credentials and other secrets out of notes. Documentation-only wording fixes may update the most relevant existing note instead of creating a new file.

## Database Documentation

Keep `docs/database-schema.md` synchronized with the Flyway migrations. Any change that creates, alters, renames, or drops a table, column, index, or constraint must update the database document in the same commit. Document field purpose, nullability, defaults, enum values, logical relationships, indexes, and important application-enforced rules. Never edit an already-applied migration; add the next `V{version}__{description}.sql` file instead.
