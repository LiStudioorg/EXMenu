# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the right-hand column to match whatever vocabulary you actually use.

## Wayfinder labels

`/wayfinder` uses a separate label family, not triage roles. These are also created in this repo:

| Label                  | Applied to                                              |
| ---------------------- | ------------------------------------------------------- |
| `wayfinder:map`        | The map issue — the single canonical artifact           |
| `wayfinder:research`   | Child ticket: answer a question from primary sources    |
| `wayfinder:prototype`  | Child ticket: build a throwaway to settle a design call |
| `wayfinder:grilling`   | Child ticket: stress-test a plan with the user          |
| `wayfinder:task`       | Child ticket: ordinary implementation work              |

A ticket carries exactly one `wayfinder:<type>` label. These are orthogonal to the triage roles above: a map or ticket does **not** also get a triage label unless a triage pass actually assigns one.
