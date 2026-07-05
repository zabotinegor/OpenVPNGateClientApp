# Data Access (EF Core)

* Avoid N+1 queries
* Avoid loading unnecessary data
* Prefer projections (`Select`) over full entity loading
* Use `AsNoTracking` for read-only queries
* Ensure transactions are intentional and minimal
* Avoid client-side evaluation
* Keep queries explicit and predictable

## EF Core Migrations

* **All migrations must be created with `dotnet ef migrations add`** — never create migration files manually.
* Editing generated `Up`/`Down` code is allowed only for deterministic conflict resolution after the `dotnet ef` generation step.
* Each module owns its own `DbContext` and its own migrations folder; do not mix contexts.
* Five EF contexts exist: `OpenVPNGateContext`, `AppFeatureContext`, `AuthorizationContext`, `MessagingContext`, `VersionsContext`.
* See `AGENTS.local.md` for the exact per-machine `dotnet ef` command pattern.

## AppFeature Seed Data

* AppFeature runtime settings are seeded via `HasData` in `OpenVPNClientServer/AppFeature/AppFeature.Adapter/Persistence/EntityConfigurations/AppFeatureSeedConfiguration.cs`.
* When adding a new AppFeature entry: (1) add the constant to `AppFeature.Domain/Constants/FeatureNames.cs`, (2) add the `HasData` entry to `AppFeatureSeedConfiguration.cs`, (3) run `dotnet ef migrations add <Name>` for `AppFeatureContext` to generate the migration.
* **Do not insert AppFeature seed rows directly into migration `Up` methods** — always go through `HasData` in `AppFeatureSeedConfiguration.cs` so the EF model snapshot stays consistent.
