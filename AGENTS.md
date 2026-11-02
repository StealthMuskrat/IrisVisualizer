# Repository workflow

- Keep the complete, current project on `main`.
- Do ongoing work directly on `main`. Do not create branches for individual work batches, features, documentation updates or test runs.
- A temporary branch may be used for an actual development stage when needed. Integrate its work into `main`, then delete that branch immediately. Do not leave completed stage branches behind.
- Preserve existing project work and commit history when consolidating branches. Never replace `main` with an older or incomplete project tree.
- UI implementation and automated stage validation are complete at 5d487ec49ce619439fa18962b83e15efd23a67eb: all 176 editor groups, pinned Overworld acceptance, Windows package/launcher checks and all five feasibility suites passed. See docs/UI-VALIDATION.md and docs/ui-validation-2026-11-02.json. Interactive Windows GUI/DPI/GPU QA remains explicitly unverified for final integration.
- The current development stage is Renderer. Work in large implementation batches, preserving the original Engine → UI → Renderer → integration order. Defer renderer-stage test execution until its implementation is complete; compilation and code inspection are permitted during implementation.
- Use `[skip ci]` in implementation commit messages while tests are deferred. Do not open a test-triggering pull request or dispatch workflows until the UI implementation is finished.
- Keep unsupported previews disabled. Renderer foundations must not bypass native readiness, version-matched user-selected client assets or generation/mesh memory budgets, and must preserve original engine block IDs/states.
