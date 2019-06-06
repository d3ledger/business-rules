pipeline {
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }
    agent { label 'd3-build-agent' }
    stages {
        stage('Tests') {
            steps {
                script {
                    checkout scm
                    docker.image("gradle:5.4-jdk8-slim")
                            .inside("-v /var/run/docker.sock:/var/run/docker.sock -v /tmp:/tmp") {
                        sh "gradle test --info"
                    }
                }
            }
            post {
                cleanup {
                    cleanWs()
                }
            }
        }

        stage('Build and push docker images') {
          agent { label 'd3-build-agent' }
          steps {
            script {
              def scmVars = checkout scm
              if (env.BRANCH_NAME ==~ /(master|develop|reserved)/ || env.TAG_NAME) {
                    withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'login', passwordVariable: 'password')]) {

                      TAG = env.TAG_NAME ? env.TAG_NAME : env.BRANCH_NAME
                      iC = docker.image("gradle:4.10.2-jdk8-slim")
                      iC.inside(" -e JVM_OPTS='-Xmx3200m' -e TERM='dumb'"+
                      " -v /var/run/docker.sock:/var/run/docker.sock -v /tmp:/tmp"+
                      " -e DOCKER_REGISTRY_URL='https://nexus.iroha.tech:19002'"+
                      " -e DOCKER_REGISTRY_USERNAME='${login}'"+
                      " -e DOCKER_REGISTRY_PASSWORD='${password}'"+
                      " -e TAG='${TEST}'") {
                        sh "gradle shadowJar"
                        sh "gradle dockerPush"
                      }
                     }
                  }
            }
          }
      }
    }
}
