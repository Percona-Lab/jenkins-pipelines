# create policy for master spot fleet role
data "aws_iam_policy_document" "jenkins-master-fleet-assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["spotfleet.amazonaws.com"]
    }
  }
}

# create role for master spot fleet
resource "aws_iam_role" "jenkins-master-fleet" {
  name               = "${var.cloud_name}-master-fleet"
  path               = "/"
  assume_role_policy = "${data.aws_iam_policy_document.jenkins-master-fleet-assume.json}"
}

# attach managed policy for master spot fleet role
resource "aws_iam_role_policy_attachment" "jenkins-master-fleet" {
  role       = "${aws_iam_role.jenkins-master-fleet.name}"
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2SpotFleetTaggingRole"
}
