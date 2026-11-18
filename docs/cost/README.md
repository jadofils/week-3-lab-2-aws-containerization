# Cost: what the design costs, including private connectivity (eu-west-1)

About **$117 a month** with `dev` running all the time, or about **$95** with `dev` shut down outside working hours. A month with sales days adds about **$4**. The per-service summary is in the [discovery report](../discovery-report/README.md#what-it-costs).

The working below uses approximate on-demand list prices for `eu-west-1`. Re-check each line in the [AWS Pricing Calculator](https://calculator.aws/) before relying on it. Taxes are excluded, and no Free Tier is assumed, so these are the worst case.

## Price assumptions

| Item | Price used |
| --- | --- |
| Fargate, Linux x86 | $0.04048 per vCPU-hour + $0.004445 per GB-hour → one 0.5 vCPU / 1 GB task = **$0.024685 per hour** |
| Application Load Balancer | $0.0252 per hour + $0.008 per LCU-hour |
| Interface VPC endpoint | $0.011 per endpoint per AZ per hour + $0.01 per GB processed |
| S3 gateway endpoint | free |
| Public IPv4 address | $0.005 per hour |
| Regional NAT gateway (for comparison) | $0.048 per hour per active AZ + $0.048 per GB |
| ECR storage | $0.10 per GB-month |
| CloudWatch Logs / alarms | $0.57 per GB ingested / $0.10 per alarm per month |
| CodePipeline V2 | $0.002 per action-minute, 100 minutes free each month |
| CodeDeploy to ECS | free |
| CodeBuild (`general1.small`) | about $0.005 per build-minute, 100 minutes free each month |

## Per-day estimate

| Line item | Quiet day (2 tasks all day) | Peak day (2 tasks for 14 h, 6 tasks for 10 h) |
| --- | --- | --- |
| Fargate tasks | 48 task-hours → **$1.18** | 28 + 60 = 88 task-hours → **$2.17** |
| ALB hourly + LCU | $0.60 + $0.19 → **$0.79** | $0.60 + $0.35 (about 3 LCU at peak) → **$0.95** |
| Interface endpoints (3 × 2 AZs) | 6 × 24 h × $0.011 → **$1.58** | **$1.58** (plus a few cents of data while new tasks pull the image) |
| Public IPv4 (ALB only) | **$0.24** | **$0.24** |
| Logs, ECR, alarms, pipeline | **≈ $0.06** | **≈ $0.07** |
| **Total per day** | **≈ $3.85** | **≈ $5.01** |

A sales day costs about 30% more than a quiet day, because the extra tasks run only while they are needed.

## Per-month estimate

| Line item | Always on | `dev` off outside working hours |
| --- | ---: | ---: |
| Fargate, 2 tasks (730 h vs about 282 h: 07:00–20:00, Monday to Friday) | 36.04 | 13.92 |
| Fargate, blue/green overlap (about 8 releases × 2 tasks × 25 min) | 0.16 | 0.16 |
| ALB (730 h + 1 LCU average) | 24.24 | 24.24 |
| Interface endpoints (3 × 2 AZs × 730 h + about 5 GB) | 48.23 | 48.23 |
| S3 gateway endpoint | 0.00 | 0.00 |
| Public IPv4 (2 for the ALB) | 7.30 | 7.30 |
| ECR (about 3 GB) | 0.30 | 0.30 |
| CloudWatch (about 1 GB of logs, 3 alarms) | 0.87 | 0.87 |
| CodePipeline + CodeBuild (about 224 action-minutes, 100 free) | 0.25 | 0.25 |
| **Quiet month** | **117.39** | **95.27** |
| Sales days (about 4 days × 4 extra tasks × 10 h = 160 task-hours) | +3.95 | +3.95 |
| **Month with sales days** | **121.34** | **99.22** |

## What the private connectivity costs (Finance Lead)

| Option | Monthly | Data path | App has an internet path? |
| --- | ---: | --- | --- |
| **Chosen:** 3 interface endpoints in 2 AZs + S3 gateway endpoint | **≈ $48** | AWS network only | **No** |
| Regional NAT gateway in 2 AZs + 2 public IPs | ≈ $77 + $0.048 per GB | Through the internet gateway | Yes |
| Interface endpoints in one AZ only | ≈ $24 | AWS network only | No, but image pulls and logging fail if that zone is lost |

- **Private connectivity is about 41% of the always-on bill.** It is a fixed hourly cost that does not depend on traffic. It is what lets containers pull images and write logs with no route to the internet.
- **It is about $29 a month cheaper than a NAT gateway**, and it removes the internet path the Head of Security does not want.
- **Each extra endpoint costs about $16 a month** (2 AZs). That is why only the four that Fargate actually needs exist ([D6](../decisions/README.md#d6-vpc-endpoints-instead-of-a-nat-gateway)).

## What the release pipeline costs

- **CodePipeline V2** charges only while actions run. One release takes about 28 action-minutes: source 1, prepare 2, deploy about 25 (canary 5, blue retention 15, start-up). With 100 free minutes, eight releases a month cost about $0.25.
- **CodeDeploy for ECS** is free. **CodeBuild** uses about 1–2 minutes per release.
- **Blue/green overlap** doubles the tasks for about 25 minutes per release: about 2 cents each.
- **GitHub Actions** is free for public repositories.

An idle pipeline costs nothing, which meets "idle environments should not cost us money" for the delivery tooling too.

## Alternatives considered on cost

| Option | Cost vs chosen | Why rejected |
| --- | --- | --- |
| NAT gateway instead of endpoints | About +$29 per month | More expensive, and it gives the app an internet path. |
| Fargate Spot | About −70% on task cost | Tasks can be interrupted at short notice, which works against "never see an outage". |
| Graviton (ARM) Fargate | About −20% on task cost | Needs multi-architecture image builds. It's a good next step. |
| Fixed 6 tasks all month | About +$72 per month | Pays for peak capacity that sits idle most days. |
| CodePipeline V1 | About $1 per month per pipeline | Similar cost, but no stage rollback ([D10](../decisions/README.md#d10-rolling-back-after-the-blue-window-codepipeline-stage-rollback)). |

## Keeping cost under control

- **Off-hours shutdown** for `dev` (`OffHoursShutdown` in `infrastructure/deployments/dev/3-service.yaml`).
- **`MaxCapacity: 6`** caps the worst-case task spend.
- **ECR lifecycle rule** keeps 15 images. **Log retention** is 14 days. **Artifact bucket** objects expire after 30 days.
- **Budget alert:** `BudgetAlertEmail` in `infrastructure/deployments/dev/3-service.yaml` emails at 80% of $150 a month actual spend and at 100% forecast.
- **To pay nothing:** delete `onward-dev-pipeline`, `onward-dev-service` and `onward-dev-network`, in that order (the ALB and endpoints go with them). Keep `onward-dev-registry`, so the images remain, and rebuild from Git when needed.
