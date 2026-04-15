# Review Comment Style

## User-Facing Summary

Provide the review queue in Russian:

```markdown
### Review Queue

1. `file:line` - short summary
   - Status: `accept` | `discuss` | `reject`
   - Why: brief technical rationale
   - Scope: what should change

### Accepted For Fix

- Safe comments to implement now.

### Needs Discussion

- Comments that need clarification or tradeoff decision.
```

## GitHub Reply Style

Write replies in English.

Tone:
- calm
- technical
- specific
- non-defensive

Template:

```markdown
Thanks for the review. I checked this path again.

Current behavior preserves `<constraint>`, while the proposed change could introduce `<specific risk>`.

I suggest `<safer alternative>`.
```

## Decision Rules

- `accept`: clear correctness/safety/maintainability improvement.
- `discuss`: valid concern but unclear implementation direction.
- `reject`: introduces regression, unnecessary churn, or worse design.
