# Image processor

Upload a JPEG or PNG on a web page, and a background job turns it into four versions. The page shows the status, and
when the job is done you can view and download the original and every version.

Kotlin Lambdas, S3, SQS, DynamoDB and a Vue 3 page, deployed with Terraform to LocalStack or AWS. Every tool runs in
Docker through `make`.

## Why it looks like this

The whole stack is built to run on the **free LocalStack image** (`4.14.0`, no account, no token), and the same
Terraform deploys it to AWS. The free image has no CloudFront and no API Gateway v2 (HTTP API), so the project uses
what it has:

- The page is static files in a **public S3 bucket**. In production this would be CloudFront in front of a private
  bucket.
- The API is a **Lambda Function URL**, not API Gateway. Because of that the API has no throttling (see
  [Limitations](#limitations)).
- The page, the API and the images bucket are three different origins, so both the API and the bucket have CORS rules
  for the page.
- A static page has no environment variables, so Terraform writes the API address and the retry budget into
  `config.json` next to the page.

It is a demo project, not a production service: there are no user accounts, and an AWS stack should be destroyed after
use.

## Run it on LocalStack

You need Docker Engine with the Compose plugin 2.40 or later and GNU Make. Everything else (JDK, Node.js, Terraform,
AWS CLI) runs in containers; the versions are in `versions.env`. LocalStack needs access to `/var/run/docker.sock`
(it starts the Lambda containers through it) and a free port 4566 on `127.0.0.1`. Plan for about 8 GB of disk and at
least 2.5 GB of memory, swap included (details in [MEASUREMENTS.md](MEASUREMENTS.md)).

```
make deploy-localstack     # builds, starts LocalStack, applies Terraform, checks that the API answers
make output-localstack     # prints the outputs; open page_url in a browser
make smoke-localstack      # sends the page's requests, and some that must be refused; ends with "smoke test passed"
make destroy-localstack    # removes the stack; make localstack-down stops the containers
```

Open `page_url`, choose a JPEG or PNG up to 20 MiB and wait for `Done`. The first run downloads the images and
dependencies; every command inside a container has a time limit.

**Host names.** All names the browser uses end with `.localhost`, for example the page
`http://image-processor-000000000000-eu-north-1-web.s3.localhost:4566/index.html`. Chrome, Edge and Firefox resolve
`*.localhost` to the loopback address themselves ([RFC 6761](https://www.rfc-editor.org/rfc/rfc6761)). If yours does
not, add the page, the API and the images bucket host names to the hosts file with `127.0.0.1` (the API host changes
with each new stack).

**Browsers.** Chrome or Edge 116, Firefox 124, Safari 17.4 or later (the page uses `AbortSignal.any`).

**Newer LocalStack images.** Images released since 23 March 2026 need an auth token
([LocalStack blog](https://blog.localstack.cloud/localstack-single-image-next-steps/)); to use one, set
`LOCALSTACK_TAG` in `versions.env`, and `compose.yaml` passes `LOCALSTACK_AUTH_TOKEN` through if you set it.

**Restart and cleanup.** LocalStack keeps no state after a restart, but the Terraform state still lists the stack.
To start again: `make localstack-down localstack-reset deploy-localstack`. To remove all containers, images
and volumes, run `make destroy-localstack` while LocalStack runs, then
`HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose --env-file versions.env down -v --rmi all` and
`docker image rm public.ecr.aws/lambda/java:<LAMBDA_JAVA_TAG>` (the tag is in `versions.env`).

## Run it on AWS

Copy `infra/aws.tfvars.example` to `infra/aws.tfvars` (ignored by git) and fill in the account id. The region is set
only there, in `aws_region`; change it only when no stack is deployed. For alarm e-mails, set `alarm_email` and confirm
the subscription. Then:

```
make deploy-aws ARCH=arm64    # or x86_64; there is no default, so a live stack never switches by accident
make output-aws               # open page_url in a browser
make smoke-aws                # also checks the Function URL's CORS preflight
make destroy-aws
make state-aws                # lists what is left in the state, or says nothing is
```

The `*-aws` targets use the `default` profile from `~/.aws` (mounted read-only); on EC2 with an instance role an empty
`~/.aws` is enough. The credentials must be able to create IAM roles, Lambda functions, S3 buckets, SQS queues, a
DynamoDB table, an SNS topic, CloudWatch alarms and log groups. The page bucket needs a public bucket policy, which
fails if `BlockPublicPolicy` is on for the whole account. The Function URL is public, so set up an AWS Budget alert
before you deploy.

## Architecture

```
Page (Vue build in its own public S3 bucket) --POST/GET /api/tasks--> API Lambda (Function URL) --> DynamoDB
  |  presigned PUT (signed: type, size, If-None-Match, fixed headers)        one item per task, status ^
  v                                                                                                    |
Images bucket --ObjectCreated--> SQS --> Worker Lambda: decode once, write 4 versions, set DONE -------+
                                  | 5 receives
                                  v
                  dead-letter queue --> Dead-letter Lambda: set FAILED   (2 alarms -> SNS)
```

1. The page sends `POST /api/tasks` with the file name, type and size. The API stores a `PENDING_UPLOAD` task and
   returns its id and a presigned PUT URL, valid for 10 minutes.
2. The browser uploads the file straight to S3. S3 notifies SQS, and the worker marks the task `PROCESSING`, decodes
   the image once, writes the versions, then sets the final status: `DONE`, `INPUT_REJECTED` (the image is bad) or
   `FAILED` (something broke on the server side).
3. A message that fails 5 times moves to the dead-letter queue. The dead-letter function marks its task `FAILED`, and
   an alarm sends a message to an SNS topic.
4. The page polls `GET /api/tasks/{id}` (after 1 s, 2 s, 3 s, then every 5 s) and keeps the task id in its URL, so a
   reload after the upload continues where it was. A `DONE` task has view and download links, valid for 60 minutes.

**Versions:** `medium` (longest side up to 1024 px, JPEG), `thumbnail` (256 px, JPEG), `grayscale` (1024 px, JPEG)
and `png` (1024 px, PNG). Smaller images are not upscaled. EXIF orientation is applied for JPEG and PNG.

**Limits:** JPEG and PNG only, up to 20 MiB (signed into the upload URL and checked again by the worker) and
16 megapixels (read from the header before decoding).

**Storage:** the images bucket holds `originals/{id}` and `derived/{id}/{version}.{ext}`; DynamoDB has one item per
task. Files and items expire after `retention_days` (7 by default) and are deleted soon after.

## Design notes

**Contract first.** `contract/openapi.yaml` is the source of the API. At build time, Fabrikt generates the Kotlin
models from it and Hey API generates the TypeScript types and Valibot validators for the page. Hibernate Validator
checks the create request against the constraints of the spec. Contract tests call the real handler for every path,
method and status in the spec and validate each response body. Both sides ignore fields they do not know, so a new
optional field breaks nothing. A new status or code does: an open page shows an unexpected response until it is
reloaded.

**Adding an image version.** Each version is an object of a class that implements `ImageTransformation`. It gets the
decoded image (as a `SourceImage`) and returns a new one; decoding, encoding and storage happen outside. A new version
is one line in `VARIANT_CATALOGUE`, plus a class if no existing one does the job:

```kotlin
val VARIANT_CATALOGUE: List<ImageTransformation> =
    listOf(
        ResizeTransformation("medium", "Medium", maxSide = 1024, format = ContentType.IMAGE_JPEG),
        ResizeTransformation("thumbnail", "Thumbnail", maxSide = 256, format = ContentType.IMAGE_JPEG),
        GrayscaleTransformation("grayscale", "Grayscale", maxSide = 1024, format = ContentType.IMAGE_JPEG),
        ResizeTransformation("png", "PNG", maxSide = 1024, format = ContentType.IMAGE_PNG),
    )
```

**Retries and failures.** S3 events go through SQS, not straight to the Lambda. A direct call retries at most twice,
while a queue lets the project set the number of attempts, the time between them and a dead-letter queue, and it caps
the worker's concurrency without reserving capacity. The worker writes all files first and the `DONE` status last,
and every status change in DynamoDB is a conditional write, so a repeated or parallel delivery does no harm. The
queue's visibility timeout is six times the worker timeout (138 s), which gives a retry budget of about 11.5 minutes
before a message reaches the dead-letter queue. Expected
outcomes are Kotlin `sealed` types, not exceptions; the task state is one too, so each status carries exactly its own
data.

**Uploads and access.** Image bytes never pass through the API. The presigned PUT signs the content type, the exact
size and `If-None-Match: *`, so S3 refuses a file of another size or type, or a second upload. The images bucket
blocks all public access and accepts only TLS. Each function has its own least-privilege role and its own log group.
An image is untrusted input: its size and pixel count are checked before it is decoded.

**The page.** Vue 3 with strict TypeScript. The page's flow is one state machine: a pure `transition(state, event)`
function instead of many separate flags. When the user starts a new upload or leaves a task, the requests of the old
one are cancelled. The page stops polling after an hour, or later if the retry budget needs it.

## Make targets

| Target | What it does |
|---|---|
| `build` | the page and the two Lambda zips |
| `frontend` | only the page |
| `test` | all backend and page tests (starts DynamoDB Local for the adapter tests) |
| `backend-test`, `frontend-test` | only the Gradle tests; only the page's Vitest tests |
| `lint`, `format` | ktlint (Spotless), ESLint and a type check of `smoke/`; `format` applies fixes |
| `tf-check`, `tf-fmt` | `terraform fmt -check` and `validate`; `tf-fmt` formats |
| `tf-lock`, `frontend-lock`, `smoke-lock` | update the provider lock file or a `package-lock.json` |
| `deploy-localstack`, `deploy-aws` | build, apply Terraform and check that the API answers |
| `smoke-localstack`, `smoke-aws` | run `smoke/smoke.ts` against the deployed stack |
| `output-localstack`, `output-aws` | the Terraform outputs (`page_url`, `function_url`, log groups, `aws_region`) |
| `destroy-localstack`, `destroy-aws` | `terraform destroy` |
| `cli-localstack`, `cli-aws` | one AWS CLI command against the stack: `make cli-localstack ARGS='s3 ls'` |
| `localstack-up`, `localstack-down`, `localstack-reset` | start LocalStack; stop all containers; delete the LocalStack Terraform state |
| `state-aws` | the resources left in the AWS state |

A bare `make` prints where the targets are listed and fails, so nothing that expects a build passes without one. For
IntelliJ IDEA, open the repository root (it needs JDK 21 on the host).

## Tests

- **Backend** (JUnit): the image pipeline, the task processor, the dead-letter function and the API endpoints; the
  DynamoDB adapter against DynamoDB Local; contract tests against the spec.
- **Page** (Vitest): the state machine's waiting rules, the URL and history handling, what each state shows, and how
  API answers are read.
- **Smoke test** (TypeScript, Node 24): sends the page's requests, and some that must be refused, to a deployed stack,
  LocalStack or AWS.

## Project layout

- `backend/shared`, `backend/api`, `backend/worker`: Gradle modules. `shared` holds the generated contract models and
  the code both functions use. The dead-letter function ships in the worker's zip.
- `build-logic/`: shared Gradle build settings (convention plugins).
- `frontend/`: the Vue 3 page.
- `contract/openapi.yaml`: the API spec.
- `infra/`: Terraform for AWS and LocalStack (only the tfvars file differs); `infra/modules/lambda_function` is the
  module used by all three functions.
- `smoke/`: the smoke test.

## Limitations

- No accounts and no throttling. A Function URL can be limited only through reserved concurrency, which takes capacity
  from the whole account. The bill has no cap: use a Budget alert and destroy the stack after use.
- Links expire: the upload URL after 10 minutes, view and download links after 60. They can stop working earlier,
  because the API signs them with the Lambda role's temporary credentials. Reloading the page gives fresh view and
  download links; for an expired upload URL, choose the file again.
- The first API request after idle waits for the JVM to start, about 2 s on AWS when last measured (details in
  [MEASUREMENTS.md](MEASUREMENTS.md)).
- If every attempt fails or times out, the task shows `FAILED` only after the retry budget, about 11.5 minutes.
- JPEG and PNG only. CMYK JPEGs are accepted, but their colors depend on the file. Any warning from the image reader
  rejects the image.
- On LocalStack, IAM is not enforced and the Function URL's CORS settings are not applied, so the smoke test skips
  that check there.
