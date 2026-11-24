# Milestone 012 experience

Baseline `a722502172a2bb48ceaf94800b529fd2c5ca0288` stays in place. Room schema version 9 is unchanged. Provider fault handling, pause, resume, stop, finish, skip, document import, document generation, and research behavior stay in the engine.

The interface uses the existing Material 3 theme with light, dark, and system appearance. The launcher mark is three original shapes meeting at one point. It is not copied from another AI product.

Home starts a discussion from a question, shows the four modes, lists recent conversations, and summarizes the actual provider state. Council keeps the conversation, controls, research, claims, and documents. Plan, task graph, and participant-request detail open from Technical details. Stop asks for confirmation before it calls the existing stop path.

Custom mode exposes the settings the planner uses: selected participants and the saved round limit, which is also the request budget. Concurrency and delegation depth stay the planner values for that mode. No unused slider is shown. Zero-cost rules stay on.

Activity filters stored events. A retrieved source link is separate from verification. History search, rename, continue, and delete confirmation use the stored conversations. Delete still removes that conversation only after confirmation.

The discussion foreground service remains `specialUse` with `START_NOT_STICKY`. It reports a user-started council discussion. It does not sync data, play media, track location, or continue after a force stop or reboot. `dataSync` would misstate that work. The service does not bypass Android background limits, and WorkManager is not used as a real-time guarantee.

Physical Galaxy Z Fold 6 testing has not been done. Expanded layout is the emulator width class only. OCR is not installed. Microsoft Office visual compatibility has not been tested. No live AI request is added.
