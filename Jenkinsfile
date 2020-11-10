node {
    stage('checkout') {
        checkout scm
    }

    stage('build') {
        sh './gradlew clean build publish'
    }

    stage('archive') {
        archive 'build/libs/*.jar'
    }
}
