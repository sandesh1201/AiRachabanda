# Milestone 014 adaptive layout

Window width uses Material 3 window size classes: compact below 600 dp, medium from 600 dp through 839 dp, and expanded at 840 dp and wider. The class comes from the current window, not a device name.

`androidx.window:window:1.5.0` is a direct dependency. It was already present in the Gradle cache as a transitive dependency of `material3-window-size-class`. Its license is Apache 2.0. `material3-adaptive` was not added because that artifact was not cached.

Compact keeps one column and bottom navigation. Medium uses the existing navigation rail and one content column. Expanded can place the conversation beside one supporting pane. The supporting pane can show participants, research, evidence, objections, attachments, documents, or technical details. Conversation only hides that pane. Tabletop posture, when WindowManager reports a horizontal half-opened separating fold, stacks the panes with a gap. A fold that does not separate or occlude does not reserve a gap. When no fold is reported, the layout uses the window width.

Draft text, the selected pane, and the conversation scroll value use `rememberSaveable`. The discussion start effect still runs once per conversation identifier. A size change does not start a provider request.

The Pixel 9a emulator does not report a fold. Screenshots at resized windows are window-size simulation. A physical Galaxy Z Fold 6 was not tested.

Schema version 9 is unchanged. No AI inference request is added.
