# Step Windows Implementation Plan

**Goal:** Deliver a Windows x64 daily-use preview preserving the existing Step core workflows and same-account sync.
**Spec:** docs/superpowers/specs/2026-11-22-windows-daily-use.md
**Architecture:** Shared Kotlin/JVM domain sources, Compose desktop UI, Windows-specific encrypted credentials/runtime services. Existing Mac/Android remain intact.
**Workspace:** clean codex/windows-preview branch based on current main, shared task checkout with disjoint agent ownership.

- [x] Shared core extraction: Session, HTTP configuration injection, reusable projections/actions; desktop core JVM module/tests; Android regression.
- [x] Desktop runtime: atomic store/backup/lock, DPAPI vault, async sync lifecycle, execution ledger, reminders and local AI settings/history; tests.
- [x] Desktop UI: four quadrants, inbox, today scheduling, ideas/linked tasks, step completion/history, sync preview/conflicts, focus and AI review; no empty entries.
- [ ] Root integration: pinned Gradle/Compose setup, Main/tray/hotkeys, approved icons, public config packaging, Windows CI installers.
- [ ] Verify native Windows build/start/security tests; inspect UI with fictional data; independent audit; publish only verified artifacts with known limits.

Runtime/UI contract is recorded in desktop/CONTRACT.md. Per-agent file ownership in task briefs; only root runs whole desktop Gradle while files are stable.

## Progress

- Android shared-source regression388/388; Desktop pure core294/294 and app26/26 (18 runtime/security,2 shortcut parsing,6 real Compose scene workflows) pass onMac. No real accounts or model calls.
- Independent review found lost drafts on hidden window and initialize/close lifetime race; both fixed with retained Window compositions, aggregated dirty/exit guards, and owned initialization job. Relevant regressions added.
- Mac Homebrew JDK packaging intentionally rejected by Compose vendor guard; no guard bypass. Actual Windows packaging usesTemurin17 onwindows-2022.
- Windows workflow/config ready; only public existing sync client parameters supplied via repository variables. Desktop generated config, toolchains, builds and local stores remain ignored.
- Waiting for real Windows CI build/install/self-test; no Windows download claimed yet.

- First WindowsCI compiled/tested, installed MSI and launched isolated packaged self-test successfully; portable step lacked createDistributable and was corrected. Second WindowsCI45007268915 passed all steps and produced exe/msi/portable. No release created.
- User then rejected visual maturity relative toMac. Publication remains paused while UI is refined againstMac semantic palette/spacing/type, not merely recolored. Added bundledOFL NotoSansSC with explicit400/500/600 axes, dark/light appearance persistence, vector icons and denser useful layout.
- Local refined tree: core294/app31 pass, including7 interaction tests and4 visual datasets producing20 screenshots at960/1280 in dark/light. Windows re-verification pending before presenting the final candidate.
