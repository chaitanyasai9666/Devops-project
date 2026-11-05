# Creates the AWS infrastructure: key pair, security group, EC2 instance.
# Ansible (ansible/site.yml) then configures the server this creates.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
    http = {
      source  = "hashicorp/http"
      version = ">= 3.0"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "us-east-1" # keep in sync with ansible/inventory/aws_ec2.yml
}

variable "instance_type" {
  type    = string
  default = "m7i-flex.large" # 8 GB: Jenkins + k3s + Maven builds don't fit in 1 GB
}

variable "public_key_path" {
  type    = string
  default = "~/.ssh/nlp-api.pem.pub"
}

provider "aws" {
  region = var.aws_region
}

# Admin ports are limited to whoever runs terraform apply; re-apply after your IP changes
data "http" "my_ip" {
  url = "https://checkip.amazonaws.com"
}

locals {
  admin_cidr = ["${trimspace(data.http.my_ip.response_body)}/32"]
}

data "aws_ami" "ubuntu" {
  owners      = ["099720109477"] # Canonical
  most_recent = true
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

resource "aws_key_pair" "nlp_api" {
  key_name   = "nlp-api"
  public_key = file(pathexpand(var.public_key_path))
}

resource "aws_security_group" "nlp_api" {
  name        = "nlp-api-sg"
  description = "NLP API host"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = local.admin_cidr
  }
  ingress {
    description = "SSH on 443, for networks that block port 22"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = local.admin_cidr
  }
  ingress {
    description = "Jenkins"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = local.admin_cidr
  }
  ingress {
    description = "NLP API"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "NLP API NodePort"
    from_port   = 30080
    to_port     = 30080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "nlp_api" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.nlp_api.key_name
  vpc_security_group_ids = [aws_security_group.nlp_api.id]

  # Ubuntu 24.04 starts sshd through a systemd socket, so the socket must be reloaded too
  user_data = <<-EOT
    #!/bin/sh
    printf 'Port 22\nPort 443\n' > /etc/ssh/sshd_config.d/10-ports.conf
    systemctl daemon-reload
    systemctl restart ssh.socket
  EOT

  root_block_device {
    volume_size           = 20
    delete_on_termination = true
  }

  tags = {
    Name    = "nlp-api"
    Project = "nlp-api" # ansible/inventory/aws_ec2.yml finds the server by this tag
  }

  lifecycle {
    ignore_changes = [ami] # a newer Ubuntu image must not replace the running server
  }
}

output "public_ip" {
  value = aws_instance.nlp_api.public_ip
}

output "app_url" {
  value = "http://${aws_instance.nlp_api.public_ip}/"
}

output "jenkins_url" {
  value = "http://${aws_instance.nlp_api.public_ip}:8080"
}
