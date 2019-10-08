def dockerTags = ['master': 'latest', 'develop': 'develop', 'trunk/soranet': 'SORANET']
pipeline {
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }
    agent {
        docker {
            label 'd3-build-agent'
            image 'openjdk:8-jdk-alpine'
            args '-v /var/run/docker.sock:/var/run/docker.sock -v /tmp:/tmp'
        }
    }
    stages {
        stage('Test') {
            steps {
                script {
                  withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'login', passwordVariable: 'password')]) {
                    sh """#!/bin/sh
                      apk update && apk add docker
                      docker login --username ${login} --password '${password}' https://nexus.iroha.tech:19002
                      ./gradlew clean test --info
                     """
                    }
                }
            }
        }
        stage('Build') {
            steps {
                script {
                    sh "./gradlew clean build --info"
                }
            }
        }
        stage('Push artifacts') {
            when {
              expression { return (env.GIT_BRANCH in dockerTags || env.TAG_NAME) }
            }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'DOCKER_REGISTRY_USERNAME', passwordVariable: 'DOCKER_REGISTRY_PASSWORD')]) {
                        env.DOCKER_REGISTRY_URL = "https://nexus.iroha.tech:19002"
                        env.TAG = env.TAG_NAME ? env.TAG_NAME : dockerTags[env.GIT_BRANCH]
                        sh "./gradlew dockerPush"
                    }
                }
            }
        }
    }
    post {
        cleanup {
            cleanWs()
        }
    }
}
