@Library('jenkins-library' ) _
new org.bakong.mainLibrary().call(
    registry:'https://docker.soramitsu.co.jp',
    nexusUserId: 'bot-soranet-rw', 
    registryCredentialsForTests: 'bot-soranet-ro',
    dockerTags: ['master': 'latest', 'develop': 'develop']
)