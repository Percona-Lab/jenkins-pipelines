# queue for termination notifications
resource "aws_sqs_queue" "jenkins" {
  name = "${var.cloud_name}-termination"

  tags = {
    iit-billing-tag = "${var.cloud_name}"
  }
}

resource "aws_sqs_queue_policy" "jenkins" {
  queue_url = "${aws_sqs_queue.jenkins.id}"

  policy = <<POLICY
{
  "Version": "2012-10-17",
  "Id": "sqspolicy",
  "Statement": [
    {
      "Sid": "First",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "sqs:SendMessage",
      "Resource": "${aws_sqs_queue.jenkins.arn}",
      "Condition": {
        "ArnEquals": {
          "aws:SourceArn": "${aws_cloudwatch_event_rule.aws-stop.arn}"
        }
      }
    }
  ]
}
POLICY
}

# notify about instance interruption from AWS side
resource "aws_cloudwatch_event_rule" "aws-stop" {
  name        = "${var.cloud_name}-aws-stop"
  description = "EC2 Spot Instance Interruption Warning"

  event_pattern = <<PATTERN
{
  "source": [
    "aws.ec2"
  ],
  "detail-type": [
    "EC2 Spot Instance Interruption Warning"
  ]
}
PATTERN
}

# notify about instance interruption from AWS side
resource "aws_cloudwatch_event_target" "aws-stop" {
  target_id = "${var.cloud_name}-termination"
  rule      = "${aws_cloudwatch_event_rule.aws-stop.name}"
  arn       = "${aws_sqs_queue.jenkins.arn}"
}
