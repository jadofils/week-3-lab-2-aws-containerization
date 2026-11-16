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

## Two repositories

The brief requires application code and infrastructure code in separate repositories. This folder holds both while they are being written:

| Repository | Paths from this folder |
| --- | --- |
| `onward-orders-app` | [`app/`](app/README.md), `Dockerfile`, `.github/workflows/build-and-push.yml` |
| `onward-orders-infrastructure` | [`infrastructure/`](infrastructure), [`docs/`](docs/README.md) |

## Infrastructure layout

| Path | What it is |
| --- | --- |
| `infrastructure/bootstrap/gitsync-bootstrap.yaml` | One-time Git sync setup: GitHub connection and sync role |
| `infrastructure/registry.yaml` + `registry-deployment.yaml` | Stack `onward-dev-registry`: ECR repository and the GitHub OIDC role |
| `infrastructure/template.yaml` + `deployment.yaml` | Stack `onward-dev-orders`: network, endpoints, ALB, ECS, scaling and the release pipeline |

Start with the [architecture](docs/architecture/README.md), then read `template.yaml` top to bottom. It is split into numbered sections.

## Run the app locally

```powershell
cd app
mvn spring-boot:run    # http://localhost:8080
mvn verify             # tests
```
