# Measurements

The numbers behind the memory and timeout values of the three functions, the API's cold start and the host
requirements in the [README](README.md).

## Conditions

- All Lambda measurements ran on AWS in `eu-north-1`, on the Lambda runtime Java 21.
- The architecture is `x86_64` unless a row names `arm64`. AWS suggests `arm64` for compute-intensive work
  ([instruction set architecture](https://docs.aws.amazon.com/lambda/latest/dg/foundation-arch.html#foundation-arch-adv)),
  so `x86_64` was expected to give the slower worst case.
- The numbers were measured while the project was being built and were not repeated on the final code. Steps 1 and 2
  of the worker table ran before the worker scaled in halving steps and changed its gray conversion, so their output
  differs from the current one. From step 3 on, the image output is the same as now, except for PNG files with an
  `eXIf` orientation, which the worker now rotates. Hibernate Validator was added to the API after the cold-start
  measurement.
- The samples are small: 1 to 15 runs per row, in one region.

## Current values

| Function | Memory | Timeout | Set in |
|---|---|---|---|
| Worker | 1769 MB | 23 s | `worker_memory_mb`, `worker_timeout_seconds` in `infra/variables.tf` |
| API | 1769 MB | 10 s | `api_memory_mb`, `api_timeout_seconds` in `infra/variables.tf` |
| Dead-letter function | 512 MB | 10 s | `infra/functions_dead_letter.tf`, `dead_letter_timeout_seconds` in `infra/locals.tf` |

The queue's visibility timeout is six times the worker timeout (138 s), and a message reaches the dead-letter queue
after 5 receives, so the retry budget is 5 x 138 s = 690 s (`infra/locals.tf`). Both factors are the minimums that
[the SQS event source page](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html) recommends.

## Worker

### Test images

All timing runs used PNG files. There are no JPEG timings. File sizes are in decimal megabytes (1 MB = 1,000,000
bytes).

| Name | Format | Bit depth | Dimensions | File size |
|---|---|---|---|---|
| 8-bit square | PNG, RGB noise | 8 | 3960x3960 (15.68 megapixels) | 20.8 MB |
| 8-bit noise | PNG, RGB noise | 8 | not recorded | 20.4 MB |
| 16-bit noise | PNG, RGBA noise | 16 | not recorded | 20.4 MB |
| 16-bit square | PNG, RGBA noise-style | 16 | 3960x3960 | 20.4 MB |
| `col8.png` | PNG, RGBA | 8 | 1x16,000,000 | 125 KB |
| `sq8.png` | PNG, RGBA | 8 | 4000x4000 | 87 KB |
| `col-noise.png` | PNG, RGBA noise | 16 | 1x16,000,000 | 20.8 MB |

### How the runs were made

- One task at a time on each stack, after a forced cold start (a changed dummy environment variable), unless the
  Measure column says warm.
- Duration is the invocation, which the timeout limits. Init comes before it, with its own limit of 10 s, and is
  outside the timeout unless it runs past those 10 s: then Lambda runs it again at the first invocation, under the
  timeout ([execution environment](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtime-environment.html)).
- "Image code alone" (step 2) is the S3 read, the decode and the four renders, without the DynamoDB writes and the S3
  PUTs.
- Steps 1 and 2 ran with the runtime's Log4Shell patch switched off (`AWS_LAMBDA_DISABLE_CVE_2021_44228_PROTECTION`),
  which the stack does not set.
- From step 4 on, a run counted only when its log had `Init Duration`, and no run was retried. Steps 4 and 5 each ran
  on a new deploy of one stack, switched between architectures with `make deploy-aws ARCH=...`; step 6 ran on two
  stacks at the same time, one per architecture.
- A dash in the Timeout column means the timeout was not recorded. In steps 5 and 6 the timeout was 900 s, so no run
  was cut off.

### Results

| Step | File | Arch | Memory, MB | Timeout, s | n (runs) | Measure | Time min / median / max, s | Outcome or note |
|---|---|---|---|---|---|---|---|---|
| 1 | 8-bit square | `x86_64` | 1024 | - | 5 | Init + Duration | 7.192 / 7.548 / 7.655 | |
| 1 | 8-bit square | `x86_64` | 1769 | - | 5 | Init + Duration | 5.140 / 5.262 / 5.492 | 30 % shorter, 20 % more GB-s |
| 1 | 8-bit square | `x86_64` | 1024 | - | 5 | warm Duration | 3.920 / 4.194 / 4.210 | |
| 1 | 8-bit square | `x86_64` | 1769 | - | 5 | warm Duration | 2.580 / 2.652 / 2.906 | 37 % shorter, 9 % more GB-s |
| 2 | 8-bit noise | `x86_64` | 1024 | - | 15 | Init + image code alone | 6.4 / 6.5 / 6.7 | |
| 2 | 16-bit noise | `x86_64` | 1024 | - | 15 | Init + image code alone | 16.0 / 16.3 / 16.5 | |
| 3 | 16-bit square | `x86_64` | 1024 | 23 | 1 | Duration via the page | 15.13 | `DONE`, 7.9 s left |
| 4 | `col8.png` | `arm64` | 1024 | 23 | 15 | cold Duration | 10.72 / 13.52 / 13.72 | `DONE` |
| 4 | `col8.png` | `x86_64` | 1024 | 23 | 5 | cold Duration | 12.24 / 12.42 / 12.92 | `DONE` |
| 4 | `sq8.png` | `arm64` | 1024 | 23 | 3 | cold Duration | 2.38 / 3.11 / 3.19 | `DONE` |
| 4 | `sq8.png` | `x86_64` | 1024 | 23 | 3 | cold Duration | 3.23 / 3.36 / 3.51 | `DONE` |
| 4 | `col-noise.png` | `arm64` | 1024 | 23 | 3 | cold Duration | 21.29 / 21.58 / 22.11 | `DONE` |
| 4 | `col-noise.png` | `x86_64` | 1024 | 23 | 3 | cold Duration | 22.51 / 22.96 / 23.00 | 1 timeout, counted as 23.00 |
| 5 | `col-noise.png` | `arm64` | 1024 | 900 | 10 | cold Duration | 17.60 / 21.51 / 21.88 | `DONE` |
| 5 | `col-noise.png` | `x86_64` | 1024 | 900 | 5 | cold Duration | 23.32 / 23.44 / 23.52 | `DONE`, all 5 over 23 s |
| 6 | `col-noise.png` | `arm64` | 1769 | 900 | 5 | cold Duration | 10.79 / 13.24 / 14.01 | `DONE` |
| 6 | `col-noise.png` | `x86_64` | 1769 | 900 | 5 | cold Duration | 13.61 / 13.79 / 14.27 | `DONE` |

### What the table shows

- **The shape costs more than the file size.** At the same 16 megapixels and bit depth, the 1 px wide `col8.png` took
  more than four times as long as the square `sq8.png` on `arm64` and 3.7 times on `x86_64` (step 4 medians). The
  versions share halving steps when they scale down. Every version of a 1 px wide image is 1 px wide, so the shared
  steps cannot start, and each of the four versions halves the 16-megapixel original by itself.
- **The slowest input found is `col-noise.png`.** At 1024 MB on `x86_64` it took 22.51-23.00 s in step 4 (one run
  timed out at 23 s) and 23.32-23.52 s in step 5 (all over 23 s).
- **16-bit takes longer than 8-bit.** The 16-bit noise PNG took 2.5 times as long as the 8-bit noise PNG (step 2
  medians, 16.3 s and 6.5 s).
- **The architecture makes little difference.** `x86_64` was 8 % faster on `col8.png` and 4-9 % slower on `sq8.png`
  and `col-noise.png` (medians), so the difference does not go one way.
- **Init** took 0.79-1.43 s in steps 4 to 6. On `arm64` some runs took about 20 % less time than the rest, all with an
  Init under 0.85 s (8 of 31 runs at 1024 MB, 1 of 5 at 1769 MB); the logs do not show why. The minimum of every
  `arm64` row is such a run, except step 4 `col-noise.png`, which had none.
- **Memory used** at 1769 MB peaked at 626-640 MB (step 6).

A 2 GiB object put straight into the images bucket, bypassing the API, was rejected by the worker in about 0.3 s with
`FILE_TOO_LARGE`, with about 175 MB used, no retry and nothing in the dead-letter queue.

### Why 1769 MB

At 1769 MB a function has the equivalent of one vCPU
([memory](https://docs.aws.amazon.com/lambda/latest/dg/configuration-memory.html)). The worker decodes and renders on
one thread, so no larger size was measured. From 1024 MB to 1769 MB the worker got clearly faster for a small extra
cost:

- On `col-noise.png` the median Duration fell by 38 % on `arm64` and by 41 % on `x86_64` (step 6 against step 5), for
  10 % and 6 % more GB-seconds per task (medians of the billed duration, which includes Init, times the memory).
- On the 8-bit square the warm Duration fell by 37 % for 9 % more GB-seconds (step 1 medians).

Of the two sizes measured, 1769 MB is the better trade-off.

### Why 23 s

The timeout was first set at 1024 MB: three times the slowest cold run of step 1 counted with its Init,
3 x 7.655 s = 22.97 s, rounded up to 23 s. Steps 4 and 5 then found `col-noise.png`, which at 1024 MB on `x86_64` ran
close to or over 23 s.

At 1769 MB the slowest of the 10 runs in step 6 took 14.27 s, 8.7 s below the timeout. The timeout is 1.6 times the
slowest run, measured on 5 runs per architecture on one day. A run 61 % longer than that one would reach the timeout.
The timeout stays at 23 s, so the visibility timeout (138 s), the retry budget (690 s) and the page's wait stay as they
are. On the failure path on AWS, with the page and no backlog, the page showed `FAILED` at 701 s, against the 690 s
budget.

## API

**Memory: 1769 MB.** At 1024 MB the Init was the same and the first `POST` about 0.4 s slower (medians of 6 cold runs
each). This comparison ran during development, when the API also served the page.

**Timeout: 10 s.** The API's DynamoDB client ends a call at 70 % of the function timeout (7 s, `api_sdk_call_timeout_ms`
in `infra/locals.tf`), so a stalled call ends with the contract's 500, not the Function URL's 502.

### Cold start

The page comes from S3, so the first request that starts the API is the first `POST /api/tasks`. The function
(1769 MB) was forced cold by a configuration update before each of three probes. Each probe was a `POST` with `curl`
from an EC2 host over a new TLS connection, and the next `POST` followed at once. Measured before Hibernate Validator
was added to the API.

| Request | Time |
|---|---|
| First `POST` (3 probes, `curl` total) | 2.21 s, 2.13 s, 2.18 s |
| Init Duration of those probes | 1.0-1.2 s |
| The `POST` right after it | 0.047-0.065 s |

The JVM pays twice: in Init, and in the first request, which is the first use of JSON decoding, validation, the
DynamoDB client and the presigner.

## Dead-letter function

**512 MB, 10 s.** One cold sample on AWS: Init 0.97 s, invocation 2.46 s, 164 MB used. The function also worked
within these values in the failure-path run on AWS. 512 MB stays, also because the memory setting sets the CPU share:
"Lambda allocates CPU power in proportion to the amount of memory configured"
([memory](https://docs.aws.amazon.com/lambda/latest/dg/configuration-memory.html)).

## Host requirements as tested

| Host | Docker Engine | Compose | GNU Make | What ran |
|---|---|---|---|---|
| Linux `x86_64` (EC2) | 29.9.2 | 5.6.1 | 4.3 | deploy, smoke test and destroy on LocalStack 4.14.0 and AWS |
| Linux `arm64` (EC2, 2 vCPU) | 29.1.3 | 2.40.3 | 4.3 | deploy, smoke test and destroy on LocalStack 4.14.0 and AWS |
| Linux `arm64` (`t4g.small`), fresh clone | 29.1.3 | 2.40.3 | 4.4.1 | build, test, lint; LocalStack 4.14.0 and AWS |
| Linux `arm64` (`t4g.small`), new host, empty cache | 29.9.2 | 5.6.1 | 4.4.1 | README steps on LocalStack 4.14.0 and AWS |
| Windows with WSL2 (`make` in WSL2) | not recorded | not recorded | not recorded | LocalStack 4.14.0, the page in Chrome |

Both `t4g.small` hosts run Ubuntu 26.04.1 on EC2 and have 2 vCPU and 1.8 GB of RAM with 6 GB of swap. On them:

- The disk use peaked at about 7.4 GB on the first host and grew by about 7 GB on the second. This is the base of
  "about 8 GB of disk" in the README.
- Memory use peaked at about 1.7 GB and swap use at about 0.6 GB on the second host, about 2.3 GB together. This is
  the base of "at least 2.5 GB of memory, swap included".
- On the second host `make deploy-localstack` took 5 min 16 s and `make deploy-aws ARCH=arm64` took 2 min 43 s.

`make deploy-localstack` took 5 min 42 s from an empty Docker cache on the `arm64` host in the second row, and
1 min 56 s with a warm cache on the `x86_64` host.

## CMYK and YCCK JPEGs

CMYK and YCCK JPEGs are accepted, and their colors depend on the file. Observed with JDK 21.0.12.1 on a copy of the
project's decode and render path:

- With an embedded ICC profile, gray and skin tones came out within 3 of the expected values.
- Without a profile, mid-tones came out lighter (gray 128 became 188).
- A CMYK file stored non-inverted came out entirely black.
