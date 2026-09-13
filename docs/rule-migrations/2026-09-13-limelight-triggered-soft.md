# Limelight triggered-soft migration — 2026-09-13

## Scope

This governance change applies only to:

- `shared.limelight-check-result-validity`
- `shared.limelight-enforce-freshness-policy`

## Enforcement change

Before this migration, each rule used a `regex-required` check for `**/*.java`.
That enforcement returned exit 1 for a missing generic pattern and produced false
positives when a diff did not establish the necessary robot-specific context.

The 2026-09-13 user decision defines this boundary: the two rules now have
`checks: []` and conditional `reviewTriggers`. A matching Limelight type or
result-access pattern in an added Java line returns exit 0 with a soft review
prompt. A trigger match requests review; it does not prove a violation.

The original approved status, approvals, and evidence remain intact. This
migration introduces no robot-specific parameter and no unsupported safety
conclusion.
