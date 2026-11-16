# Design decisions

Each decision gives the choice, the business reason, and the alternatives rejected. Owner: Jado Fils SEZIKEYE.

## D1. Region: eu-west-1
**Decision.** Deploy to `eu-west-1` (Ireland), as in Lab 1.
**Reason.**
- Every service used is available there: ECS Fargate, CodeDeploy for ECS, CodePipeline V2, CloudFormation Git sync and every VPC endpoint needed.
- Prices are lower than in `af-south-1`.
- Latency from West Africa to western Europe is usually as low as or lower than to Cape Town.

**Rejected.** `af-south-1`: an opt-in Region with higher prices.

## D2. Two repositories: application and infrastructure
**Decision.**
- `week-3-lab-2-orders-app` holds the code, the `Dockerfile` and the build workflow.
- `week-3-lab-2-aws-containerization` holds the CloudFormation templates, the Git sync deployment files and these docs.

**Reason.**
- The brief requires separate repositories.
- Each repository has one way of being released: an app merge produces an image, and an infrastructure merge updates stacks.
- Application engineers can't change the network or IAM by accident, and the repositories can have different reviewers.

**Rejected.** One repository with path filters. It is simpler, but it breaks the brief, and one merge could change both the app and its permissions.

## D3. Three stacks, registry first
**Decision.**
- `onward-dev-gitsync-bootstrap`: uploaded once in the console. It holds the GitHub connection and the Git sync role.
- `onward-dev-registry`: the ECR repository and the GitHub OIDC role.
- `onward-dev-orders`: everything that runs and releases the service.

The last two are Git-synced from the infrastructure repository.

**Reason.**
- ECS can't start the service until an image exists. A separate registry stack lets the first image be pushed before the service stack is created.
- Stack order on a rebuild is fixed: bootstrap → registry → first image push → orders.
- The registry outlives the service: tearing down `onward-dev-orders` to save money keeps every released image.

**Rejected.**
- One stack: its first creation would hang waiting for an image that can't exist yet.
- Nested stacks: child templates must be uploaded to S3, which is an extra manual step.
- A public placeholder image: tasks can't reach public registries ([D6](#d6-vpc-endpoints-instead-of-a-nat-gateway)).

## D4. GitHub Actions signs in to AWS with OIDC; no access keys
**Decision.**
- `onward-dev-registry` creates the IAM OIDC identity provider for `token.actions.githubusercontent.com` and the role `onward-dev-github-actions-role`.
- The trust policy requires `aud = sts.amazonaws.com` and `sub = repo:jadofils/week-3-lab-2-orders-app:ref:refs/heads/main`.
- The role has one customer managed policy, `onward-dev-ecr-push-policy`. It allows `ecr:GetAuthorizationToken` plus the push actions on the `onward-dev-orders` repository only.

**Reason.**
- Meets "no long-lived cloud credentials in our repositories or pipelines".
- Each workflow run gets credentials that expire within an hour.
- Pull requests and other branches can't push, and a leaked workflow log contains nothing reusable.
- GitHub stores only the role ARN, as a repository variable; it is not a secret.

**Elsewhere, also without keys:**
- Git sync uses a CodeConnections GitHub App connection.
- CodePipeline, CodeBuild, CodeDeploy and EventBridge each use their own IAM service role.

**Rejected.**
- An IAM user with access keys in GitHub secrets: long-lived and usable from anywhere.
- Letting GitHub call CodePipeline directly: it needs more permission and couples the repositories.

## D5. ECS on Fargate, 0.5 vCPU and 1 GB per task, in private subnets
**Decision.** One ECS service, `onward-dev-orders-service`, on Fargate (platform `LATEST`, Linux x86). Each task has 0.5 vCPU and 1 GB of memory, with `AssignPublicIp: DISABLED`, spread over two private subnets in two zones.

**Reason.**
- No servers to patch, and billing is per second while tasks run.
- 1 GB is enough for the Spring Boot app with the JVM capped at 75% of container memory.
- 0.5 vCPU keeps the CPU spike demonstration able to push average CPU over the scaling target.
- x86 matches GitHub's standard runners, so no cross-building is needed.

**Rejected.**
- ECS on EC2: back to patching servers.
- Graviton (ARM): about 20% cheaper, but it needs multi-architecture builds. It's a good next step.
- Fargate Spot: tasks can be interrupted, which works against "never see an outage".

## D6. VPC endpoints instead of a NAT gateway
**Decision.**
- Interface endpoints for `com.amazonaws.eu-west-1.ecr.api`, `ecr.dkr` and `logs`, each in both private subnets, with private DNS on.
- A gateway endpoint for `s3` on the private route table. Its policy allows reads only from ECR's image-layer bucket.
- No NAT gateway, and no `0.0.0.0/0` route from the private subnets.

**Reason.**
- It meets "the application itself must have no path to the internet: traffic to AWS services stays on AWS's network", and the brief requires endpoints.
- These four are exactly what a Fargate task needs: `ecr.api` and `ecr.dkr` to pull the image, S3 for the image layers, and `logs` for the `awslogs` driver.
- Fargate tasks don't need ECS endpoints. The app calls no other AWS service.
- **Cost:** about $48 a month for the three interface endpoints in two zones. A Regional NAT in two zones would cost about $77 ([cost page](../cost/README.md)). The S3 gateway endpoint is free.

**Rejected.**
- A NAT gateway: it gives the app an internet path, and it costs more.
- Endpoints in one zone only: they stop working if that zone is lost.
- Extra endpoints such as `ssm`, `ssmmessages` and `secretsmanager`: nothing uses them, and each costs about $16 a month.

## D7. EventBridge starts the pipeline when `latest` is pushed; releases are pinned by digest
**Decision.**
- The ECR repository uses `IMMUTABLE_WITH_EXCLUSION`: every tag is immutable except `latest`.
- GitHub Actions pushes `sha-<commit>`, then moves `latest`.
- The EventBridge rule `onward-dev-orders-image-pushed` matches `source: aws.ecr`, `detail-type: ECR Image Action`, `action-type: PUSH`, `result: SUCCESS`, the repository name and `image-tag: latest`. Its target is the pipeline, through a role allowed only `codepipeline:StartPipelineExecution` on that pipeline.
- The pipeline is V2, with no source polling.

**Reason.**
- The brief requires EventBridge to trigger CodePipeline.
- Matching only `latest` means one release per merge, not one per tag.
- The ECR source action outputs the image **digest**, so the version that was tested is the version deployed, even if `latest` moves during the release.
- `sha-<commit>` tags can never be overwritten, so every release can be traced to a commit.

**Rejected.**
- Fully mutable tags: a version could be silently replaced.
- Fully immutable tags with no `latest`: the ECR source action needs a fixed tag to follow.
- CodePipeline polling: slower, and not event-driven as required.

## D8. The pipeline copies the task definition CloudFormation registered
**Decision.**
- `template.yaml` registers the task definition family `onward-dev-orders`: size, roles, logging, port and environment.
- The ECS service refers to the **family name**, not a revision, so CloudFormation never needs to change the service's task definition after creation.
- The *Prepare* stage (CodeBuild project `onward-dev-orders-prepare`, buildspec in the template):
  - reads the latest revision of the family;
  - swaps its image for the `<IMAGE1_NAME>` placeholder;
  - writes `taskdef.json` and `appspec.yaml` for CodeDeploy.

**Reason.**
- CloudFormation can't update the task definition of a service controlled by CodeDeploy. This split avoids that error.
- Infrastructure owners still change task size or logging in one place, and the change is picked up by the next release.
- The application repository needs no AWS-specific files, so it stays free of account IDs and role ARNs.

**Rejected.**
- Keeping `taskdef.json` and `appspec.yaml` in the application repository (the AWS tutorial pattern): it couples the app repository to infrastructure names and the account ID.
- The `AWS::CodeDeployBlueGreen` CloudFormation hook: it releases through stack updates, not through CodePipeline as the brief requires.

## D9. CodeDeploy blue/green: canary, alarm rollback, 15-minute blue retention
**Decision.**
- Deployment group `onward-dev-orders-dg`: two target groups (`blue`, `green`, target type `ip`) on the production listener port 80.
- Deployment configuration `CodeDeployDefault.ECSCanary10Percent5Minutes`.
- Automatic rollback on `DEPLOYMENT_FAILURE` and `DEPLOYMENT_STOP_ON_ALARM`, using alarm `onward-dev-orders-5xx-alarm` (`HTTPCode_Target_5XX_Count` ≥ 5 in one minute).
- Blue tasks are terminated 15 minutes after a successful shift.

**Reason.**
- Customers never see an outage. Green must be healthy before it receives any traffic.
- Only 10% of customers meet a bad release, and only until the alarm fires.
- For 15 minutes after a release, rollback is only a listener switch (CTO: "go back quickly").
- The cost is about 25 extra task-minutes per release.

**Rejected.**
- ECS rolling updates: old and new versions mix, and rollback is a new deployment. It also fails the brief's CodeDeploy requirement.
- `ECSAllAtOnce`: every customer meets a bad release.
- A public test listener: a second public port goes against "one public entry point". The health check plus the canary do that job.

## D10. Rolling back after the blue window: CodePipeline stage rollback
**Decision.** After blue is gone, an engineer uses *Start rollback* on the pipeline's Deploy stage and chooses the last good execution. CodePipeline redeploys that execution's image digest blue/green.
**Reason.** One action, no rebuild, the same safe deployment path, and it is recorded. Stage rollback is a V2-only feature, which is one reason the pipeline is V2.
**Rejected.** Re-tagging an old image as `latest`: blocked for every tag except `latest`, and it hides what happened.

## D11. Service Auto Scaling: 2–6 tasks, target tracking on 50% CPU
**Reason.**
- Two tasks in two zones is the minimum that survives losing a task or a zone.
- Six caps spend at about three times normal capacity.
- Target tracking both adds and removes tasks, so no one adjusts anything (Head of Operations).
- It is the same signal as Lab 1, so the CPU spike panel in the UI still demonstrates it.

**Known interaction.** AWS documents that a scaling event during a blue/green deployment can make the deployment fail. A failed deployment rolls back safely, and the pipeline can be rerun. Avoiding releases during a sales-day peak is normal practice anyway.

**Rejected.**
- Request-count scaling: CPU is the client's stated signal.
- Scheduled scaling alone: misses unexpected surges.

## D12. `dev` shuts down outside working hours
**Decision.** Two Application Auto Scaling scheduled actions, in time zone `Africa/Lagos`:
- 20:00 every day: min and max set to 0;
- 07:00 Monday to Friday: back to min 2, max 6.

The schedule is enabled by `OffHoursShutdown: 'true'` in `deployment.yaml`; `test` and `prod` keep it off.

**Reason.** "Idle environments should not cost us money." Fargate stops charging when tasks stop, which saves about $22 a month for `dev`. For a review outside working hours, set the parameter to `'false'` and merge.

**Rejected.** Deleting and recreating the stack every night: slower, and the endpoints and ALB would be rebuilt daily. That remains the right move for long idle periods.

## D13. No inline policies; one role per job
**Decision.**
- Every IAM permission is an AWS managed policy or an `AWS::IAM::ManagedPolicy` attached through `ManagedPolicyArns`. No role uses the `Policies` property.

| Role | Permissions |
| --- | --- |
| `onward-dev-github-actions-role` | `onward-dev-ecr-push-policy` (push to one repository) |
| `onward-dev-orders-task-execution-role` | AWS managed `AmazonECSTaskExecutionRolePolicy` |
| `onward-dev-orders-task-role` | none (the app calls no AWS API) |
| `onward-dev-orders-codedeploy-role` | AWS managed `AWSCodeDeployRoleForECS` |
| `onward-dev-orders-pipeline-role` | customer managed: artifact bucket, ECR describe, CodeBuild start, CodeDeploy deploy, `iam:PassRole` for the two task roles |
| `onward-dev-orders-prepare-role` | customer managed: `ecs:DescribeTaskDefinition`, its log group, the artifact bucket |
| `onward-dev-orders-events-role` | customer managed: `codepipeline:StartPipelineExecution` on one pipeline |
| `onward-dev-gitsync-role` | CloudFormation rights scoped to `onward-dev-*` names |

**Reason.** It is a brief requirement and a client standard. Each permission can be found, reviewed and reused, and the earlier audit finding of "too much access" is not repeated.

## D14. Health checks on `/health`
**Reason.**
- The ALB checks every 15 seconds, and three failures remove a task, so a task that is only busy during a CPU spike is not mistaken for a failed one.
- The ECS service has a 60-second health-check grace period for JVM start-up.
- CodeDeploy shifts traffic to green only after green passes these checks.

## D15. No single point of failure
| If this fails | What keeps the service running |
| --- | --- |
| One task | ALB health checks pull it, and ECS replaces it |
| One Availability Zone | Tasks, ALB nodes and endpoint interfaces also run in the other zone; ECS places replacements there |
| A release | Canary + alarm rollback; blue kept for 15 minutes; stage rollback after that |
| GitHub or the pipeline | Only new releases stop; the running service has no runtime dependency on them |
| Sudden demand | Target tracking adds tasks up to 6 while the existing tasks keep answering |

**Not covered (stated honestly):** losing the whole Region, and orders held in memory on a failed task ([D17](#d17-orders-in-memory)).

## D16. Container image
**Decision.**
- GitHub Actions runs `mvn verify` first. The `Dockerfile` then copies the tested JAR onto a Java 21 JRE base image.
- The container runs as a non-root user, with `-XX:MaxRAMPercentage=75`, and exposes 8080.
- ECR scans every image on push. A lifecycle rule keeps the last 15 `sha-*` images.

**Reason.** Small image, fast start, the tested artifact is the one shipped, and storage stays bounded.
**Rejected.** Building inside the Dockerfile without running tests first: an untested image could reach ECR.

## D17. Orders in memory
**Reason.** The brief is about delivery, not data. Each task keeps its own sample orders. A durable, shared order store needs a database, which is a separate client decision with its own cost. With a database, schema changes would have to work with both the blue and green versions.

## D18. HTTP for the lab; no ALB access logs or Container Insights
**Reason.**
- There is no domain or certificate for the lab. Production should add HTTPS with ACM and redirect HTTP to HTTPS.
- Access logs and Container Insights add cost.
- The evidence needed here comes from ALB and ECS CloudWatch metrics, container logs and the CodeDeploy history.

## D19. "Which server answered" comes from the ECS task metadata endpoint
**Decision.** On Fargate the app reads its task ID, Availability Zone and private IP from the ECS task metadata endpoint (`ECS_CONTAINER_METADATA_URI_V4`). This replaces the EC2 instance metadata service used in Lab 1.
**Reason.** Fargate has no EC2 instance metadata. The task metadata endpoint is local to the task, so it needs no internet or endpoint access. The UI can still show which container answered, and how many, as capacity changes.
