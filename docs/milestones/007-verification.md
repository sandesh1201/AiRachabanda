# Milestone 007 — Claim verification

Claim kind, supporting notes, and objection history stay on Room schema version 6. A mathematical claim is verified only when local decimal arithmetic matches the claimed result. Malformed expressions, unsupported operations, division by zero, and oversized input are rejected. Participant text is not executed.

A locator is stored as an unverified citation. The retrieval interface returns immediately and does not fetch a page. A successful future retrieval still has to show that the source supports the claim.

An objection changes status only with a reason. Disagreement does not resolve it. New contradictory evidence reopens a resolved objection. Correcting a claim preserves the original statement, marks dependents for reassessment, and cancels pending work aimed at the corrected claim.

The synthesis brief lists findings, verified calculations, evidence, corrections, assumptions, alternatives, unresolved objections, and the stopping reason. The record-based synthesis remains the labeled fallback.

The accuracy helper scores answers supplied by a test against a held-out reference. Those scores are not a measurement of live AI accuracy. No live provider request and no live web retrieval are added.
