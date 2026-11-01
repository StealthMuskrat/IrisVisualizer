# Repository workflow

- Keep the complete, current project on `main`.
- Do ongoing work directly on `main`. Do not create branches for individual work batches, features, documentation updates or test runs.
- A temporary branch may be used for an actual development stage when needed. Integrate its work into `main`, then delete that branch immediately. Do not leave completed stage branches behind.
- Preserve existing project work and commit history when consolidating branches. Never replace `main` with an older or incomplete project tree.
- The project is currently in the UI stage. Finish UI implementation before executing its regression suites or GitHub tests. Compilation and code inspection are permitted during implementation.
- Use `[skip ci]` in implementation commit messages while tests are deferred. Do not open a test-triggering pull request or dispatch workflows until the UI implementation is finished.
- Keep unsupported previews disabled. Complete UI validation before starting the renderer stage.
