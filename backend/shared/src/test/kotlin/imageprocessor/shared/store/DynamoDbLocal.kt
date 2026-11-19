package imageprocessor.shared.store

import imageprocessor.shared.requiredVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.net.URI
import java.util.UUID

fun dynamoDbLocalClient(): DynamoDbClient =
    DynamoDbClient.builder()
        .endpointOverride(URI.create(requiredVariable(System.getenv(), "DYNAMODB_ENDPOINT")))
        .region(Region.EU_NORTH_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
        .build()

fun createTaskTable(client: DynamoDbClient): TaskTableConfig {
    val config = TaskTableConfig(
        tableName = "tasks-${UUID.randomUUID()}",
    )
    val request = CreateTableRequest.builder()
        .tableName(config.tableName)
        .attributeDefinitions(
            AttributeDefinition.builder().attributeName(ID).attributeType(ScalarAttributeType.S).build(),
        )
        .keySchema(KeySchemaElement.builder().attributeName(ID).keyType(KeyType.HASH).build())
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .build()
    client.createTable(request)
    return config
}
