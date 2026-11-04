// CI/CD: Maven build + test -> Docker image -> load into Kubernetes (k3s) -> Ansible deploy + smoke test
pipeline {
    agent any
    triggers { pollSCM('H/2 * * * *') } // checks the repo every ~2 min, no inbound webhook needed
    environment { IMAGE = "nlp-api:${env.BUILD_NUMBER}" }

    stages {
        stage('Maven: build & test') {
            steps { sh 'mvn -B clean verify' }
            post { always { junit 'target/surefire-reports/*.xml' } }
        }
        stage('Docker: build image') {
            steps { sh 'docker build -t $IMAGE .' }
        }
        stage('Kubernetes: load image') {
            steps { sh 'docker save $IMAGE | sudo k3s ctr images import -' }
        }
        stage('Ansible: deploy') {
            steps { dir('ansible') { sh 'ansible-playbook -i localhost, deploy.yml -e image=$IMAGE' } }
        }
    }

    post { success { archiveArtifacts 'target/nlp-api.jar' } }
}
