# Live review: proving each client check

## Build it from the repositories (one time, Region eu-west-1)

Order matters: the service can't start until an image exists ([D3](../decisions/README.md#d3-layered-stacks-registry-first)).

1. **Bootstrap Git sync.** Go to *CloudFormation → Create stack → With new resources*.
   - Upload `infrastructure/bootstrap/gitsync-bootstrap.yaml` and name the stack `onward-dev-gitsync-bootstrap`.
   - Acknowledge the IAM capability and create the stack. It creates the connection `onward-dev-github` and the role `onward-dev-gitsync-role`.
2. **Approve the connection.** Go to *Developer Tools → Settings → Connections → onward-dev-github → Update pending connection*. Authorize the GitHub app for **both** repositories. The status becomes *Available*.
3. **Registry stack.** Go to *CloudFormation → Create stack → Sync from Git*.
   - Stack `onward-dev-registry`, repository `week-3-lab-2-aws-containerization`, branch `main`.
   - Deployment file `infrastructure/deployments/dev/1-registry.yaml`, role `onward-dev-gitsync-role`.
   - When it finishes, copy the output `GitHubActionsRoleArn`.
4. **First image.**
   - In `week-3-lab-2-orders-app` on GitHub, open *Settings → Secrets and variables → Actions → Variables*. Add `AWS_ROLE_ARN` (the ARN from step 3) and `AWS_REGION=eu-west-1`. These are variables, not secrets: neither is a credential.
   - Run *Actions → Build and push image → Run workflow* on `main` (CD can be started by hand for this first image; after that it runs by itself whenever CI passes on `main`). ECR now holds `sha-<commit>` and `latest`.
5. **Network, service and pipeline stacks.** *Sync from Git* again, once per stack, in this order and with the same role. Each waits for the one before:
   - `onward-dev-network`, deployment file `infrastructure/deployments/dev/2-network.yaml` (about 3 minutes).
   - `onward-dev-service`, deployment file `infrastructure/deployments/dev/3-service.yaml` (about 5 minutes). Its output `ApplicationUrl` is the site.
   - `onward-dev-pipeline`, deployment file `infrastructure/deployments/dev/4-pipeline.yaml`.
   - Every later merge to `main` that changes a template or deployment file updates only that stack.

> **Reviewing outside 07:00–20:00 Lagos time on weekdays?** Set `OffHoursShutdown: 'false'` in `infrastructure/deployments/dev/3-service.yaml` and merge it first; otherwise the service has 0 tasks ([D12](../decisions/README.md#d12-dev-shuts-down-outside-working-hours)).

## Step by step checks

Use AWS CloudShell, or AWS CLI v2 with short-lived credentials. First set:

```bash
export AWS_REGION=eu-west-1 STACK=onward-dev-service
export CLUSTER=onward-dev-orders-cluster SERVICE=onward-dev-orders-service PIPELINE=onward-dev-orders-pipeline
export URL=$(aws cloudformation describe-stacks --stack-name $STACK \
  --query "Stacks[0].Outputs[?OutputKey=='ApplicationUrl'].OutputValue" --output text)
echo $URL
```

Keep this running in a second terminal during checks 2, 3, 5 and 6. It should print only `200`:

```bash
while true; do curl -s -m 3 -o /dev/null -w "%{http_code} " "$URL/health"; sleep 1; done
```

## 1. One public address; containers in private subnets with no route to the internet

- Open `$URL`. The page shows your name, the lab name and which task answered.
- Tasks have no public IP:
  ```bash
  aws ec2 describe-network-interfaces \
    --filters Name=group-name,Values=onward-dev-orders-task-sg \
    --query "NetworkInterfaces[].[AvailabilityZone,PrivateIpAddress,Association.PublicIp]" --output table
  ```
  The last column is `None` for every task.
- The private route table has no internet route: only `local` and the S3 prefix list (`pl-…` → `vpce-…`). There is no `0.0.0.0/0` and no NAT gateway.
  ```bash
  aws ec2 describe-route-tables --filters Name=tag:Name,Values=onward-dev-orders-private-rt \
    --query "RouteTables[0].Routes[].[DestinationCidrBlock,DestinationPrefixListId,GatewayId]" --output table
  aws ec2 describe-nat-gateways --filter Name=state,Values=available --query "length(NatGateways)"
  ```
- The task security group allows outbound traffic only to the endpoints:
  ```bash
  aws ec2 describe-security-groups --filters Name=group-name,Values=onward-dev-orders-task-sg \
    --query "SecurityGroups[0].[IpPermissions,IpPermissionsEgress]"
  ```
- The endpoints that keep AWS traffic on AWS's network:
  ```bash
  aws ec2 describe-vpc-endpoints --filters Name=tag:Project,Values=onward \
    --query "VpcEndpoints[].[ServiceName,VpcEndpointType,length(SubnetIds)]" --output table
  ```

## 2. Pushing a change results in a new version running, with no manual steps

1. In `week-3-lab-2-orders-app`, make a visible change, for example the `app.info.application-name` text, and bump `<version>` in `pom.xml`. Open a pull request and merge it.
2. Show each hand-off with no one logged in:
   - **GitHub Actions:** the run passes the tests and pushes `sha-<commit>` and `latest`.
   - **ECR:**
     ```bash
     aws ecr describe-images --repository-name onward-dev-orders \
       --query "sort_by(imageDetails,&imagePushedAt)[-1].[imageTags,imageDigest]"
     ```
   - **EventBridge started the pipeline:**
     ```bash
     aws codepipeline list-pipeline-executions --pipeline-name $PIPELINE --max-items 1 \
       --query "pipelineExecutionSummaries[0].[status,trigger.triggerType,trigger.triggerDetail]"
     ```
     The trigger type is `CloudWatchEvent`, which is EventBridge.
   - **CodeDeploy:** *CodeDeploy → Deployments* shows the traffic shift 10% → 100%.
3. Refresh the page: the new text appears. The request loop printed only `200` throughout.

## 3. A release causes no downtime, and a bad release can be reversed

- **No downtime:** the request loop from check 2 shows no non-`200` response. CodeDeploy only shifts traffic once green is healthy.
- **Instant reversal, within 15 minutes of a release:**
  ```bash
  DEP=$(aws deploy list-deployments --application-name onward-dev-orders-deploy \
    --deployment-group-name onward-dev-orders-dg --query "deployments[0]" --output text)
  aws deploy stop-deployment --deployment-id $DEP --auto-rollback-enabled
  ```
  Traffic switches back to the blue tasks in seconds, and the page shows the previous text.
- **Automatic rollback on errors:** walk through the deployment group configuration: `DEPLOYMENT_FAILURE` and `DEPLOYMENT_STOP_ON_ALARM` with `onward-dev-orders-5xx-alarm`.
  ```bash
  aws deploy get-deployment-group --application-name onward-dev-orders-deploy \
    --deployment-group-name onward-dev-orders-dg \
    --query "deploymentGroupInfo.[deploymentConfigName,autoRollbackConfiguration,alarmConfiguration]"
  ```
- **Reversal later on:** redeploy the last good release from the pipeline's history:
  ```bash
  GOOD=<execution id of the last good release>
  aws codepipeline rollback-stage --pipeline-name $PIPELINE --stage-name Deploy \
    --target-pipeline-execution-id $GOOD
  ```

## 4. No long-lived credentials in the repositories or pipelines

- **GitHub:** the repository has no AWS secrets, only the `AWS_ROLE_ARN` and `AWS_REGION` variables.
  - The workflow uses `permissions: id-token: write` and `aws-actions/configure-aws-credentials` with `role-to-assume`.
  - The run log shows *Assuming role with OIDC*.
- **The role trusts only the main branch of the app repository:**
  ```bash
  aws iam get-role --role-name onward-dev-github-actions-role \
    --query "Role.AssumeRolePolicyDocument.Statement[0].Condition"
  ```
- **No IAM user access keys are used by the setup:**
  ```bash
  aws iam list-users --query "Users[].UserName"   # then: aws iam list-access-keys --user-name <name> for any listed
  ```
- **Pipeline, deployment, EventBridge and Git sync** each run under their own IAM role. There are no inline policies:
  ```bash
  for r in onward-dev-github-actions-role onward-dev-orders-pipeline-role \
           onward-dev-orders-codedeploy-role onward-dev-orders-events-role onward-dev-orders-task-execution-role; do
    echo "$r inline: $(aws iam list-role-policies --role-name $r --query 'length(PolicyNames)')"; done
  ```
  Every count is `0`.

## 5. The service adds containers under load and removes them afterwards

1. In the UI, **Sales-day CPU spike**: 420 seconds → **Start spike**. Click again after new tasks appear, so they join the load.
2. Follow the count:
   ```bash
   watch -n 15 "aws ecs describe-services --cluster $CLUSTER --services $SERVICE \
     --query 'services[0].[desiredCount,runningCount]' --output text"
   ```
   It rises from 2 towards 6 within a few minutes. New task IDs appear in **Which server is answering?**.
3. After the spike, or **Stop spikes**, it scales back to 2 over roughly 10–15 minutes.
4. Evidence:
   ```bash
   aws application-autoscaling describe-scaling-activities --service-namespace ecs \
     --resource-id service/$CLUSTER/$SERVICE --max-items 6 \
     --query "ScalingActivities[].[StartTime,Description]" --output table
   ```

## 6. The service keeps working if a container fails or a zone is lost

- **A container fails:**
  ```bash
  T=$(aws ecs list-tasks --cluster $CLUSTER --service-name $SERVICE --query "taskArns[0]" --output text)
  aws ecs stop-task --cluster $CLUSTER --task $T --reason "live review: container failure"
  ```
  The request loop keeps printing `200`, and ECS starts a replacement (`aws ecs describe-services … events`).
- **A zone is lost.** Stop every task in zone A:
  ```bash
  for t in $(aws ecs list-tasks --cluster $CLUSTER --service-name $SERVICE --query "taskArns[]" --output text); do
    az=$(aws ecs describe-tasks --cluster $CLUSTER --tasks $t --query "tasks[0].availabilityZone" --output text)
    [ "$az" = "${AWS_REGION}a" ] && aws ecs stop-task --cluster $CLUSTER --task $t --reason "simulate AZ loss" >/dev/null && echo "stopped $t"
  done
  ```
  The zone-B tasks keep answering, and replacements start. For a fuller test, AWS Fault Injection Service's `aws:network:disrupt-connectivity` action can isolate a whole subnet.

## 7. A new engineer can understand how the pieces fit together

Walk through, in this order:
1. The two repository READMEs.
2. The [architecture page](../architecture/README.md): repositories and stacks, then the VPC, the request flow, the release flow and rollback.
3. The templates in deployment order: `2-network.yaml`, `3-service.yaml`, `4-pipeline.yaml`.
4. The app repository's `ci.yml` (CI: checks every change) and `build-and-push.yml` (CD: runs only after CI passes on `main`).

## 8. What it costs, including the private connectivity, and which alternatives were rejected

Walk through the [cost page](../cost/README.md): endpoints vs NAT, off-hours shutdown, and the pipeline cost. Then go through the [decisions](../decisions/README.md).

## 9. The whole setup can be rebuilt from the repositories

1. Disconnect Git sync on `onward-dev-pipeline`, `onward-dev-service` and `onward-dev-network` (stack → *Git sync* tab). Empty the artifact bucket (`aws s3 rm s3://onward-dev-orders-artifacts-<account-id> --recursive`), then delete the three stacks in that order.
2. Repeat step 5 of the build section. Everything comes back from the templates, the deployment files and the image already in ECR.
3. For a full rebuild, delete `onward-dev-registry` too; its repository must be emptied first. Then repeat steps 3–5.

## After the review: stop the charges

1. Disconnect Git sync on `onward-dev-pipeline`, `onward-dev-service` and `onward-dev-network`, so they aren't recreated. Empty the artifact bucket (output `ArtifactBucketName` of `onward-dev-pipeline`), then delete the three stacks in that order. The ALB and endpoints stop charging.
2. Optionally, keep `onward-dev-registry`. It costs cents and keeps every released image.
3. Optionally, delete `onward-dev-gitsync-bootstrap`.
