# Milestone 006B — Discussion planning

Baseline: `bf0d1bcbff787203c5926c198bcf55c72f0e43cb`.

The planner is deterministic. It uses the discussion mode, numbered parts, numeric expressions, the participant count, and the request budget. It does not call a model.

Numbered parts in Standard or Deep mode become research tasks plus one analysis task that waits for them. Independent tasks can be active together up to the mode's concurrency limit. A dependency cycle is not scheduled. Cancelling a task cancels the tasks that wait on it.

Delegation depth and fan-out follow the mode. Quick stays at one level. The execution gate rejects billing, credential access, arbitrary network access, code execution, and budget increases. The wording of a participant message is not what grants those permissions.

Initial task context contains the assigned part and user context. A later task can receive stored claims and source locators. A shortened context says it is not the original source.

The synthesis task receives a stored brief. The on-screen synthesis remains the record-based fallback and is labeled as not a live model response.

No live provider request is sent.
