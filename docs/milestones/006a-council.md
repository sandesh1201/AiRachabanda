# Milestone 006A — Council discussion

Baseline: `20b95cde4901a471b7884bf4b2500924e9c4850b`.

The existing orchestration engine still owns sessions, roles, tasks, objections, and budgets. This milestone adds the discussion rules around it.

A quick question assigns research and synthesis only. Standard and deep sessions can assign a Devil's Advocate. Initial research prompts contain the user request and user-written context. They do not include another participant's answer.

A participant request records its sender, recipient or capability, purpose, claim, expected output, round, and depth. The engine rejects an unknown recipient, a repeated request, delegation deeper than two steps, and any instruction that tries to change billing, security, privacy, permissions, or the budget.

An imported critique creates an objection only when it quotes a stored claim. A verification line is stored only when it names a stored claim and a known outcome. The recorded synthesis is built from those rows. It is not a live model response, and it does not invent a source.

Assisted ChatGPT and Claude remain assisted. No live provider request is sent.
