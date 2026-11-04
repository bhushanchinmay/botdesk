# 4. Approval is bound to a commit SHA

Status: accepted

**Context.** A human approves after reading a diff. Code could change between approval and release.

**Decision.** Approving records the head commit. Release refuses if the branch head differs.
Approving an output version that changed after the request was raised is refused too.

**Consequences.** What you reviewed is exactly what ships, like optimistic locking with a version column.
Any later change needs approval again.
