# Onward Logistics: Delivering Order System Updates Safely

Lab 2 (Week 3, Compute & Delivery on AWS), by Jado Fils SEZIKEYE.

The Onward order system runs as one container image on Amazon ECS with Fargate. The containers sit in private subnets with no route to the internet, behind one public Application Load Balancer. Merging to `main` is the only manual step:

- GitHub Actions builds the image and pushes it to Amazon ECR, signing in with OIDC.
- EventBridge starts CodePipeline.
- CodeDeploy releases the new version blue/green, with no downtime and a quick way back.

```text
merge ─▶ GitHub Actions ──OIDC──▶ ECR ──event──▶ EventBridge ─▶ CodePipeline ─▶ CodeDeploy (blue/green)
                                                                                       │
customers ──HTTP──▶ ALB (public subnets, 2 AZs) ─▶ blue | green target group ◀─────────┘
                     └─▶ ECS Fargate service: 2–6 tasks (private subnets, 2 AZs, no internet route)
                           └─ AWS traffic only ─▶ VPC endpoints: ecr.api · ecr.dkr · logs · s3 (gateway)
```

## Repository layout

The application and the infrastructure live in this one repository, in separate folders with separate delivery paths:

| Path | Holds | Deployed by |
| --- | --- | --- |
| [`app/`](app/README.md), `Dockerfile`, `.github/workflows/build-and-push.yml` | The Spring Boot application and its container build | GitHub Actions → ECR → EventBridge → CodePipeline → CodeDeploy |
| `infrastructure/`, `docs/` | CloudFormation templates and documentation | CloudFormation Git sync |

The build workflow runs only when `app/`, `Dockerfile`, `.dockerignore` or the workflow itself changes, so infrastructure and docs commits do not release a new image. The GitHub OIDC role trusts only `repo:jadofils/week-3-lab-2-aws-containerization:ref:refs/heads/main`.

### One-time GitHub setup

After the `onward-dev-registry` stack exists, open *Settings → Secrets and variables → Actions → **Variables*** in this repository and add `AWS_ROLE_ARN` (stack output `GitHubActionsRoleArn`) and `AWS_REGION` (`eu-west-1`). These are variables, not secrets: neither is a credential. Then run *Actions → Build and push image → Run workflow* to push the first image.

## Infrastructure layout

| Path | What it is |
| --- | --- |
| `infrastructure/bootstrap/gitsync-bootstrap.yaml` | One-time Git sync setup: GitHub connection and sync role |
| `infrastructure/registry.yaml` + `registry-deployment.yaml` | Stack `onward-dev-registry`: ECR repository and the GitHub OIDC role |
| `infrastructure/template.yaml` + `deployment.yaml` | Stack `onward-dev-orders`: network, endpoints, ALB, ECS, scaling and the release pipeline |

Start with the [architecture](docs/architecture/README.md), then read `template.yaml` top to bottom. It is split into numbered sections.

## Diagram

[`docs/architecture/architecture.drawio`](docs/architecture/architecture.drawio): the VPC, the delivery pipeline and how a change flows. [Open it in diagrams.net](https://app.diagrams.net/#Hjadofils%2Fweek-3-lab-2-aws-containerization%2Fmain%2Fdocs%2Farchitecture%2Farchitecture.drawio).

## Docs

Discovery report, client profile, architecture, decisions, cost and the live-review checks: see [`docs/`](docs/README.md).
