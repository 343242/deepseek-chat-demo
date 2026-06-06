# Remove messaging `enabled` toggle

## Goal

Remove the `enabled` feature flag from messaging config. Messaging is always active — remove NoOpMessageBus dead code path.

## Requirements

* Remove `enabled` field from `MessagingProperties` record
* Remove `NoOpMessageBus.java`
* Remove `NoOpMessageBusTest.java`
* Simplify `MessagingAutoConfiguration` — single path, always create `RocketMQMessageBus`
* Remove `MESSAGING_ENABLED` env var from `application.yml`
* All other `${ENV_VAR:default}` overrides in messaging section stay unchanged

## Acceptance Criteria

* [ ] `MessagingProperties` has no `enabled` field
* [ ] `NoOpMessageBus.java` deleted
* [ ] `NoOpMessageBusTest.java` deleted
* [ ] `MessagingAutoConfiguration` has single bean creation path
* [ ] `application.yml` has no `MESSAGING_ENABLED` / `enabled` reference
* [ ] All remaining unit tests pass
* [ ] Build compiles cleanly

## Out of Scope

* Changes to RocketMQMessageBus core implementation
* Changes to other env var overrides
* Integration test fixes

## Technical Notes

* Files to modify: `MessagingProperties.java`, `MessagingAutoConfiguration.java`, `application.yml`
* Files to delete: `NoOpMessageBus.java`, `NoOpMessageBusTest.java`
* `MessagingHealthIndicator` only depends on `MessageBusManagement` interface — no direct NoOp ref
