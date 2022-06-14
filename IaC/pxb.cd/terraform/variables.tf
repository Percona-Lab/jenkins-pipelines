variable "cloud_name" {
  description = "Uniq cloud name"
  default     = "jenkins-pxb"
}

variable "aws_region" {
  description = "AWS region to launch servers."
  default     = "us-west-2"
}

variable "aws_az_list" {
  type    = list(string)
  default = ["us-west-2a", "us-west-2b", "us-west-2c"]
}

variable "main_az" {
  description = "Desired AZ for Jenkins master"
  default     = "1"
}

variable "hostname" {
  description = "Fully Qualified Domain Name"
  default     = "pxb.cd.percona.com"
}

variable "hostedzone" {
  description = "Hosted Zone ID"
  default     = "Z1H0AFAU7N8IMC"
}

variable "key_name" {
  description = "Name of AWS key pair for jenkins master"
  default     = "jenkins-master"
}

data "aws_ami" "amazon-linux-2" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["amzn2-ami-hvm-*-x86_64-gp2"]
  }
}

data "template_file" "master_user_data" {
  template = "${file("master_user_data.sh")}"

  vars = {
    JHostName             = "${var.hostname}"
    MasterIP_AllocationId = "${aws_eip.jenkins.id}"
    JDataVolume           = "${aws_ebs_volume.jenkins.id}"
  }
}
