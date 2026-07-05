# Coding Standards

## General

* Use clear, intention-revealing names
* Methods must be small and cohesive
* Avoid deep nesting
* Avoid duplication when it improves clarity
* Remove dead or unused code
* Avoid premature abstraction

## Nullability

* Respect nullable reference types
* Avoid unsafe null usage
* Validate inputs at boundaries

---

## ASP.NET API Guidelines

### Controllers / Endpoints

* Keep controllers thin
* Delegate logic to services
* Validate input explicitly
* Return correct HTTP status codes

### Responses

* Use consistent response structures
* Prefer `ProblemDetails` for errors
* Do not expose internal implementation details

### Model Binding

* Use explicit DTOs
* Do not bind domain entities directly

---

## Dependency Injection

* Use constructor injection only
* Avoid service locator pattern
* Use correct lifetimes:

  * Scoped for request-based services
  * Singleton only when safe
* Avoid over-injecting dependencies

---

## Async & Concurrency

* Use async/await consistently
* Do NOT use sync-over-async
* Propagate `CancellationToken` where supported
* Avoid fire-and-forget tasks unless explicitly safe
* Ensure thread safety where relevant

---

## Anti-Patterns to Avoid

* fat controllers
* service locator usage
* sync-over-async
* hidden side effects
* silent failures
* excessive abstraction
* leaking domain models into API
* uncontrolled logging
* implicit behavior
