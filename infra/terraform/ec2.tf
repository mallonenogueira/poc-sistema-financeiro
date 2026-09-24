# EC2 de demonstração/homologação: sobe a stack inteira via docker compose.
# Sem SSH nem IP público — acesso administrativo via AWS Systems Manager Session Manager.

data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "demo_host" {
  name               = "pocbank-demo-host-${var.environment}"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "demo_host_ssm" {
  role       = aws_iam_role.demo_host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "demo_host" {
  name = "pocbank-demo-host-${var.environment}"
  role = aws_iam_role.demo_host.name
}

resource "aws_security_group" "demo_host" {
  name        = "pocbank-demo-host-${var.environment}"
  description = "Somente saida; entrada apenas pelo ALB/VPC"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "demo_host" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.ec2_instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.demo_host.id]
  iam_instance_profile   = aws_iam_instance_profile.demo_host.name

  metadata_options {
    http_tokens = "required" # IMDSv2 obrigatório
  }

  root_block_device {
    volume_size = 30
    encrypted   = true
  }

  user_data = <<-EOF
    #!/bin/bash
    set -eux
    dnf install -y docker git
    systemctl enable --now docker
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -sSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  EOF

  tags = {
    Name = "pocbank-demo-${var.environment}"
  }
}
