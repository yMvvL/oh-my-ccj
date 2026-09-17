## What this changes

<!-- One or two sentences. If it fixes an issue, link it. -->

## How it was verified

<!--
The command you ran and what it printed. "Should work" is not a result; an honest "not verified, and
here is why" is worth more than a confident claim that turns out false.
-->

```
./mvnw test
```

## Checklist

- [ ] `./mvnw test` passes (and node is installed, so the browser cases are not skipped)
- [ ] A fix comes with a test that fails without it
- [ ] Comments explain the *why*, including any alternative that was rejected
- [ ] No new runtime dependency (or: it was discussed in an issue first)
