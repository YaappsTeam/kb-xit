# Git & issue-tracking workflow

A portable description of the workflow this repository's history was built
with. It doesn't depend on anything specific to this project — copy this
file into another repository and it applies as-is. The two things it needs
from the host: a git remote hosted somewhere with pull requests (GitHub,
GitLab, etc.) and an issue tracker on the same platform (GitHub Issues is
what the examples below use, but the pattern maps to Jira, Linear, or
anything else with labels and parent/child issues).

## Why this exists

The goal is traceability without process overhead: at any point, "what
changed, why, and can I undo just this piece" should be answerable by
reading commit messages and issue comments, without reconstructing context
from memory or chat history. Every rule below serves that one goal.

## 1. Backlog shape: stories and subtasks, not a flat issue list

- **Stories** are user-facing or operator-facing outcomes, written as
  `As a <persona>, I want <goal>, so that <reason>` where that framing
  fits naturally. Label them `story`. Not every story needs subtasks — a
  small, single-PR piece of work can be a story with no children.
- **Subtasks** are the independently-committable pieces of a story, linked
  as native sub-issues (parent/child), not just referenced by text. Label
  them `subtask`. One subtask is usually one commit; a subtask too small
  to be its own commit probably belongs folded into a sibling instead of
  existing on its own.
- **Every story and subtask carries an acceptance-criteria checklist** as
  a markdown task list in its body (`- [ ] thing that must be true`).
  These get checked off as work lands — see §4.
- **Backlog labels** signal sequencing without a separate board: `next`
  (unblocked, reasonable to pick up now), `later` (valid but not urgent,
  explicitly not recommended to start yet — say why in the issue body),
  `blocked` (cannot proceed until some named external condition changes —
  name that condition in the issue body so anyone can tell when it's
  cleared).
- If the platform has no native "issue type" feature, labels plus native
  sub-issue linking substitute for it fully. Don't invent a parallel
  tracking file (a TODO.md, a spreadsheet) alongside the issue tracker —
  one source of truth.

## 2. Scoping a story before writing code

Before implementing, read the code the story touches and write down in
the issue body anything the original plan got wrong. This step is not
optional busywork — it's the difference between an issue that describes
intent and one that describes what actually happened.

Concretely: if a story's acceptance criteria assume an interface, a
schema, or an approach that turns out not to fit once you've read the
actual code, **say so in the issue before writing the code**, with the
reasoning. Then implement the better-fitting approach. This means:

- Nobody re-discovers the same wrong assumption later.
- The eventual PR description can say "this deviates from the plan,
  here's why" instead of silently doing something different from what
  the issue says.
- A reader six months later understands the codebase's actual shape was
  a discovery, not an oversight.

## 3. Branching and commit granularity

- One branch per story (or per tightly-related cluster of subtasks that
  can't ship independently of each other). Not one branch per subtask —
  that produces PR-confirmation overhead disproportionate to the size of
  each piece.
- **One commit per subtask**, with a commit message that names the
  subtask (`(story #24, subtask #43)` or equivalent) and states what
  changed and, briefly, why. Small, separately-revertable commits are the
  entire point — resist the urge to squash-as-you-go.
- Before every commit that touches code: run the full test suite. A
  subtask isn't done until its own tests pass *and* nothing else broke.
- Version bumps (if the project uses semantic versioning with an inline
  changelog-style comment, or any equivalent lightweight changelog)
  happen in the same commit as the change they describe, not batched at
  the end.

## 4. Opening and merging a pull request

- Open one PR per branch, once its subtasks are ready to be reviewed as a
  unit. The PR description summarizes each subtask's change, calls out
  any deviation from the original plan (per §2) explicitly, and ends with
  a test plan — a checklist of what was actually verified, not what
  should theoretically work.
- **Never merge without an explicit, separate confirmation for that
  specific PR.** Opening a PR and waiting is always required. A past
  approval to merge one PR is not standing authorization for the next
  one — ask again, every time. This is the single most important rule in
  this document: it's what keeps a human in the loop on every change that
  reaches the shared branch.
- **Merge with a regular merge commit, not squash, and not rebase.**
  Squashing collapses the per-subtask commits from §3 into one, which
  destroys the exact granularity the workflow was built to preserve — a
  bad subtask can no longer be reverted independently of a good one in
  the same PR. Use whatever the host calls "create a merge commit."
- After merging: close every subtask issue individually, each with a
  short **Resolution** section in its body naming the exact commit SHA
  (not just "see the PR") and noting anything that shipped differently
  than the acceptance criteria originally said. Check off the AC boxes
  that are now true. Then update the parent story the same way, and close
  it once every one of its subtasks is closed — a story with any subtask
  still open stays open, even if the main work is done, so partial
  completion is visible at a glance.
- If a repo-level or otherwise hard-to-reverse action becomes ready as
  part of a story (archiving a repository, deleting a branch, rotating a
  credential), track it as its own subtask and hold it open until a human
  explicitly says to proceed — the same confirmation rule as merging, but
  for actions bigger than a single PR.

## 5. Keeping the tracker honest after the fact

The tracker is only useful if it reflects reality continuously, not just
at creation time. Concretely:

- A subtask's body gets a **Resolution** section on close — what shipped,
  which commit, and any deviation from the plan. Don't just flip the
  issue to closed with no comment.
- If closing a PR auto-closes a parent issue (many hosts do this on
  "Closes #N" in the PR body) but leaves its sub-issues open and its
  checklist unchecked, that's a bug in the process, not a fact to leave
  standing — go back and close the children and check the boxes.
- When a design decision made during implementation contradicts something
  already written in a planning document (an architecture doc, a
  requirements doc, a phase plan), update that document in the same PR.
  A planning doc that describes a plan nobody followed is worse than no
  planning doc.

## 6. What this workflow deliberately doesn't do

- It doesn't require a subtask for every single file changed — subtasks
  are scoped to independently-meaningful pieces of work, not to file
  boundaries.
- It doesn't require a PR per subtask — see §3.
- It doesn't require the acceptance criteria to be exhaustive up front.
  They get corrected in the issue (§2) once the real shape of the work is
  known, and that correction is itself valuable history, not something to
  hide by editing the original text away.
