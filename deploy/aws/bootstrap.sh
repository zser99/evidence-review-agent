#!/usr/bin/env bash
# AWS 기반 자원 준비 스크립트 (한 번만 실행).
#   EKS 클러스터 · S3 버킷(비공개·암호화·버전 관리) · IRSA 역할 · EFS(StorageClass efs-sc) · RDS PostgreSQL · 시크릿
#
# 주의: 이 스크립트는 실제 AWS 계정에서 실행해 검증하지 못했다(작성 환경에 AWS 자격 증명 없음).
#       명령은 aws / eksctl / kubectl / helm 의 표준 사용법대로 작성했으니, 한 단계씩 실행하며 결과를 확인할 것.
#       과금되는 자원(EKS, NAT, RDS, EFS, ALB)을 만든다. 끝나면 deploy/README.md 의 '정리' 절차로 지운다.
#
# 사용:  AWS_REGION=ap-northeast-2 CLUSTER=evidence BUCKET=<전역에서 유일한 이름> DB_PASSWORD=<강한 비밀번호> ./bootstrap.sh
set -euo pipefail

: "${AWS_REGION:=ap-northeast-2}"
: "${CLUSTER:=evidence}"
: "${NAMESPACE:=evidence}"
: "${BUCKET:?BUCKET(전역에서 유일한 S3 버킷 이름)을 지정하세요}"
: "${DB_PASSWORD:?DB_PASSWORD 를 지정하세요}"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
HERE=$(cd "$(dirname "$0")" && pwd)

echo "== 1. EKS 클러스터 (관리형 노드 그룹 2대, OIDC 사용) =="
eksctl create cluster --name "$CLUSTER" --region "$AWS_REGION" --version 1.33 \
  --nodegroup-name app --node-type t3.large --nodes 2 --nodes-min 2 --nodes-max 4 \
  --managed --with-oidc

echo "== 2. 애드온: AWS Load Balancer Controller, EFS CSI 드라이버 =="
eksctl create iamserviceaccount --cluster "$CLUSTER" --region "$AWS_REGION" --namespace kube-system \
  --name aws-load-balancer-controller --attach-policy-arn "arn:aws:iam::${ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy" \
  --approve || echo "  (AWSLoadBalancerControllerIAMPolicy 가 없으면 AWS 문서의 iam_policy.json 으로 먼저 만든다)"
helm repo add eks https://aws.github.io/eks-charts && helm repo update
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller -n kube-system \
  --set clusterName="$CLUSTER" --set serviceAccount.create=false --set serviceAccount.name=aws-load-balancer-controller
eksctl create addon --cluster "$CLUSTER" --region "$AWS_REGION" --name aws-efs-csi-driver --force
# NetworkPolicy를 실제로 집행하려면 VPC CNI의 네트워크 정책 기능을 켜야 한다(기본은 꺼져 있다)
aws eks update-addon --cluster-name "$CLUSTER" --region "$AWS_REGION" --addon-name vpc-cni   --configuration-values '{"enableNetworkPolicy":"true"}' --resolve-conflicts OVERWRITE

echo "== 3. EFS 파일 시스템 + StorageClass(efs-sc) — Qdrant 볼륨 =="
VPC_ID=$(aws eks describe-cluster --name "$CLUSTER" --region "$AWS_REGION" --query cluster.resourcesVpcConfig.vpcId --output text)
NODE_SG=$(aws eks describe-cluster --name "$CLUSTER" --region "$AWS_REGION" --query cluster.resourcesVpcConfig.clusterSecurityGroupId --output text)
EFS_ID=$(aws efs create-file-system --region "$AWS_REGION" --encrypted --tags Key=Name,Value="$CLUSTER-qdrant" --query FileSystemId --output text)
for SUBNET in $(aws eks describe-cluster --name "$CLUSTER" --region "$AWS_REGION" --query 'cluster.resourcesVpcConfig.subnetIds[]' --output text); do
  aws efs create-mount-target --region "$AWS_REGION" --file-system-id "$EFS_ID" --subnet-id "$SUBNET" --security-groups "$NODE_SG" || true
done
kubectl apply -f - <<EOF
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: efs-sc
provisioner: efs.csi.aws.com
parameters:
  provisioningMode: efs-ap
  fileSystemId: ${EFS_ID}
  directoryPerms: "700"
  uid: "1000"
  gid: "1000"
EOF

echo "== 4. S3 버킷: 공개 차단, 기본 암호화, 버전 관리(규정집·증빙 이력) =="
aws s3api create-bucket --bucket "$BUCKET" --region "$AWS_REGION" --create-bucket-configuration LocationConstraint="$AWS_REGION"
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled

echo "== 5. IRSA: 백엔드 파드만 이 버킷에 접근 =="
sed "s/EVIDENCE_BUCKET/${BUCKET}/g" "$HERE/s3-policy.json" > /tmp/evidence-s3-policy.json
POLICY_ARN=$(aws iam create-policy --policy-name "${CLUSTER}-evidence-s3" --policy-document file:///tmp/evidence-s3-policy.json --query Policy.Arn --output text)
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
eksctl create iamserviceaccount --cluster "$CLUSTER" --region "$AWS_REGION" --namespace "$NAMESPACE" \
  --name evidence-backend --role-name evidence-agent-backend --attach-policy-arn "$POLICY_ARN" --role-only --approve

echo "== 6. RDS PostgreSQL (클러스터 VPC 안, 비공개) =="
SUBNETS=$(aws eks describe-cluster --name "$CLUSTER" --region "$AWS_REGION" --query 'cluster.resourcesVpcConfig.subnetIds' --output text)
aws rds create-db-subnet-group --region "$AWS_REGION" --db-subnet-group-name "$CLUSTER-db" --db-subnet-group-description "evidence db" --subnet-ids $SUBNETS
DB_SG=$(aws ec2 create-security-group --region "$AWS_REGION" --group-name "$CLUSTER-db" --description "evidence rds" --vpc-id "$VPC_ID" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --region "$AWS_REGION" --group-id "$DB_SG" --protocol tcp --port 5432 --source-group "$NODE_SG"
aws rds create-db-instance --region "$AWS_REGION" --db-instance-identifier "$CLUSTER-db" --engine postgres --engine-version 16 \
  --db-instance-class db.t4g.micro --allocated-storage 20 --storage-encrypted --no-publicly-accessible \
  --db-name evidence --master-username evidence --master-user-password "$DB_PASSWORD" \
  --db-subnet-group-name "$CLUSTER-db" --vpc-security-group-ids "$DB_SG" --backup-retention-period 7
aws rds wait db-instance-available --region "$AWS_REGION" --db-instance-identifier "$CLUSTER-db"
RDS_HOST=$(aws rds describe-db-instances --region "$AWS_REGION" --db-instance-identifier "$CLUSTER-db" --query 'DBInstances[0].Endpoint.Address' --output text)

echo "== 7. 애플리케이션 시크릿 (Git에 두지 않는다) =="
kubectl -n "$NAMESPACE" create secret generic evidence-agent-secrets \
  --from-literal=APP_JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=AI_INTERNAL_TOKEN="$(openssl rand -hex 32)" \
  --from-literal=DB_PASSWORD="$DB_PASSWORD" \
  --from-literal=APP_DEMO_PASSWORD="${APP_DEMO_PASSWORD:-demo1234}" \
  --from-literal=JEV_API_KEY="${JEV_API_KEY:-}" \
  --from-literal=OPENAI_API_KEY="${OPENAI_API_KEY:-}" \
  --from-literal=UNIVERSITY_API_KEY="${UNIVERSITY_API_KEY:-}" \
  --dry-run=client -o yaml | kubectl apply -f -

cat <<EOF

준비 완료. deploy/helm/evidence-agent/values-eks.yaml 에 아래 값을 넣으세요.
  postgres.host                 : ${RDS_HOST}
  s3.bucket                     : ${BUCKET}
  serviceAccount.name           : evidence-backend
  serviceAccount.annotations    : eks.amazonaws.com/role-arn: arn:aws:iam::${ACCOUNT_ID}:role/evidence-agent-backend
  qdrant.persistence.storageClass: efs-sc
그다음 deploy/README.md 의 '배포' 절차를 따릅니다.
EOF
