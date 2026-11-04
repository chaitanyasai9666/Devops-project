# Automated Deployment of a Real-Time NLP API

A Spring Boot NLP API (sentence/word segmentation, sentiment with negation, keyword extraction) built with **Maven**,
packaged with **Docker**, run on **Kubernetes (k3s)** on an **AWS EC2** Linux server that **Ansible** provisions and
configures, and shipped on every `git push` by a **Jenkins** CI/CD pipeline.

```
 Laptop (WSL)                               AWS us-east-1
 ────────────                               ──────────────────────────────────────────────────────────
 ansible-playbook site.yml ──AWS API──────▶ EC2 m7i-flex.large, Ubuntu 24.04  (SG: 22, 8080 = your IP; 30080 = all)
                           ──SSH──────────▶  ├─ Jenkins :8080 ──polls──▶ GitHub repo
 git push ──▶ GitHub                         │     mvn verify → docker build → k3s ctr import → ansible deploy.yml
                                             ├─ Docker engine
                                             └─ k3s (Kubernetes)
                                                  Service NodePort :30080 ──▶ Deployment nlp-api (2 pods) ◀── clients
```

## API

| Method | Path           | Body                     | Returns                                                         |
|--------|----------------|--------------------------|-----------------------------------------------------------------|
| POST   | `/api/analyze` | `{"text": "..."}` (≤10k) | `sentences, tokens, sentiment, score (-1..1), keywords, micros` |
| GET    | `/health`      |                          | `{"status":"UP"}` (Kubernetes probes)                           |

```bash
curl -X POST http://<EC2_IP>:30080/api/analyze -H 'Content-Type: application/json' \
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
ansible/site.yml              provision EC2 + set up the Linux server (Docker, k3s, Jenkins)
ansible/deploy.yml            deploy to Kubernetes + smoke test (run by Jenkins)
ansible/teardown.yml          delete all AWS resources
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
aws configure                      # access key from step 1, region us-east-1
cd ~ && git clone <your-repo-url> nlp-api && cd nlp-api/ansible
ansible-galaxy collection install -r requirements.yml
```
Work inside the WSL home folder, not `/mnt/c/...`: Ansible ignores `ansible.cfg` in world-writable Windows folders
(or `export ANSIBLE_CONFIG=$PWD/ansible.cfg`).

### 3. Provision EC2 + set up the server (one command)
```bash
ansible-playbook site.yml
```
Creates the key pair (`~/.ssh/nlp-api.pem`), security group and instance, installs Docker, JDK 21, Maven, Ansible,
k3s and Jenkins, then prints the Jenkins URL and its initial admin password.
To use another region or size, edit `group_vars/all.yml` and `inventory/aws_ec2.yml`.

### 4. Jenkins pipeline
1. Push this project to GitHub (public repo, or add a GitHub credential in Jenkins).
2. Open `http://<EC2_IP>:8080`, paste the password, choose **Install suggested plugins**, create your admin user.
3. **New Item** → `nlp-api` → **Pipeline** → Definition: *Pipeline script from SCM* → Git → your repo URL →
   branch `*/main` → Script Path `Jenkinsfile` → Save → **Build Now**.

The pipeline runs **Maven build & test → Docker image → load into Kubernetes → Ansible deploy + smoke test**.
After the first build Jenkins polls the repo every ~2 minutes, so every `git push` redeploys with a rolling update.

### 5. Tear down (stop the bill)
`m7i-flex.large` is free-tier eligible, but free hours/credits run out if it stays on. When you're done:
```bash
ansible-playbook teardown.yml
```

## How each syllabus topic is covered

| # | Topic | Where / how |
|---|-------|-------------|
| 1 | Create and manage an AWS account | Step 1: root MFA, budget alert, least-privilege IAM user + access keys used by Ansible |
| 2 | Amazon EC2 | `site.yml` play 1: `ec2_key`, `ec2_security_group`, `ec2_ami_info`, `ec2_instance` (20 GB gp3, tags); `teardown.yml` |
| 3 | Setting up Linux instances | `site.yml` play 2 on Ubuntu 24.04: apt packages and repos, users/groups, sudoers, systemd services, cloud-init |
| 4 | Ansible modules, plugins | Modules: `apt`, `deb822_repository`, `user`, `copy`, `shell`, `slurp`, `wait_for`, `uri`, `service`, `debug`, `meta`. Plugins: `aws_ec2` **inventory** plugin, `profile_tasks` **callback** plugin, `url`/`template` **lookup** plugins, `from_yaml_all`/`b64decode` **filter** plugins |
| 5 | Ansible collections in playbooks | `requirements.yml`; FQCNs `amazon.aws.*`, `kubernetes.core.k8s`, `ansible.posix.profile_tasks`; collection action group in `module_defaults: group/amazon.aws.aws` |
| 6 | Docker architecture | Docker client (Jenkins `docker build`) → `dockerd` on EC2 → image layers in the local store → container runtime (containerd, which k3s also uses) |
| 7 | Docker images and containers | `Dockerfile` (Temurin JRE base layer + app jar layer, non-root user), `.dockerignore`, one image tag per build, `docker save` → `k3s ctr images import` |
| 8 | Kubernetes cluster architecture | k3s: control plane (API server, scheduler, controller manager, datastore) + node (kubelet, kube-proxy, containerd). `k8s/nlp-api.yaml`: Deployment (2 replicas, readiness/liveness probes, resource limits, rolling update) + NodePort Service |
| 9 | DevOps principles, delivery pipeline, tools, DevOps on cloud | Everything as code (infrastructure, configuration, pipeline), automated tests gate every deploy, small frequent releases, cloud infrastructure on AWS. Flow: code → build → test → package → release → deploy → verify |
| 10 | CI/CD with Jenkins | `Jenkinsfile`: SCM polling trigger, 4 stages, JUnit test report, archived jar, deploy only if tests pass |
| 11 | CI/CD with Maven | `pom.xml`: Spring Boot parent (dependency management), `clean verify` lifecycle (compile → test → package), Surefire reports, `target/nlp-api.jar` |
