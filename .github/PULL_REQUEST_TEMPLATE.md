## What does this change?

## Why?

## How was it tested?
- [ ] `gradle :app:testDebugUnitTest`
- [ ] `cd backend && python -m pytest`
- [ ] Tried on a device / emulator (API level: )

## Security & consent checklist
- [ ] No new way to access a device without the owner's explicit approval
- [ ] No secrets, tokens or typed text are logged or stored
- [ ] New remote actions go through `SessionGuard` and the relay's scope policy
- [ ] Docs / CHANGELOG updated
