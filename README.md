
# Automated Deployment of a Real-Time NLP API

A Spring Boot NLP API (sentence/word segmentation, sentiment with negation, keyword extraction) built with **Maven**,
packaged with **Docker**, run on **Kubernetes (k3s)** on an **AWS EC2** Linux server that **Terraform** creates and **Ansible**
configures, and shipped on every `git push` by a **Jenkins** CI/CD pipeline.

```
 Laptop (WSL)                               AWS us-east-1
 ────────────                               ──────────────────────────────────────────────────────────
 terraform apply ──────────AWS API──────▶ EC2 m7i-flex.large, Ubuntu 24.04  (SG: 22, 443, 8080 = your IP; 80, 30080 = all)
 ansible-playbook site.yml ──SSH :443────▶  ├─ Jenkins :8080 ──polls──▶ GitHub repo
 git push ──▶ GitHub                         │     mvn verify → docker build → k3s ctr import → ansible deploy.yml
                                             ├─ Docker engine
                                             └─ k3s (Kubernetes)
                                                  Service :80 and :30080 ──▶ Deployment nlp-api (2 pods) ◀── clients
```

## API

| Method | Path           | Body                     | Returns                                                         |
|--------|----------------|--------------------------|-----------------------------------------------------------------|
| POST   | `/api/analyze` | `{"text": "..."}` (≤10k) | `sentences, tokens, sentiment, score (-1..1), keywords, micros` |
| GET    | `/health`      |                          | `{"status":"UP"}` (Kubernetes probes)                           |
| GET    | `/`            |                          | Web page for trying `/api/analyze` in a browser                 |

```bash
curl -X POST http://<EC2_IP>/api/analyze -H 'Content-Type: application/json' \
     -d '{"text":"Kubernetes is amazing. The old server was not fast and always crashed."}'
# {"sentences":2,"tokens":12,"sentiment":"negative","score":-0.25,"keywords":["kubernetes","amazing","old","server","fast"],"micros":310}
```

Run it locally: `mvn spring-boot:run` (or `mvn package && docker build -t nlp-api . && docker run -p 8080:8080 nlp-api`).

## Project layout

```
pom.xml, src/                 Maven project: the API + JUnit tests
Dockerfile, .dockerignore     container image
k8s/nlp-api.yaml              Kubernetes Deployment + Service
Jenkinsfile                   CI/CD pipeline
terraform/main.tf             create the AWS infrastructure (key pair, security group, EC2)
ansible/site.yml              set up the Linux server (Docker, k3s, Jenkins)
ansible/deploy.yml            deploy to Kubernetes + smoke test (run by Jenkins)
ansible/inventory/aws_ec2.yml dynamic inventory plugin
ansible/requirements.yml      Ansible collections
```

## Step by step

### 1. AWS account
1. Sign up at aws.amazon.com, then turn on **MFA for the root user** (IAM → Security credentials).
2. Billing → **Budgets** → create a monthly cost budget (e.g. $5) with an email alert.
3. IAM → Users → create user `ansible` → attach **AmazonEC2FullAccess** → Security credentials → **Create access key** (CLI).
   Use this user from now on, never the root keys.

### 2. Control machine (WSL Ubuntu)
```bash
sudo apt install -y ansible python3-boto3 awscli
# Install Terraform: https://developer.hashicorp.com/terraform/install
aws configure                      # access key from step 1, region us-east-1
cd ~ && git clone <your-repo-url> nlp-api && cd nlp-api/ansible
ansible-galaxy collection install -r requirements.yml
```
Work inside the WSL home folder, not `/mnt/c/...`: Ansible ignores `ansible.cfg` in world-writable Windows folders
(or `export ANSIBLE_CONFIG=$PWD/ansible.cfg`).

### 3. Create the infrastructure with Terraform
```bash
ssh-keygen -t rsa -m PEM -N '' -f ~/.ssh/nlp-api.pem   # only once; skip if the key already exists
cd ~/nlp-api/terraform
terraform init
terraform apply
```
Creates the key pair, security group and instance, then prints the app and Jenkins URLs.
SSH and Jenkins only accept your current IP address. If it changes, run `terraform apply` again.
To use another region or size, edit the variables in `main.tf` and `ansible/inventory/aws_ec2.yml`.

### 4. Configure the server with Ansible
```bash
cd ~/nlp-api/ansible
ansible-playbook site.yml
```
Installs Docker, JDK 21, Maven, Ansible, k3s and Jenkins, then prints the Jenkins URL and its initial admin password.
Wait about a minute after `terraform apply` so the server can finish its first boot.

### 5. Jenkins pipeline
1. Push this project to GitHub (public repo, or add a GitHub credential in Jenkins).
2. Open `http://<EC2_IP>:8080`, paste the password, choose **Install suggested plugins**, create your admin user.
3. **New Item** → `nlp-api` → **Pipeline** → Definition: *Pipeline script from SCM* → Git → your repo URL →
   branch `*/main` → Script Path `Jenkinsfile` → Save → **Build Now**.

The pipeline runs **Maven build & test → Docker image → load into Kubernetes → Ansible deploy + smoke test**.
After the first build Jenkins polls the repo every ~2 minutes, so every `git push` redeploys with a rolling update.

### 6. Tear down (stop the bill)
`m7i-flex.large` is free-tier eligible, but free hours/credits run out if it stays on. When you're done:
```bash
cd ~/nlp-api/terraform
terraform destroy
```

## How each syllabus topic is covered

| # | Topic | Where / how |
|---|-------|-------------|
| 1 | Create and manage an AWS account | Step 1: root MFA, budget alert, least-privilege IAM user + access keys used by Terraform and Ansible |
| 2 | Amazon EC2 | `terraform/main.tf`: `aws_key_pair`, `aws_security_group`, `aws_ami`, `aws_instance` (20 GB, tags); `terraform destroy` |
| 3 | Setting up Linux instances | `site.yml` on Ubuntu 24.04: apt packages and repos, users/groups, sudoers, systemd services, cloud-init |
| 4 | Ansible modules, plugins | Modules: `apt`, `deb822_repository`, `user`, `copy`, `shell`, `slurp`, `wait_for`, `uri`, `service`, `debug`, `meta`. Plugins: `aws_ec2` **inventory** plugin, `profile_tasks` **callback** plugin, `template` **lookup** plugin, `from_yaml_all`/`b64decode` **filter** plugins |
| 5 | Ansible collections in playbooks | `requirements.yml`; `amazon.aws.aws_ec2`, `kubernetes.core.k8s`, `ansible.posix.profile_tasks` |
| 6 | Docker architecture | Docker client (Jenkins `docker build`) → `dockerd` on EC2 → image layers in the local store → container runtime (containerd, which k3s also uses) |
| 7 | Docker images and containers | `Dockerfile` (Temurin JRE base layer + app jar layer, non-root user), `.dockerignore`, one image tag per build, `docker save` → `k3s ctr images import` |
| 8 | Kubernetes cluster architecture | k3s: control plane (API server, scheduler, controller manager, datastore) + node (kubelet, kube-proxy, containerd). `k8s/nlp-api.yaml`: Deployment (2 replicas, readiness/liveness probes, resource limits, rolling update) + LoadBalancer Service |
| 9 | DevOps principles, delivery pipeline, tools, DevOps on cloud | Everything as code (infrastructure, configuration, pipeline), automated tests gate every deploy, small frequent releases, cloud infrastructure on AWS. Flow: code → build → test → package → release → deploy → verify |
| 10 | CI/CD with Jenkins | `Jenkinsfile`: SCM polling trigger, 4 stages, JUnit test report, archived jar, deploy only if tests pass |
| 11 | CI/CD with Maven | `pom.xml`: Spring Boot parent (dependency management), `clean verify` lifecycle (compile → test → package), Surefire reports, `target/nlp-api.jar` |
| 12 | Infrastructure as code with Terraform | `terraform/main.tf`: AWS and HTTP providers, variables, data sources, resources, outputs; `init` → `apply` → `destroy`; `terraform validate` in GitHub Actions |

## Team
- chaitanyasai9666
- jayram
- gnaneswarreddy
