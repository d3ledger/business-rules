# Business rules validation service

Backend module for validating custom business rules in [Iroha](https://github.com/hyperledger/iroha).<br/>
Temporal name of the project is BRVS (**B**usiness **R**ules **V**alidation **S**ervice).
**BRVS** is useful in case you need to validate user transactions somehow since Iroha has no smart-contracts implemented so far.<br/>
**BRVS** instances run some preconfigured [validators](./brvs-rules/src/main/java/iroha/validation/rules/Rule.java) combining predefined [rules](./brvs-rules/src/main/java/iroha/validation/validators/Validator.java) passing a user multisignature transaction from ['Pending Transactions pool'](https://iroha.readthedocs.io/en/latest/api/queries.html#get-pending-transactions) as an argument and decide if it should be signed and sent to the Iroha peer.

## Getting Started

Each **BRVS** instance should use their own separate key pair in Iroha.<br/>
All those key pairs must be attached to users by running ['Add Signatory'](https://iroha.readthedocs.io/en/latest/api/commands.html#add-signatory) Iroha command.<br/>
Consequently users quorum should be increased proportionally to the **BRVS** keys attached amount<br/> (i.e. `quorum -> 'old quorum' + 2/3 of BRVS instances`).<br/>
So think about the quorum modification beforehand.

The following instructions will get you a copy of the project up and running on your local machine for development and testing purposes. See deployment for notes on how to deploy the project on a live system.

### Installing

A step by step series of examples that tell you how to get a development env running

Clone the repository

```
git clone https://github.com/d3ledger/business-rules.git
```

Create a key pair for each planned **BRVS** instance using ed25519 sha3 crypto

Create an account with permission ['Can get blocks'](https://iroha.readthedocs.io/en/latest/maintenance/permissions.html#block-stream) for **BRVS** (one for all instances) in your Iroha

Perform ['Add Signatory'](https://iroha.readthedocs.io/en/latest/api/commands.html#add-signatory) Iroha command to the account created for every key pair created

Perform ['Add Signatory'](https://iroha.readthedocs.io/en/latest/api/commands.html#add-signatory) Iroha command to the user accounts for every key pair created and modify their quorum accordingly

Modify **BRVS** [configuration](./config/context/spring-context.xml) in a way you need. You can extend it by introducing new Rules and Validators implementations

Submit correct data using environment variables [application.properties](brvs-core/src/main/resources/application.properties)

Run clean build and farJar gradle tasks

```
cd ./business-rules
./gradlew clean build
./gradlew :brvs-core:shadowJar
```

Modify [Dockerfile](./Dockerfile) if needed

Submit correct data into [docker-compose.yml](./deploy/docker-compose.yml) (i.e. you use separate rmq or mongo instance)

Make sure Iroha is running and run BRVS
```
docker-compose -f deploy/docker-compose.yml --build up
```

Register all the users to be monitored by BRVS using POST request
```
<brvs-hostname>:8080/brvs/rest/register/<account-id>
```

To ensure BRVS works send some transaction from a user perspective and query BRVS for the validation result using GET request
```
<brvs-hostname>:8080/brvs/rest/status/<transaction-hash>
```

## API examples
### Querying validation results
- Querying invalid or unknown transaction hash
```
localhost:8080/brvs/rest/status/123

Result:
(404)
Body:
{
    "status": "UNKNOWN",
    "reason": ""
}
```
- Querying transaction known by BRVS but not yet processed
```
localhost:8080/brvs/rest/status/13EDF1F41991ABF414B3252253C3D5B5198BDDAAFCCB181BFB334758C3C4ABEA

Result:
(200)
Body:
{
    "status": "UNKNOWN",
    "reason": ""
}
```
- Querying validated transaction
```
localhost:8080/brvs/rest/status/13EDF1F41991ABF414B3252253C3D5B5198BDDAAFCCB181BFB334758C3C4ABEA

Result:
(200)
Body:
{
    "status": "VALIDATED",
    "reason": ""
}
```
- Querying transaction rejected by BRVS
```
localhost:8080/brvs/rest/status/6A54D95EFD400F316F0396914724B247E57065A66CABFD86427EA73BEFA886AC

Result:
(200)
Body:
{
    "status": "REJECTED",
    "reason": "iroha.validation.validators.impl.SimpleAggregationValidator"
}
```
The reason message is just a string. It is still under development

## Running the tests

To run the tests just run
```
./gralew test
```
The most interesting test about general workflow is [IrohaIntegrationTest](./brvs-core/src/test/java/iroha/validation/behavior/IrohaIntegrationTest.java). It will help you to learn how to use BRVS in your system.
