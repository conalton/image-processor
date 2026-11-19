# Working notes

Notes for anyone working on this repository: people and coding agents alike.

## Project

A web application that turns an uploaded image into several versions: S3 upload by presigned URL, an SQS-triggered
Kotlin Lambda, task status in DynamoDB, a dead-letter function, a Function URL API and a Vue page; Terraform, on AWS
or LocalStack. The API is deliberately not throttled.
Layout: `backend/` (Gradle modules `shared`, `api`, `worker`), `build-logic/`, `frontend/`, `contract/`, `infra/`,
`smoke/`.

## Principles

- **One owner for every value.** A name, limit, prefix, timeout or rule is defined in one place; the rest receives it.
- **Values cross tool boundaries from Terraform to the application** (environment variables, `config.json` for the
  page). Terraform does not read application files; the application does not read Terraform files.
- **Use what already exists.** Look for a standard feature or a maintained library (a release or commits in roughly the
  last four months, not archived or deprecated; an exception states its reason) before writing our own mechanism.
  State the reason for a new dependency in the commit message.
- **Fix the cause, not the symptom.** If something does not fit cleanly, write down what was tried and the options.
- **Keep it small.** Add a mechanism only for a requirement or a documented decision.
- **Comments only where they help:** a non-obvious constraint or reason, not what the code or a name already says.

## Tooling

- Every external tool runs in a container; the host needs only Docker and Make. Entry points are `make` targets.
- Commands must not hang: waits and polling use a timeout or bounded retries; nothing interactive.

## Terraform

- Reference resource attributes instead of rebuilding names or ARNs; compute a derived value once in a `local`.
- Every variable has a `description`; account-specific values have no default; the provider version is pinned.
- Every Lambda has its own log group with explicit retention and its own least-privilege role.
- `depends_on` only where AWS checks permissions while creating a resource.
- Files by AWS service or function (`s3.tf`, `functions_<name>.tf`), not one large `main.tf`.

## Code structure

- Split by responsibility. A handler parses, dispatches and maps the result; the work happens in the classes it calls.
  No god files (split past about 200-300 lines); no `utils`, `helpers` or `common`.
- Infrastructure values come from environment variables, read once at start-up into a typed configuration. Application
  rules (limits, status model, error codes) live in Kotlin, in `shared` when both functions use them.

## Kotlin

- Kotlin on Java 21, readable by a Java or TypeScript developer; follow the
  [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Use `val`, `data class`, `sealed interface` with `when`, null safety, `require` / `check`, constructor parameters
  for dependencies. Avoid `!!`, `lateinit`, reflection, global mutable state and operator overloading of our own.
- Expected outcomes are `sealed` values, not exceptions; untyped input becomes a `data class` once, at the edge.

## TypeScript and Vue

- `strict` mode, no `any`; `<script setup lang="ts">`; all API calls in one module; no `v-html`.
- API types are generated from the OpenAPI spec at build time and not committed.

## Tests

- Test behaviour, not implementation. No tests that mirror the code or assert SDK calls.
