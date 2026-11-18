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

The brief requires application code and infrastructure code in separate repositories:

| Repository | Holds | Deployed by |
| --- | --- | --- |
| [week-3-lab-2-orders-app](https://github.com/jadofils/week-3-lab-2-orders-app) | `app/` (Spring Boot), `Dockerfile`, `.github/workflows/build-and-push.yml` | GitHub Actions → ECR → EventBridge → CodePipeline → CodeDeploy |
| [week-3-lab-2-aws-containerization](https://github.com/jadofils/week-3-lab-2-aws-containerization) (this one) | `infrastructure/` (CloudFormation), `docs/` | CloudFormation Git sync |

## Infrastructure layout

```text
infrastructure/
├── templates/                 what each stack creates
│   ├── 0-gitsync-bootstrap.yaml one-time Git sync setup (uploaded once)
│   ├── 1-registry.yaml          ECR repository + GitHub OIDC role
│   ├── 2-network.yaml           VPC, subnets, routes, VPC endpoints, all security groups
│   ├── 3-service.yaml           ALB with blue/green target groups, ECS, task roles, scaling, budget
│   └── 4-pipeline.yaml          EventBridge rule, CodePipeline, CodeBuild Prepare, CodeDeploy, rollback alarm
└── deployments/dev/           one Git sync deployment file per stack: template + parameters + tags
    ├── 1-registry.yaml          → stack onward-dev-registry
    ├── 2-network.yaml           → stack onward-dev-network
    ├── 3-service.yaml           → stack onward-dev-service
    └── 4-pipeline.yaml          → stack onward-dev-pipeline
```

Git sync links each stack to exactly one deployment file, so there is one per stack. Stacks share values through CloudFormation exports named `onward-dev-<stack>-<value>`. Create them in the order above and delete them in reverse.

Start with the [architecture](docs/architecture/README.md), then read the templates in deployment order: `2-network.yaml` → `3-service.yaml` → `4-pipeline.yaml`. Each keeps the numbered sections (1–7) of the design.

## Diagram

[`docs/architecture/architecture.drawio`](docs/architecture/architecture.drawio): the VPC, the delivery pipeline and how a change flows. [Open it in diagrams.net](https://app.diagrams.net/#Hjadofils%2Fweek-3-lab-2-aws-containerization%2Fmain%2Fdocs%2Farchitecture%2Farchitecture.drawio).

## Docs

Discovery report, client profile, architecture, decisions, cost and the live-review checks: see [`docs/`](docs/README.md).
