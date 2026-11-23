# Devil's Advocate

Status: Phase 1 orchestration requirement. Specified in Milestone 003A. The assisted Claude prompt is implemented. The automated review engine, objection persistence, and accuracy benchmark are not implemented. No live inference is authorized.

## Role

The Devil's Advocate is an orchestration role. It is not a decorative label and it does not insert a simulated AI message.

The role runs after initial responses exist. It tries to disprove the preliminary conclusion using only the supplied material and evidence the reviewer can actually support. Disagreement without a stated basis is out of scope.

The role must:

1. Start from independent initial responses that are already in the conversation.
2. Identify claims, assumptions, calculations, citations, and proposed conclusions.
3. Assign a reviewer that is separate from the author of the claim being tested.
4. Attempt to disprove the preliminary conclusions.
5. Identify unsupported assumptions.
6. Identify factual and mathematical errors.
7. Identify logical inconsistencies.
8. Identify missing context and relevant counterexamples.
9. Identify weak, fabricated, outdated, or contradictory citations.
10. Propose plausible alternative explanations only when the material supports them.
11. Send specific objections back to the original participants.
12. Request evidence-supported rebuttals or corrections.
13. Assign independent evidence verification when a different underlying model is available.
14. Revise the proposed conclusion.
15. Preserve unresolved objections in the final answer.

The role must not invent counterarguments or evidence, treat majority agreement as proof, reject a minority opinion automatically, fabricate citations, generate a confidence percentage, continue without a stopping condition, exceed the request budget, or trigger paid inference.

A different hosting provider running the same underlying model is not an independent reviewer. Until a message stores an underlying model identity, the application discloses that the critique is not independent verification. A same-model self-critique may be stored as a fallback and must keep that disclosure.

## Participation

Automated review is permitted only when the zero-cost policy already allows that provider and the remaining automated request budget is at least one. In the current build that budget is zero and Claude automation stays off, so `automatedReviewPermitted` is false.

Assisted review is the current path. Claude is eligible. The application prepares a targeted critique prompt from provider responses already stored in the conversation, creates an assisted task in `AWAITING_USER_RESPONSE`, and imports the user's returned text into that task's discussion round. The import remains user-supplied and provider-attributed. Skip and defer still create no message.

If the conversation has no provider response, or Custom review depth is zero, no prompt is prepared and the reason is shown.

## Research modes

| Mode | Review |
| --- | --- |
| Quick | One critical review when a response exists. The planned round count is 1. |
| Standard | One dedicated Devil's Advocate review, then a rebuttal and correction round, before synthesis. |
| Deep Research | Up to three critical rounds when the material justifies them. Independent reviewers are requested when a different underlying model is known. Revised conclusions are reassessed. Review stops when the budget is exhausted, no new high-severity objection appears, or the remaining objections are explicitly unresolved. |
| Custom | The saved maximum-round preference is the review depth, from 1 to 8. A depth of zero prepares no critique and discloses reduced verification. |

Improved accuracy is not claimed. The benchmark below has not been run.

## Objection record

Each objection is a separate record. Original provider responses are not overwritten.

| Field | Meaning |
| --- | --- |
| Objection ID | Stable local identifier. |
| Target claim ID | The claim being tested. |
| Authoring participant | The provider assigned the Devil's Advocate role. |
| Underlying model identity | Recorded model name when known. Empty when unknown. |
| Discussion round | The round that produced the objection. |
| Category | Unsupported assumption, factual error, mathematical error, logical inconsistency, missing context, counterexample, weak citation, or alternative explanation. |
| Severity | Low, medium, or high. |
| Explanation | The objection itself. |
| Supporting evidence | Evidence supplied by the reviewer. Empty when the reviewer did not supply any. |
| Requested verification | What a later participant must check. |
| Rebuttal | The later response, when one exists. |
| Resolution | Open, confirmed error, corrected, rebutted with evidence, unresolved, or insufficient evidence. |

Model-generated objections and evidence are untrusted until a later validation step accepts them. Room storage for these records is the next persistence change. This milestone keeps the critique request in the existing research-task and imported-response tables so the returned text stays attached to the conversation, provider, and round.

## Accuracy benchmark

A later milestone compares, on held-out questions with independently verifiable answers:

- Single-model answers.
- Multi-model independent responses.
- Multi-model discussion without a Devil's Advocate.
- Multi-model discussion with a Devil's Advocate.

Measure factual correctness, citation validity, calculation accuracy, error correction, unnecessary objections, unresolved contradictions, latency, and request consumption. Do not choose questions because they favor this architecture. Do not publish an accuracy claim from that study until it has been run.
