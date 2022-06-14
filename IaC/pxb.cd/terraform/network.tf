# separate virtual network for jenkins instances
resource "aws_vpc" "jenkins" {
  cidr_block           = "10.179.0.0/22"
  enable_dns_hostnames = true
  enable_dns_support   = true
  instance_tenancy     = "default"

  tags = {
    Name            = "${var.cloud_name}"
    iit-billing-tag = "${var.cloud_name}"
  }
}

# Internet Gateway for jenkins VPC
resource "aws_internet_gateway" "jenkins" {
  vpc_id = "${aws_vpc.jenkins.id}"

  tags = {
    Name            = "${var.cloud_name}"
    iit-billing-tag = "${var.cloud_name}"
  }
}

# create subnet in all AZs
resource "aws_subnet" "jenkins" {
  count = "${length(var.aws_az_list)}"

  vpc_id                  = "${aws_vpc.jenkins.id}"
  cidr_block              = "10.179.${count.index}.0/24"
  availability_zone       = "${element(var.aws_az_list, count.index)}"
  map_public_ip_on_launch = true

  tags = {
    Name            = "${var.cloud_name}-${count.index}"
    iit-billing-tag = "${var.cloud_name}"
  }
}

# create route table for VPC
resource "aws_route_table" "jenkins" {
  vpc_id = "${aws_vpc.jenkins.id}"

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = "${aws_internet_gateway.jenkins.id}"
  }

  tags = {
    Name            = "${var.cloud_name}"
    iit-billing-tag = "${var.cloud_name}"
  }
}

# add subnet route
resource "aws_route_table_association" "jenkins" {
  count = "${length(var.aws_az_list)}"

  route_table_id = "${aws_route_table.jenkins.id}"
  subnet_id      = "${element(aws_subnet.jenkins.*.id, count.index)}"
}

# allow ssh access (assign when needed)
resource "aws_security_group" "jenkins-SSH" {
  name        = "${var.cloud_name}-SSH"
  description = "SSH traffic in"
  vpc_id      = "${aws_vpc.jenkins.id}"

  ingress {
    from_port = 22
    to_port   = 22
    protocol  = "tcp"

    cidr_blocks = [
      "176.37.55.60/32",
      "188.163.20.103/32",
      "213.159.239.48/32",
      "46.149.86.84/32",
      "54.214.47.252/32",
      "54.214.47.254/32",
      "46.149.84.26/32",
      "93.170.117.65/32",
    ] 
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    iit-billing-tag = "${var.cloud_name}"
  }
}

# allow http and https access to jenkins master
resource "aws_security_group" "jenkins-HTTP" {
  name        = "${var.cloud_name}-HTTP"
  description = "HTTP and HTTPS traffic in"
  vpc_id      = "${aws_vpc.jenkins.id}"

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    iit-billing-tag = "${var.cloud_name}"
  }
}
