# RFC: Audit-Grade Compliance Reporting

This memo derives the architecture from the five requirements and defends the decisions that matter.
The thesis is that the requirements are not five independent features but one invariant: every
reported number is a deterministic function of the source data and the graph-resolved rules, and that
derivation is provable. Each decision below protects that invariant.

## Forces

- The reader is an audit examiner, not an end user. Correct is not enough; provably correct and
  provably not fabricated is the bar.
- Language models are non-deterministic and not auditable. Any number a model touches is, by
  definition, not reproducible and not defensible, so the model must be kept out of the numbers
  structurally rather than by instruction.
- The graph must carry weight. A graph that exists but is not involved in producing the numbers would
  fail the traceability requirement, so the graph has to be a real input to computation.
- A second firm is coming. It is the test of whether method is data, which can be switched, or code,
  which cannot.

## Decision: a deterministic engine with the model on the outside

The system is split into a deterministic core, which reads the holdings, resolves rules from the
graph, and computes, and a model periphery, which interprets guideline prose at ingestion and writes
narrative at output. The model client has no dependency on the holdings or the calculators.

The weak form of the no-model-numbers requirement is to instruct the model not to compute. That is
unfalsifiable, and an examiner will not accept it. The strong form is that there is no code path by
which model output becomes a reported number. The strong form is achieved two ways. First, by wiring:
the only structured output the model produces is a rule description naming a calculation method, and
that method is validated against a fixed registry, so a hallucinated method or number cannot reach a
calculator. Second, by verification: after the narrative is written, a firewall scans it and fails the
run if it contains any number not already in the computed output. This turns a claim into a test.

Reproducibility follows from the same design. The numeric path has no model, no clock, and no
randomness; it uses exact decimal arithmetic and a stable iteration order. Two runs are byte-for-byte
identical. The same mechanism that keeps the model out of the numbers also delivers reproducibility.

The rejected alternative is to let the model compute and then check the result against the answer key.
That only works where an answer key exists, it is not reproducible, and it fails the spirit of the
requirement even when the numbers happen to match.

## Decision: compute figures by traversing the graph

Computation does not begin from a hard-coded list of columns to sum. It begins from the graph. For
each figure the engine resolves, by traversal, which positions contribute, which limit applies, which
calculation method to run, and which passage defines the rule. Only then does a calculator sum the
positions. The graph path travelled and the chunk it ends at are emitted with the value.

This is what makes traceability real. The evaluator's stated test is to pick a figure and walk from it
to its source. If the path were attached after the number was computed, it would be decoration and
could drift from the truth. Because the traversal is the mechanism that selects the inputs, the
citation cannot be wrong about where the figure came from. The trace is correct by construction.

A figure whose path does not resolve to a source chunk is emitted as an error rather than a silent
value. An untraceable number is worse than a missing one, so it is surfaced.

## Decision: provenance on every node and edge

Every node and edge carries its source document, page, chunk, ingestion time, and extraction
confidence, and every limit and threshold has an edge to the chunk that defines it. Traceability is
only as strong as its weakest link; if one hop lacked provenance the chain would break. The same
confidence metadata drives the Gate 1 decision between automatic acceptance and human review, so the
data that enables the trace also enables the error-handling story.

## Decision: each firm is declarative configuration

A firm is a validated YAML file describing its method variants, read by the shared calculators at run
time. The tempting alternative, a separate calculator class per firm, is rejected because it puts
method in code, so a third firm would need a code change and a rebuild, which is exactly what the
requirement is testing against. It would also scatter a firm's differences across classes instead of
keeping them in one file an auditor can read and compare. The calculators do contain branches such as
"if fallen angels are included", but the branch is parameterised by configuration the firm owns, and
neither branch names a firm. The engine knows about a setting, not about Firm A or Firm B. That is the
line between configurable method and hard-coded firm.

## Decision: an append-only audit log demonstrated in code

Every stage writes an immutable event to a log exposed only through append and read operations, with
no update or delete, backed by a file opened in append mode. The requirement is to demonstrate
append-only in code rather than assert it as policy, because a policy is exactly what an examiner
distrusts. Removing the capability to mutate is the proof. The log captures the run start, the firm
configuration, the graph construction, the figure computation, the reconciliation result, the
traceability result, the firewall result, and the export, so a run can be replayed end to end.

## Decision: reconcile exactly, and read the real answer key

The target is exact reconciliation. Net asset value is exact and the arithmetic is decimal, so the
percentage and currency figures match the answer key with no tolerance. The Firm A answer key is read
directly from the provided spreadsheet rather than a transcription, so the comparison is genuine, and
the run reports a per-figure pass or fail and a numeric delta. Stating that a tolerance is zero, and
showing the deltas, distinguishes rounding from a method error.

## Human in the loop by design

Extraction is error-prone, so the graph has a mandatory human verification gate before any figure
trusts it, plus a per-item confidence gate during extraction. The system is autonomous on the
deterministic parts, where automation is safe, and gated on the interpretive parts, where it is not.

## Scope and what production would add

This is a one-week proof, not a production deployment. Out of scope by intent: production
authentication, secrets management beyond an environment variable for the model key, exhaustive
exception handling, and scale. For production one would add access control on the audit log and any
API, secrets in a vault, hash-chained audit events for tamper evidence, and a fuller extraction-review
interface. The error handling that is present covers the two failure modes the task names: a
low-confidence extracted item, held at the first gate, and an untraceable figure, returned as an
error.

## How the evaluation maps to these decisions

| What the evaluator does | The decision that makes it pass |
|-------------------------|---------------------------------|
| Run twice and diff the numbers | No model, clock, or randomness on the numeric path |
| Trace one figure to its source | Computation is graph traversal; provenance ends at a chunk |
| Switch from Firm A to Firm B with no code edit | Declarative configuration read by shared calculators |
| Verify the model produced no number | Wiring separation plus the firewall check |
| Reconcile to the answer key | Exact decimal reconciliation, answer key read from the spreadsheet |
| Inspect and replay the run | Append-only event log capturing the whole run |
