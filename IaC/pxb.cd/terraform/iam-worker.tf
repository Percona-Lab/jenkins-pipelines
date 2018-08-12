# create assume policy for worker instance role
data "aws_iam_policy_document" "jenkins-worker-assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

# create role for worker instance
resource "aws_iam_role" "jenkins-worker" {
  name               = "${var.cloud_name}-worker"
  path               = "/"
  assume_role_policy = "${data.aws_iam_policy_document.jenkins-worker-assume.json}"
}

# create policy for worker instance role
data "aws_iam_policy_document" "jenkins-worker" {
  # worker should be able to assign tags
  statement {
    actions = [
      "ec2:DescribeSpotInstanceRequests",
      "ec2:CreateTags",
      "ec2:DeleteTags",
      "ec2:DescribeInstances",
    ]

    resources = [
      "*",
    ]
  }
}

# create policy for worker instance role
resource "aws_iam_policy" "jenkins-worker" {
  name   = "${var.cloud_name}-worker"
  policy = "${data.aws_iam_policy_document.jenkins-worker.json}"
}

# attach policy to worker instance role
resource "aws_iam_role_policy_attachment" "jenkins-worker" {
  role       = "${aws_iam_role.jenkins-worker.name}"
  policy_arn = "${aws_iam_policy.jenkins-worker.arn}"
}

# create instance profile for worker instance
resource "aws_iam_instance_profile" "jenkins-worker" {
  name = "${var.cloud_name}-worker"
  path = "/"
  role = "${aws_iam_role.jenkins-worker.name}"
}
