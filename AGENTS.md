# Waynova field-data safety

- Never run Gradle `connected*AndroidTest` tasks: their cleanup has uninstalled the field app and removed its recording storage.
- Run instrumentation only through `bash mobile/test_emulator.sh`, which requires an explicitly selected emulator and verifies `ro.kernel.qemu=1`. Never target a physical phone for instrumentation.
- Never uninstall Waynova, clear its data, or replace/delete phone recordings as an implementation step. Before any authorized phone update, copy and verify all existing raw CSV, diagnostics CSV and summary JSON files to a new local backup directory. If backup cannot be verified, stop and ask.
- Keep private drive fixtures outside APK assets and source control. Treat phone GPS as a reference, not survey-grade truth. Never train, adapt, or feed the estimator with withheld test references.
- Preserve unrelated work and staged deletions.
