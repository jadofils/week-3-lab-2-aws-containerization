# Discovery Report: Delivering Order System Updates Safely

| Lab | Lab 2 - Delivering Order System Updates Safely for Onward Logistics |
| --- | --- |
| Name | Jado Fils SEZIKEYE |
| Date | 12 November 2026 |
| Client | Onward Logistics (see the [Client Profile](../client-profile/README.md)) |
| Application stack | Java 21, Spring Boot, single full-stack application in one container image |
| AWS Region | `eu-west-1` (Ireland); see [decision D1](../decisions/README.md#d1-region-eu-west-1) |

## 1. What you found

### The client's need

Onward Logistics needs every approved change to its order system to reach customers on its own, without outages, without anyone holding long-lived cloud keys, and with a quick way back when a release goes wrong.

### Solutions that exist

| Solution | How it works | Trade off for this client |
| --- | --- | --- |
| Manual release (today) | An engineer builds an image on a laptop, uploads it with personal keys and updates the running service. | **Strength:** no tooling to build. **Weakness:** slow and error-prone, depends on one person's laptop and long-lived keys, and nobody dares release on peak days. |
| Rolling update from a CI job | GitHub Actions builds the image and tells ECS to replace containers a few at a time. | **Strength:** simple, one tool. **Weakness:** old and new versions serve customers together during the update, and rolling back means running another full update while the bad version is live. |
| Blue/green release with a managed pipeline | CI builds and pushes the image. A pipeline starts a second, complete copy of the service ("green"), checks it, moves traffic to it gradually, and keeps the old copy ("blue") ready to take traffic back. | **Strength:** no downtime, and rollback is a traffic switch that takes seconds. Every step is recorded. **Weakness:** more moving parts, and for a few minutes per release twice the containers run. |

### What happens with no solution

- **Outages caused by releases.** Manual steps are skipped or done in the wrong order, and the mistake reaches every customer at once. Last year's peak-day failures already cost Onward merchant trust.
- **Slow, risky recovery.** Without a recorded previous version and an automatic switch back, recovering from a bad release means another manual release under pressure.
- **Stolen keys.** Long-lived access keys kept on laptops or in repositories are a common way into cloud accounts. They work from anywhere until someone notices.
- **Missed business goals.** The client wants weekly instead of monthly releases. That is not possible while each release needs a brave engineer and a quiet day.

**Documented examples.**
- **Bad manual deployment.** In August 2012, Knight Capital deployed new trading code by hand to eight servers but missed one. The old code on that server sent millions of orders, and Knight lost more than $460 million in about 45 minutes. The SEC later found it lacked adequate controls and testing for code deployment and fined it $12 million.
- **Leaked long-lived keys.** In 2016, intruders used an AWS access key that an Uber engineer had stored in a private GitHub repository to download data on about 25.6 million people. The FTC expanded its settlement with Uber, which now faces 20 years of independent security assessments.

### Sources

- [SEC: SEC charges Knight Capital with violations of market access rule (2013)](https://www.sec.gov/news/press-release/2013-222)
- [FTC: Uber agrees to expanded settlement with FTC related to privacy, security claims (2018)](https://www.ftc.gov/news-events/press-releases/2018/04/uber-agrees-expanded-settlement-ftc-related-privacy-security)
- [Amazon ECS: CodeDeploy blue/green deployments](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-type-bluegreen.html)
- [Amazon ECS: interface VPC endpoints](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/vpc-endpoints.html)
- [Amazon ECR: interface VPC endpoints](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html)
- [GitHub: configuring OpenID Connect in Amazon Web Services](https://docs.github.com/en/actions/security-for-github-actions/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services)
- [AWS CodePipeline: pipeline types and stage rollback](https://docs.aws.amazon.com/codepipeline/latest/userguide/pipeline-types-planning.html)
- [Amazon ECR: preventing image tags from being overwritten](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-tag-mutability.html)
- [CloudFormation Git sync](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/git-sync.html)
- [AWS PrivateLink pricing](https://aws.amazon.com/privatelink/pricing/), [AWS Fargate pricing](https://aws.amazon.com/fargate/pricing/), [AWS CodePipeline pricing](https://aws.amazon.com/codepipeline/pricing/)

## 2. The business value of your solution

### Your chosen approach

**A blue/green release pipeline for a containerized order service on Amazon ECS with Fargate, in private subnets with no internet route.**

- **Merging is the only manual step.** A merge to `main` in the application repository makes GitHub Actions test the code, build the image and push it to Amazon ECR. It signs in to AWS with a short-lived OIDC token, not a stored key. The new image fires an EventBridge rule, which starts CodePipeline. CodeDeploy then releases it.
- **No downtime.** CodeDeploy starts a complete second set of containers ("green") next to the running set ("blue"). It sends 10% of customers to green for 5 minutes, then all of them.
- **Quick way back.**
  - If errors rise during the release, an alarm rolls it back automatically.
  - For 15 minutes after the release, the old containers keep running, so a rollback is only a traffic switch.
  - After that, CodePipeline can redeploy the last good release in one action.
- **No path to the internet.** Containers have no public addresses and their subnets have no internet route at all: no NAT gateway. They reach ECR, S3 and CloudWatch Logs through VPC endpoints, so that traffic stays on AWS's network. Customers reach the app only through the load balancer.
- **Survives failures and peaks.**
  - At least two containers run, one in each of two zones. Failed containers are replaced automatically.
  - On busy days the service grows from 2 up to 6 containers when CPU is high, and shrinks afterwards.
- **Readable and rebuildable.** Two repositories, three short CloudFormation templates and one numbered architecture page. Everything is deployed by Git sync and can be rebuilt from Git.

This suits Onward better than rolling updates because customers never see a half-released version and going back takes seconds, not a new deployment. It suits them better than manual releases because nobody holds keys and nobody logs in to deploy.

### Services you will use

| Service | The job it does | Why this service, used this way |
| --- | --- | --- |
| Amazon ECS with AWS Fargate | Runs the order app containers (0.5 vCPU, 1 GB each) in private subnets, in two zones. | No servers to patch. Billed per second only while containers run, which supports scaling down and off-hours shutdown. |
| Application Load Balancer | The single public address. It has two target groups (blue and green) and one listener that CodeDeploy switches between them. | Makes zero-downtime traffic shifting possible, and health-checks `/health`. |
| Amazon ECR | Stores the versioned images. Tags are immutable, except `latest`, which marks the release. Scan on push is on, and a lifecycle rule keeps the last 15 images. | Private registry reachable through VPC endpoints. Each release is pinned by digest, so a deployed version can't change underneath. |
| VPC endpoints (`ecr.api`, `ecr.dkr`, `logs` interface; `s3` gateway) | Private connectivity from containers to the AWS services they need. | Meets "no path to the internet". It is cheaper than a NAT gateway and keeps traffic on AWS's network. |
| GitHub Actions + IAM OIDC identity provider | Tests, builds and pushes the image, and signs in to AWS with a short-lived token. | No AWS keys stored in GitHub. The role trusts only the `main` branch of the application repository and may only push to one ECR repository. |
| Amazon EventBridge | Turns "a new `latest` image was pushed" into "start the pipeline". | Required by the brief. It decouples the app repository from the pipeline: GitHub needs no permission on CodePipeline. |
| AWS CodePipeline (V2) + AWS CodeBuild | Takes the new image, prepares the deployment files, and hands them to CodeDeploy. | Every release is recorded, and stage rollback redeploys the last good release. V2 is billed per minute, so an idle pipeline costs nothing. |
| AWS CodeDeploy (ECS blue/green) | Starts green, shifts traffic 10% then 100%, rolls back on alarm, and keeps blue for 15 minutes. | Required by the brief, and it is what makes releases safe and reversible. Free for ECS. |
| Application Auto Scaling | Keeps 2–6 containers, targets 50% average CPU, and stops `dev` containers outside working hours. | Grows on busy days and shrinks afterwards with no one adjusting anything. Idle `dev` time costs nothing for compute. |
| Amazon CloudWatch | Container logs (14-day retention), CPU metrics for scaling, and the 5xx alarm that triggers rollback. | Gives the evidence for every check, and turns "errors are rising" into an automatic rollback. |
| AWS IAM (customer managed and AWS managed policies only) | One role per job: GitHub, task execution, pipeline, build, deployment, EventBridge and Git sync. | Least privilege and no inline policies (brief and client standard). |
| AWS CloudFormation + Git sync | Three templates deployed from the infrastructure repository. | Nothing is clicked in the console, and every change is reviewed in Git. |
| AWS Budgets | Monthly budget with email alerts at 80% actual and 100% forecast. | Spend stays visible (client standard). |

### What it costs

Approximate list prices for `eu-west-1`, On-Demand, Free Tier not applied, taxes excluded. The working is on the [cost page](../cost/README.md).

| Service | Configuration | Quiet month (USD) | Month with sales days (USD) |
| --- | --- | ---: | ---: |
| Fargate: always-on containers | 2 tasks × 0.5 vCPU / 1 GB × 730 h | 36.04 | 36.04 |
| Fargate: sales-day containers | Up to 4 more tasks for about 4 days × 10 h (160 task-hours) | 0.00 | 3.95 |
| Fargate: blue/green overlap | About 8 releases × 2 extra tasks × 25 min | 0.16 | 0.16 |
| Application Load Balancer | 1 ALB, about 1 LCU average | 24.24 | 24.24 |
| **Private connectivity:** interface endpoints | 3 endpoints × 2 AZs × 730 h, about 5 GB processed | 48.23 | 48.23 |
| **Private connectivity:** S3 gateway endpoint | Gateway endpoint | 0.00 | 0.00 |
| Public IPv4 addresses | 2, both for the ALB; containers have none | 7.30 | 7.30 |
| Amazon ECR | About 3 GB stored (15 images) | 0.30 | 0.30 |
| CloudWatch | About 1 GB of logs, 3 alarms | 0.87 | 0.87 |
| CodePipeline V2 + CodeBuild + CodeDeploy | About 8 releases × 28 action-minutes (100 free) | 0.25 | 0.25 |
| AWS Budgets, EventBridge, GitHub Actions (public repo) | | 0.00 | 0.00 |
| **Total per month, always on** | | **117.39** | **121.34** |
| **Total per month, `dev` off outside working hours** | Tasks run about 282 h instead of 730 h | **95.27** | **99.22** |

- **Private connectivity costs about $48 a month**, roughly 41% of the always-on bill. A Regional NAT gateway in two zones would cost about $77 a month and would also give the app a path to the internet. Endpoints are both cheaper and safer here.
- **Idle `dev` time costs no compute.** Shutting the containers down outside working hours saves about $22 a month. Deleting the stack between projects removes the fixed costs (ALB and endpoints) as well, and it can be rebuilt from Git.
- **Releases are almost free.** Pipeline, build and deployment come to well under $1 a month at weekly releases.

### Who benefits, and how you will know

| Stakeholder | Benefit | How you would know |
| --- | --- | --- |
| Chief Technology Officer | Merging is the only manual step; releases cause no outage and can be undone quickly. | A merge produces a new version on the page within about 15 minutes, with no one logged in. A request loop shows only `200` during the release. Rolling back takes under a minute. |
| Head of Security | No long-lived keys, one public entry point, and no internet path from the app. | No IAM user access keys exist, and GitHub has only a role ARN. Container subnets' route table has no `0.0.0.0/0` route, and tasks have no public IP. |
| Head of Operations | Failed containers and lost zones are handled automatically, and capacity follows demand. | Stopping a task: a new one starts and the page keeps answering. During a CPU spike the task count rises towards 6, then falls back to 2. |
| Finance Lead | Knows what the private connectivity costs, and idle `dev` time costs no compute. | The [cost page](../cost/README.md) shows the endpoints versus NAT. Running tasks show 0 outside working hours. Budget alerts arrive at 80% of the limit. |
| Engineers | Release by merging, and find their way around from the docs. | A new engineer follows the [architecture page](../architecture/README.md) and the repository READMEs to a successful release without a guided tour. |

### Limits and risks of your design

- **Fixed costs remain while the stack exists.** The ALB and the interface endpoints charge by the hour even with zero containers. To pay nothing, delete the stack and rebuild it from Git.
- **Auto Scaling and CodeDeploy can conflict.** AWS documents that a scaling event during a blue/green deployment can make the deployment fail. The failed deployment rolls back safely, and the pipeline can be rerun.
- **Rollback after the 15-minute window is a redeploy.** It takes as long as a normal release (about 10 minutes), not seconds.
- **Database changes are not covered.** Orders are held in memory per container in this demonstration. With a real database, schema changes need to work with both blue and green versions.
- **HTTP only** for the lab. Production needs HTTPS with an ACM certificate.
- **Region failure is not covered.** The design survives losing one zone, not the whole Region.
- **Off-hours shutdown means `dev` is not available at night or at weekends.** Turn it off in `infrastructure/deployments/dev/3-service.yaml` for a review outside working hours.
- **Prices are approximate list prices.** Confirm them in the [AWS Pricing Calculator estimate](https://calculator.aws/#/estimate?id=bc91d51c1383cfdff4475d4979578b77d8aa5860) before quoting them. The [cost page](../cost/README.md#aws-pricing-calculator-estimate) explains where its inputs differ from the templates.
