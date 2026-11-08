# Phase 6 ownership and integration

This file records the non-interference rules for Phase 6. It is the source of truth for
file ownership while the feature branches are active.

## Integration rules

- One feature branch and one PR at a time. The Phase 6 integrator owns shared-file edits
  and merges each green PR into `debug` before the next branch is cut.
- A file may be changed by only the currently active feature PR. Later features must start
  from the updated `debug` tip and must not edit another active branch's checkout.
- Builds and tests run in GitHub Actions only. Do not run Gradle or native builds locally.
- Keep each feature in a separate commit/PR so it has a clear revert point. PR descriptions
  state risks, test results, and the revert commit.
- Do not merge Phase 6 work to `main` or publish it until the owner has tested the APK.
- Keep all process, storage, and filesystem operations inside TermX-owned sessions,
  processes, and rootfs directories.

## File ownership by feature

| Feature PR | Exclusive file area while active | Integrator |
| --- | --- | --- |
| 6.1 Agent keep-alive | `session/`, `runtime/`, foreground-service manifest, Doctor capability probe | Phase 6 integrator |
| 6.2 Terminal notifications | terminal output parsing, notification routing, shell notification hooks | Phase 6 integrator |
| 6.3 Native prompt composer | composer UI, text sanitization/sending, composer history and snippets | Phase 6 integrator |
| 6.4 Process and port viewer | process/proc parsers, process panel UI, guarded signal flow | Phase 6 integrator |
| 6.5 Rootfs backup and restore | rootfs archive, SAF transfer, snapshot and restore flows | Phase 6 integrator |

Shared entry points such as `MainActivity`, `SessionService`, settings storage, and
`AndroidManifest.xml` are edited by only the active PR. Features that need the same file
are serialized and integrated in order; no concurrent ownership is allowed.
