# Cost: what the design costs (eu-west-1)

**Source of every figure on this page:** [AWS Pricing Calculator estimate](https://calculator.aws/#/estimate?id=8d91f73244be325f9700d79218809e51f83b0f95), Region Europe (Ireland) `eu-west-1`, On-Demand prices, taxes excluded.

| | USD |
| --- | ---: |
| **Total per month** | **55.95** |
| **Total per year (12 months)** | **671.40** |
| Upfront | 0.00 |

## Cost per service

| Service | What it is used for | Configuration entered in the calculator | Monthly (USD) |
| --- | --- | --- | ---: |
| AWS Fargate | Runs the order app containers | Linux, x86; 2 tasks; 1 vCPU and 2 GB memory per task; 9.3 hours a day on average (`dev` stops outside working hours); 20 GB ephemeral storage | 27.93 |
| Elastic Load Balancing | The one public entry point (Application Load Balancer) | 1 Application Load Balancer; 25 new connections per second on average | 24.24 |
| Amazon CloudWatch | Container logs and the alarms used for scaling and rollback | 1 GB standard logs, 1 GB infrequent-access logs, 1 GB vended logs; 1 metric; 3 standard alarms and 1 high-resolution alarm | 2.33 |
| AWS CodeBuild | **No longer used**: the Prepare step was replaced by the S3 deployment bundle. Still in the estimate. | On-demand, Linux, `arm1.2xlarge`; 1 build a month, 10 minutes | 0.90 |
| Amazon ECR | Stores the order app images | 3 GB stored | 0.30 |
| AWS CodePipeline | Runs Source (ECR + S3 bundle) → Deploy for each release | V2 pipeline, 224 action-minutes a month | 0.25 |
| Amazon S3 | Pipeline artifact bucket and the deployment bundle bucket | S3 Standard, 0.01 GB; 50 PUT and 100 GET requests a month | 0.00 |
| Amazon EventBridge | Starts the pipeline when a new image is pushed | AWS service events, 1 KB payload | 0.00 |
| Amazon VPC | Network, public IPv4 and private connectivity | Public IPv4 addresses, as entered | 0.00 |
| **Total** | | | **55.95** |

Where the money goes: **Fargate 50%** and the **load balancer 43%** make up 93% of the monthly cost. Everything for the release pipeline (CodePipeline, CodeBuild, S3, EventBridge) comes to **$1.15** a month.

## Services in the design with no line in the estimate

These are part of the design and are not billed for this use, so they do not appear in the estimate: AWS CodeDeploy (ECS blue/green), Amazon ECS (cluster and service, you pay for Fargate), Application Auto Scaling, the S3 gateway endpoint, AWS IAM and the GitHub OIDC provider, AWS CloudFormation and Git sync, AWS CodeConnections, and AWS Budgets. GitHub Actions runs outside AWS.

## What drives the cost, and how it is kept down

| Cost driver | Control in the design |
| --- | --- |
| Fargate tasks (the largest line) | **Off-hours shutdown** in `dev` (`OffHoursShutdown: 'true'` in `infrastructure/deployments/dev/3-service.yaml`): 0 tasks from 20:00, back at 07:00 Monday to Friday. The estimate's 9.3 hours a day reflects this. **`MaxTasks: 6`** caps the spend on busy days. |
| Load balancer | Charged by the hour while it exists, even with 0 tasks. To pay nothing, delete the stacks and rebuild them from Git. |
| CloudWatch | Log retention is 14 days. Container Insights is disabled. |
| ECR | The lifecycle rule keeps the last 15 released images and removes untagged layers after 7 days. |
| S3 artifact bucket | Objects expire after 30 days. |
| Release pipeline | CodePipeline V2 is billed per action-minute, so an idle pipeline costs nothing. |
| Unexpected spend | **AWS Budgets** emails at 80% of $150 a month actual spend and at 100% forecast (`MonthlyBudgetUsd` and `BudgetAlertEmail` in `infrastructure/deployments/dev/3-service.yaml`). |

**To pay nothing:** delete `onward-dev-pipeline`, `onward-dev-service` and `onward-dev-network`, in that order (empty the artifact bucket first). Keep `onward-dev-registry` so the images remain, and rebuild from Git when needed.

## Alternatives considered

| Option | Why rejected |
| --- | --- |
| NAT gateway instead of VPC endpoints | It gives the app a path to the internet, which the Head of Security does not allow. |
| Fargate Spot | Tasks can be interrupted at short notice, which works against "customers never see an outage". |
| Fixed 6 tasks all month | Pays for peak capacity that sits idle most days; Auto Scaling adds tasks only when needed. |
| CodePipeline V1 | No stage rollback ([D10](../decisions/README.md#d10-rolling-back-after-the-blue-window-codepipeline-stage-rollback)). |
