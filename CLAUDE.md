# Subsequence Matching for JDT

Eclipse plugin providing enhanced content assist with subsequence matching and frequency-based method ranking.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn clean verify
```

Requires Maven 3.9+ (Tycho 4.0.8 refuses older versions) and a Java 21 JAVA_HOME (adjust the path to the local JDK install).

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
  - `preferences/` — preference page for model directory path and min prefix length; shows which model ZIPs were found in the configured directory
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

## Ancestry

Spiritual successor to Eclipse Code Recommenders (org.eclipse.recommenders). Uses the same JBIF model format and model ZIPs but with a simpler architecture — no Bayesian inference at completion time, just direct probability lookup from pre-trained models.
