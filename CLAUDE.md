# Subsequence Matching for JDT

Eclipse plugin providing enhanced content assist with subsequence matching and frequency-based method ranking.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn clean verify
```

Requires Maven 3.9+ (Tycho 4.0.13 refuses older versions) and a Java 21 JAVA_HOME (adjust the path to the local JDK install).

Minimum supported Eclipse is **2025-03 (4.35)**: it is the first release whose JDT UI bundle (`org.eclipse.jdt.ui` 3.34.0) declares `JavaSE-21`, so every Eclipse that can host this plugin from that release on runs on Java 21. Accordingly both bundles declare `Bundle-RequiredExecutionEnvironment: JavaSE-21`, the manifest lower bounds are `org.eclipse.jdt.core [3.41.0,4.0.0)` and `org.eclipse.jdt.ui [3.34.0,4.0.0)`, the target platform is `https://download.eclipse.org/releases/2025-03`, and Java 21 language features and APIs may be used freely. No backward compatibility with older Eclipse releases or Java 17 is kept.

Produces update site ZIP at `org.eclipse.subsequence.jdt.repository/target/org.eclipse.subsequence.jdt.repository-1.0.0-SNAPSHOT.zip`. The build also runs the unit tests in `org.eclipse.subsequence.jdt.tests` via tycho-surefire (headless OSGi runtime).

## Project Structure

- `org.eclipse.subsequence.jdt/` — main plugin
  - `completion/` — `SubsequenceCompletionProposalComputer` — JDT content assist integration, applies subsequence matching + frequency boost to proposals; auto-schedules a workspace analysis on first completion when no workspace data exists
  - `callmodel/` — frequency boost system using pre-trained models
    - `CallModelIndex` — singleton managing ZIP-based models (call, statics, ctor) + workspace analysis data; merges all sources using max probability per method
    - `FrequencyBooster` — computes relevance boost (0-200) per proposal from model probabilities; skips `java.lang.Object` methods
    - `JbifParser` — parses JBIF (Bayesian Network) format from recommenders model ZIPs
    - `CtorModelParser` — parses constructor frequency JSON from ctor ZIPs
    - `WorkspaceAnalyzer` — background job analyzing method/constructor call frequency in user's workspace (plain and `super` calls, `new` expressions, method references)
    - `CompletionTracker` — dual-counter store (workspace scan + accepted completions) persisted to the plugin state location; normalized view is memoized
  - `core/` — `LCSS` subsequence matching algorithm
  - `dialog/` — Open Type dialog alternative with subsequence matching (dialog size/history persisted to the plugin state location)
  - `preferences/` — preference page for model directory path, min prefix length and the diagnostic log file; shows which model ZIPs were found in the configured directory
- `org.eclipse.subsequence.jdt.tests/` — test fragment (`Fragment-Host: org.eclipse.subsequence.jdt`, `eclipse-test-plugin` packaging, JUnit 5)
- `org.eclipse.subsequence.jdt.feature/` — Eclipse feature
- `org.eclipse.subsequence.jdt.repository/` — p2 update site

## Model Directory

Configured via preferences (`subwords_model_dir_path`). Points to a directory containing:
- `*-call.zip` — instance method call frequency (JBIF format)
- `*-statics.zip` — static method call frequency (JBIF format)
- `*-ctor.zip` — constructor call counts (JSON format)

Current model location: `/speedy/apps/recommenders/downloads/models/photon/jre/jre/1.0.0-SNAPSHOT/`

ZIP entries use raw type names as paths (e.g., `java/util/HashMap.jbif`), no generics.

## Key Design Decisions

- **Type erasure before model lookup**: `Signature.getTypeErasure()` strips generic parameters from declaration signatures before looking up JBIF entries (e.g., `HashMap<K,V>` → `HashMap`)
- **Call + statics models are merged**: both JBIF models are loaded per type and combined with max-per-method, so a type present in both exposes instance and static methods
- **Merge strategy for probabilities**: when both pre-trained model and workspace data exist for a type, the higher probability per method is used (not workspace-takes-priority)
- **Constructor keys**: constructors are keyed `<init>#paramCount` everywhere in user data (workspace analyzer, acceptance tracking, boost lookup); pre-trained ctor ZIPs are matched by parameter signature with a param-count fallback
- **Object method demotion**: methods declared on `java.lang.Object` get zero frequency boost to keep them at the bottom of completion lists
- **Workspace analysis**: manual via Navigate > Analyze Workspace Method Calls, plus one automatic background run on first completion when no workspace data exists; re-analysis replaces workspace counts and resets acceptance counts (the scan already includes previously accepted completions)
- **Hot-path discipline**: no file I/O during completion (frequency data is cached, `CompletionTracker.getNormalizedData()` is memoized), and LCSS matching runs once per proposal with a cheap in-order pre-check before full enumeration
- **Name-only completion before an existing `(`**: method/constructor proposals never synthesize an argument list when the completed identifier is directly followed by `(` (e.g. `deleteAll|(Foo.class)` keeps its arguments); `SubsequenceProposal` strips a trailing `()` from the core completion and forces the delegate's overwrite decision (`fToggleEating`, via reflection) because JDT's insert mode always appends an argument list and newer JDT core parsers report a range up to the statement end, which keeps the parentheses even in overwrite mode

## Diagnostic logging

Completion misbehaviour has so far only been reproducible in a real workspace, so
`CompletionDiagnostics` appends one trace per accepted completion to the file named by the
`subwords_diagnostic_log_path` preference (Java > Editor > Content Assist > Subsequence Matching >
"Diagnostic log file"). Empty path = off, which is the default.

Each trace records the caret line, insert/overwrite mode and the `nameOnly` decision, the delegate
class, the core proposal and its required proposals with their ranges and completion strings, and
the replacement string plus resulting line *after* apply. A "fallback path" trace means the delegate
was not an `AbstractJavaCompletionProposal`; **no trace at all** means the accepted proposal did not
come from this plugin.

The delegate's replacement string is deliberately read only after apply — reading it earlier caches
it (`LazyJavaCompletionProposal.getReplacementString()`) and would change the behaviour being traced.
`SubsequenceApplyEndToEndTest.diagnosticLogRecordsTheApply` covers the trace itself.

## Testing

`org.eclipse.subsequence.jdt.tests` runs under tycho-surefire with `useUIHarness=true`, so the build
needs a display (`DISPLAY=:0 mvn clean verify`, or Xvfb). Besides the unit tests it drives real JDT
completion against a workspace it creates:

- `CompletionEngineProbeTest` — what jdt.core actually proposes, at the caret and at the reduced
  trigger offsets the computer uses. Documents that the engine reports the token under the *real*
  caret whatever offset it was triggered at, because `CompletionScanner` scans the whole identifier;
  do not assume the ranges drift
- `ConstructorCompletionApplyTest` — the same completion applied through plain JDT, as a baseline
- `SubsequenceApplyEndToEndTest` — the full plugin path (computer → `SubsequenceProposal.apply`) over
  a real viewer, across three source shapes (plain anonymous class, anonymous class in nested
  argument lists, two anonymous classes deep) × insert/overwrite × fill-argument-names

## Ancestry

Spiritual successor to Eclipse Code Recommenders (org.eclipse.recommenders). Uses the same JBIF model format and model ZIPs but with a simpler architecture — no Bayesian inference at completion time, just direct probability lookup from pre-trained models.
