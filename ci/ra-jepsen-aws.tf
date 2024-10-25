provider "aws" {
  profile = "default"
}

data "aws_ami" "debian" {
  most_recent = true
  owners = ["136693071363"] # Debian

  filter {
    name   = "name"
    values = ["debian-12-amd64-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  } 
}

resource "aws_key_pair" "ssh_key" {
  key_name   = "jepsen-ra-kv-store-key"
  public_key = file("jepsen-bot.pub") 
}

resource "aws_instance" "jepsen_controller" {
  ami           = data.aws_ami.debian.id
  instance_type = "m7i.xlarge"
  associate_public_ip_address = true

  key_name = aws_key_pair.ssh_key.key_name 
  security_groups = ["jepsen"]
  tags = {
    Name = "JepsenRaKvStore"
  }

  private_dns_name_options {
    enable_resource_name_dns_a_record = true
    hostname_type = "ip-name"
  }

  provisioner "file" {
    source      = "jepsen-bot"
    destination = "/home/admin/jepsen-bot"
    connection {
      type     = "ssh"
      user     = "admin"
      private_key = file("jepsen-bot")
      host = aws_instance.jepsen_controller.public_ip
    }
  }


}

resource "aws_instance" "jepsen_worker" {
  count = 5
  ami           = data.aws_ami.debian.id
  instance_type = "t3.small"
  associate_public_ip_address = true

  key_name = aws_key_pair.ssh_key.key_name 
  security_groups = ["jepsen"]
  tags = {
    Name = "JepsenRaKvStore"
  }

  private_dns_name_options {
    enable_resource_name_dns_a_record = true
    hostname_type = "ip-name"
  } 

}

output "controller_ip" {
  value       = aws_instance.jepsen_controller.public_ip
}

output "workers_ip" {
  value       = join(" ", aws_instance.jepsen_worker.*.public_ip)
}

output "workers_hostname" {
  value       = join(" ", [for worker in aws_instance.jepsen_worker : split(".", worker.private_dns)[0]])
}

output "workers_hosts_entries" {
  value = join("\n", [for worker in aws_instance.jepsen_worker : join(" ", [worker.private_ip, split(".", worker.private_dns)[0]])])
}
