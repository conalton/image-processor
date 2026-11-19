package imageprocessor.shared.aws

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

class AwsClients(private val region: Region) {
    fun dynamoDb(apiCallTimeout: Duration? = null): DynamoDbClient {
        val builder = DynamoDbClient.builder()
            .region(region)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
        if (apiCallTimeout != null) {
            builder.overrideConfiguration { it.apiCallTimeout(apiCallTimeout) }
        }
        return builder.build()
    }

    fun s3(): S3Client =
        S3Client.builder()
            .region(region)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()

    fun s3Presigner(endpoint: URI): S3Presigner =
        S3Presigner.builder()
            .region(region)
            .endpointOverride(endpoint)
            .build()
}
