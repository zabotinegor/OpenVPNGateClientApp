# MQ-SUB05-002: Primary URL attempted first on foreground

**AC:** AC-3 (primary-first on reconnect cycle)

## Steps

1. With app installed and launched, check logcat for SSE connection URL

## Expected

- `SSE connecting (attempt=0) url=<PRIMARY_URL>` — primary URL used on first attempt

## Result: PASS

Log: `SSE connecting (attempt=0) url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events`  
Primary URL confirmed as first attempt.
