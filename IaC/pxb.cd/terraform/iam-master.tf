# Used by the AlloyGatewayBearerRead statement below to scope the
# secretsmanager:GetSecretValue ARN to the current AWS account.
data "aws_caller_identity" "current" {}

# create assume policy for master instance role
data "aws_iam_policy_document" "jenkins-master-assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

# create role for master instance
resource "aws_iam_role" "jenkins-master" {
  name               = "${var.cloud_name}-master"
  path               = "/"
  assume_role_policy = data.aws_iam_policy_document.jenkins-master-assume.json
}

# create policy for master instance role
data "aws_iam_policy_document" "jenkins-master" {
  # master should be able to start worker instances
  statement {
    actions = [
      "ec2:ModifySpotFleetRequest",
      "ec2:DescribeSpotFleetRequests",
      "ec2:DescribeSpotFleetInstances",
      "ec2:DescribeSpotInstanceRequests",
      "ec2:CancelSpotInstanceRequests",
      "ec2:GetConsoleOutput",
      "ec2:RequestSpotInstances",
      "ec2:RunInstances",
      "ec2:StartInstances",
      "ec2:StopInstances",
      "ec2:TerminateInstances",
      "ec2:CreateTags",
      "ec2:DeleteTags",
      "ec2:DescribeInstances",
      "ec2:DescribeKeyPairs",
      "ec2:DescribeRegions",
      "ec2:DescribeImages",
      "ec2:DescribeAvailabilityZones",
      "ec2:DescribeSecurityGroups",
      "ec2:DescribeSubnets",
    ]

    resources = [
      "*",
    ]
  }

  # master should be able to pass worker role to worker instance
  statement {
    actions = [
      "iam:ListRoles",
      "iam:PassRole",
      "iam:ListInstanceProfiles",
    ]

    resources = [
      "${aws_iam_role.jenkins-worker.arn}",
      "${aws_iam_instance_profile.jenkins-worker.arn}",
    ]
  }

  # actions in master instance UserData, master should be able to attach volume
  statement {
    actions = [
      "ec2:AttachVolume",
      "ec2:DetachVolume",
      "ec2:DescribeVolumes",
      "ec2:AssociateAddress",
      "ec2:CreateTags",
      "ec2:DescribeInstances",
    ]

    resources = [
      "*",
    ]
  }

  # allow read messages from SQS
  statement {
    actions = [
      "sqs:GetQueueUrl",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:DeleteMessageBatch",
    ]

    resources = [
      "${aws_sqs_queue.jenkins.arn}",
    ]
  }

  # PS-10997: master-side Grafana Alloy systemd unit fetches the
  # alloy-gateway bearer at boot via this scoped permission. ARN suffix
  # is the AWS-Secrets-Manager 6-char random suffix; the trailing wildcard
  # tolerates rotation that creates a new secret version with a new suffix.
  statement {
    actions = [
      "secretsmanager:GetSecretValue",
    ]

    resources = [
      "arn:aws:secretsmanager:us-east-1:${data.aws_caller_identity.current.account_id}:secret:percona-ci-platform/alloy-gateway/bearer-*",
    ]
  }
}

# create policy for master instance role
resource "aws_iam_policy" "jenkins-master" {
  name   = "${var.cloud_name}-master"
  policy = data.aws_iam_policy_document.jenkins-master.json
}

# attach policy to master instance role
resource "aws_iam_role_policy_attachment" "jenkins-master" {
  role       = aws_iam_role.jenkins-master.name
  policy_arn = aws_iam_policy.jenkins-master.arn
}

# PS-10997: SSM Session Manager + SSM Run Command for the master fleet.
# Replaces the EC2 Instance Connect path on hosts that lack the IC agent
# (e.g., custom AMIs) and gives a parallel-fleet command path for cloud-init
# extensions like the Alloy systemd install. AWS-managed policy.
resource "aws_iam_role_policy_attachment" "jenkins-master-ssm" {
  role       = aws_iam_role.jenkins-master.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# create instance profile for master instance
resource "aws_iam_instance_profile" "jenkins-master" {
  name = "${var.cloud_name}-master"
  path = "/"
  role = aws_iam_role.jenkins-master.name
}
