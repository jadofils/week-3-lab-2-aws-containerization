# Client Profile: Onward Logistics

Onward Logistics is the client for every lab in this programme. This page holds the facts that stay the same while their needs change each week. Source: [`client-profile-needs.docx`](../source/client-profile-needs.docx).

## At a glance

Onward Logistics is a fast-growing last-mile delivery company that moves parcels for online merchants across West Africa.

| Topic | Detail |
| --- | --- |
| Business | Regional last-mile delivery. Connects merchants, riders and customers, with live parcel tracking. |
| Engineering team | Lean and growing. |
| Technology stacks | Systems built with Python and Java. |
| AWS starting point | One or several AWS accounts configured by hand in the console, a few servers and little consistency. An internal audit flagged too much access for too many people. |

## What the business is trying to achieve

Leadership wants to grow quickly without losing customer trust or control of costs.

**Priorities, in order:**
1. Grow without breaking: handle peak days without outages.
2. Protect customer and merchant data, because trust is the product.
3. Control cost, because delivery margins are thin.
4. Ship improvements faster, moving from monthly to weekly releases.
5. Be ready for audits at any time.

**Constraints:**
- A small platform team supports a much larger group of engineers.
- The budget is limited, so unexplained spend is a problem.
- Data protection rules apply in every market the company operates in.
- Many customers use mobile connections that can be slow or unreliable.

## Standards every solution follows

| Standard | Rule | How Lab 2 follows it |
| --- | --- | --- |
| Naming | `onward-<environment>-<purpose>`, for example `onward-dev-orders`. Environments are `dev`, `test` and `prod`. | Every stack and resource name starts with `onward-dev-` (see the [architecture](../architecture/README.md#resource-names)). |
| Tags | Tag every resource that supports tags with `Project` (onward), `Owner` (your name) and `Environment`. | Set once per stack in each Git sync deployment file and on each resource in the templates. |
| Access | Grant only what a person or system needs. No shared accounts. Avoid long-lived credentials. | GitHub signs in with OIDC; every AWS service uses its own role; no access keys exist anywhere ([D4](../decisions/README.md#d4-github-actions-signs-in-to-aws-with-oidc-no-access-keys)). |
| Automation | Make what you build repeatable from code, kept in Git. | All infrastructure is CloudFormation deployed by Git sync; releases are fully automated. |
| Cost | Every resource has an owner. Delete what you no longer need. Keep budget alerts active. | `Owner` tag, ECR and log retention rules, off-hours shutdown for `dev`, and a monthly budget alert ([cost](../cost/README.md)). |
| Region | Each lab names the Region or asks you to choose and justify one. | `eu-west-1` ([D1](../decisions/README.md#d1-region-eu-west-1)). |
| Decisions | Write down each decision with its reason, in business terms. | [Design decisions](../decisions/README.md). |

## Requirements by week

Earlier requirements still apply.

| Week | What the client needs |
| --- | --- |
| 1 | Engineers are onboarded safely and the same way every time. People outside the company get only temporary, limited access. Every change to who can access what can be traced. Spend is visible from day one. |
| 3, Lab 1 | Absorb sales-day demand automatically, survive the loss of a server or a zone, and expose only one public entry point. |
| 3, Lab 2 | Every approved change reaches customers automatically, safely and without downtime; the application has no path to the internet; no long-lived credentials. See the [problem description](../problem-description/problem.md). |
