# Business rules validation service

Backend module for validating custom business rules in [Iroha](https://github.com/hyperledger/iroha).<br/>
Temporal name of the project is BRVS (**B**usiness **R**ules **V**alidation **S**ervice).
**BRVS** is useful in case you need to validate user transactions somehow since Iroha has no smart-contracts implemented so far.<br/>
**BRVS** instances run a preconfigured [validator](./brvs-rules/src/main/java/iroha/validation/validators/Validator.java) combining predefined [rules](./brvs-rules/src/main/java/iroha/validation/rules/Rule.java) passing a user multisignature transaction from ['Pending Transactions pool'](https://iroha.readthedocs.io/en/latest/api/queries.html#get-pending-transactions) as an argument and decide if it should be signed and sent to the Iroha peer.

## Getting Started

Each **BRVS** instance should use their own separate key pair in Iroha.<br/>
All those key pairs must be attached to users by running ['Add Signatory'](https://iroha.readthedocs.io/en/latest/api/commands.html#add-signatory) Iroha command.<br/>
Consequently users quorum should be increased proportionally to the **BRVS** keys attached amount<br/> (i.e. `quorum -> 'old quorum' + 2/3 of BRVS instances`).<br/>
So think about the quorum modification beforehand.

The following instructions will get you a copy of the project up and running on your local machine for development and testing purposes. See deployment for notes on how to deploy the project on a live system.

### Configuration

All the application configuration variables are defined in the [application.properties](brvs-core/src/main/resources/application.properties) file. Here is a brief description of each general parameter:
```
CREDENTIAL_ACCOUNTID - Iroha credential account id
CREDENTIAL_PUBKEY - Iroha credential public key
CREDENTIAL_PRIVKEY - Iroha credential private key
BRVS_USERKEYSPATH - Filesystem path of the directory to save the generated keys. They are used by BRVS to be a subset of a user quorum.
BRVS_USERKEYSCOUNT - Amount of keys supported
BRVS_LOCALHOSTNAME - Name of the BRVS instance (used to identify BRVS hosts in a multi-instance environment)
BRVS_USERDOMAINS - User domains to be checked by the BRVS instance
BRVS_PORT - Port to expose endpoints to
USER_SIGNATORIES_KEY - Iroha account detail key to store user signatories in Json
ACCOUNTS_HOLDER - Iroha account id to store a list of users accounts
IROHA_HOST - Iroha host
IROHA_PORT - Iroha port
MONGO_HOST - MongoDB host (if you use it in your context file)
MONGO_PORT - MongoDB port (if you use it in your context file)
REPOSITORY_ACCOUNTID - Iroha account id of dynamic rules storage
SETTER_ACCOUNTID - Iroha account id of dynamic rules and settings setter
SETTINGS_ACCOUNTID - Iroha account id of dynamic rules tweaks
```

### Dynamic rules (new)

BRVS now supports dynamic on-chain rules.
In order to use it just upload correct groovy class implementing ['Rule'](./brvs-rules/src/main/java/iroha/validation/rules/Rule.java) interface to an Iroha detail of `REPOSITORY_ACCOUNTID` with key you want to represent the rule's name. Don't forget that such transaction must be created by `SETTER_ACCOUNTID` account.
Then commit an account detail transaction to the `SETTINGS_ACCOUNTID` using the same setter and the following format: `<name you created> -> "true"/"false"`. True for enabling the rule, false for disabling. It can be done dynamically on any time.

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

Submit correct data using environment variables [application.properties](brvs-core/src/main/resources/application.properties) described previously

Run `clean shadowJar` gradle tasks

```
cd ./business-rules
./gradlew clean build shadowJar
```

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
(200)
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
    "status": "PENDING",
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
    "reason": "Client is not able to remove signatories"
}
```

### Sending Iroha transactions and executing Iroha queries using BRVS
- Sending a JSON serialized transaction
```
http://localhost:8080/brvs/rest/transaction/send

Request body (application/json):
{
    "payload": {
        "reduced_payload": {
            "commands": [
                {
                    "add_asset_quantity": {
                        "asset_id": "btc#bitcoin",
                        "amount": "1"
                    }
                }
            ],
            "creator_account_id": "test@notary",
            "created_time": "1563356513188",
            "quorum": 1
        }
    },
    "signatures": [
        {
            "public_key": "092E71B031A51ADAE924F7CD944F0371AE8B8502469E32693885334DEDCC6001",
            "signature": "E865F94EEF73749D0C57348E0DB2B3547609ABC3C3A4E8FE7976BB3F574D219C84F652B32AF6DFF2412E32AFBCD70CC6C728F9065B10BC43E712AB0B4D46F009"
        }
    ]
}

Response contains Iroha transaction statuses streaming:

{"tx_status": "ENOUGH_SIGNATURES_COLLECTED","tx_hash": "9f9250c45911e2b3105af08c6e8f04d53587c160674de97144ff9cdb04dec70a"}
{"tx_status": "STATEFUL_VALIDATION_SUCCESS","tx_hash": "9f9250c45911e2b3105af08c6e8f04d53587c160674de97144ff9cdb04dec70a"}
{"tx_status": "COMMITTED","tx_hash": "9f9250c45911e2b3105af08c6e8f04d53587c160674de97144ff9cdb04dec70a"}

```
If you want BRVS to sign the transaction with its private key use `*/brvs/rest/transaction/send/sign` endpoint then.

If you want to send a batch of transactions use `*/brvs/rest/batch/send` or `*/brvs/rest/batch/send/sign`

- Executing a JSON serialized query
```
http://localhost:8080/brvs/rest/query

Request body (application/json):
{
    "payload": {
        "meta": {
            "created_time": "1563364886062",
            "creator_account_id": "test@notary",
            "query_counter": "1"
        },
        "get_account": {
            "account_id": "test@notary"
        }
    },
    "signature": {
        "public_key": "092E71B031A51ADAE924F7CD944F0371AE8B8502469E32693885334DEDCC6001",
        "signature": "574EB42A23AC0BC5B8D28EA3E5FD7595AC2C33A6647C6131E2C8433401D4FF7E890B25F920D35A96FA0622881153FF4044140C03F710C5BF1D1185A29748B809"
    }
}

Response contains Iroha query result:
{
    "account_response": {
        "account": {
            "account_id": "test@notary",
            "domain_id": "notary",
            "quorum": 1,
            "json_data": "{}"
        },
        "account_roles": [
            "none",
            "tester",
            "registration_service",
            "client"
        ]
    },
    "query_hash": "a3fdd447e8d47e3f9466a44e8c9ebd3e6cabeb9a3b0f7dc8205c955ef5fb7bfb"
}

```
If you want BRVS to sign the transaction with its private key use `*/brvs/rest/query/send/sign` endpoint then.


Full Iroha protobuf schema could be found [here](https://github.com/hyperledger/iroha/tree/master/shared_model/schema)

## Running the tests

To run the tests just run
```
./gralew test
```
The most interesting test about general workflow is [IrohaIntegrationTest](./brvs-core/src/test/java/iroha/validation/behavior/IrohaIntegrationTest.java). It will help you to learn how to use BRVS in your system.
