provider "google" {
  credentials = file("jepsen-bot.json")
  project     = "cf-rabbitmq-core"
  region      = "europe-west4"
  # if changing the zone, make sure to change it as well in the setup script
  zone        = "europe-west4-a"
}

resource "google_compute_instance" "jepsen_controller" {
  name         = "jepsen-bot-ra-jepsen-controller"
  machine_type = "n1-standard-2"

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-10"
    }
  }

  network_interface {
    network       = "default"
    access_config {
    }
  }
  metadata = {
     sshKeys = "jepsen-bot:${file("jepsen-bot.pub")}"
  }
  provisioner "file" {
    source      = "jepsen-bot"
    destination = "/home/jepsen-bot/jepsen-bot"
    connection {
      type     = "ssh"
      user     = "jepsen-bot"
      private_key = file("jepsen-bot")
      host = google_compute_instance.jepsen_controller.network_interface.0.access_config.0.nat_ip
    }
  }
}


resource "google_compute_instance" "jepsen_worker" {
  count        = 5
  name         = "jepsen-bot-ra-jepsen-${count.index}"
  machine_type = "n1-highcpu-2"
  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-10"
    }
  }

  network_interface {
    network       = "default"
    access_config {
    }
  }
  metadata = {
    sshKeys = "jepsen-bot:${file("jepsen-bot.pub")}"
  }
}

output "controller_ip" {
  value = google_compute_instance.jepsen_controller.network_interface.0.access_config.0.nat_ip
}

output "workers_hostname" {
  value = join(" ", google_compute_instance.jepsen_worker.*.name)
}

output "workers_ip" {
  value = join(" ", google_compute_instance.jepsen_worker.*.network_interface.0.access_config.0.nat_ip)
}
