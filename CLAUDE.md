# Subsequence Matching for JDT

Eclipse plugin providing enhanced content assist with subsequence matching and frequency-based method ranking.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn clean verify
```

Produces update site ZIP at `org.eclipse.subsequence.jdt.repository/target/org.eclipse.subsequence.jdt.repository-1.0.0-SNAPSHOT.zip`.

## Project Structure

- `org.eclipse.subsequence.jdt/` — main plugin
  - `completion/` — `SubsequenceCompletionProposalComputer` — JDT content assist integration, applies subsequence matching + frequency boost to proposals
  - `callmodel/` — frequency boost system using pre-trained models
    - `CallModelIndex` — singleton managing ZIP-based models (call, statics, ctor) + workspace analysis data; merges both sources using max probability per method
    - `FrequencyBooster` — computes relevance boost (0-200) per proposal from model probabilities; skips `java.lang.Object` methods
    - `JbifParser` — parses JBIF (Bayesian Network) format from recommenders model ZIPs
    - `CtorModelParser` — parses constructor frequency JSON from ctor ZIPs
    - `WorkspaceAnalyzer` — background job analyzing method call frequency in user's workspace
    - `DiagnosticLog` — debug logging to `/data/tmp/subsequence.log` (temporary, can be removed)
  - `core/` — `LCSS` subsequence matching algorithm
  - `dialog/` — Open Type dialog alternative with subsequence matching
  - `preferences/` — preference page for model directory path and min prefix length
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
- **Merge strategy for probabilities**: When both pre-trained model and workspace data exist for a type, the higher probability per method is used (not workspace-takes-priority)
- **Object method demotion**: Methods declared on `java.lang.Object` get zero frequency boost to keep them at the bottom of completion lists
- **Diagnostic logging**: `DiagnosticLog` appends to `/data/tmp/subsequence.log` — temporary instrumentation for debugging the boost pipeline

## Ancestry

Spiritual successor to Eclipse Code Recommenders (org.eclipse.recommenders). Uses the same JBIF model format and model ZIPs but with a simpler architecture — no Bayesian inference at completion time, just direct probability lookup from pre-trained models.
