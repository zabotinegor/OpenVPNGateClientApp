# Error Handling & Logging

## Error Handling

* Do not swallow exceptions
* Do not use overly broad catch blocks
* Handle known failure cases explicitly

## Logging

* Use structured logging
* Logs must be actionable
* Do NOT log:

  * secrets
  * tokens
  * personal data
* Include context in logs
