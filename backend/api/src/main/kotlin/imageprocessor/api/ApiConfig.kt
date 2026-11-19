package imageprocessor.api

import imageprocessor.shared.requiredVariable
import imageprocessor.shared.store.TaskTableConfig
import imageprocessor.shared.store.loadTaskTableConfig
import java.time.Duration

data class ApiConfig(
    val region: String,
    val imagesBucket: String,
    val originalsPrefix: String,
    val s3PublicEndpoint: String,
    val taskTable: TaskTableConfig,
    val retention: Duration,
    val sdkCallTimeout: Duration,
)

fun loadApiConfig(environment: Map<String, String>): ApiConfig {
    val retentionDays = requiredVariable(environment, "RETENTION_DAYS").toLongOrNull()
    require(retentionDays != null && retentionDays > 0) {
        "Environment variable RETENTION_DAYS is not a positive whole number of days"
    }
    val sdkCallTimeoutMs = requiredVariable(environment, "SDK_CALL_TIMEOUT_MS").toLongOrNull()
    require(sdkCallTimeoutMs != null && sdkCallTimeoutMs > 0) {
        "Environment variable SDK_CALL_TIMEOUT_MS is not a positive whole number of milliseconds"
    }
    return ApiConfig(
        region = requiredVariable(environment, "AWS_REGION"),
        imagesBucket = requiredVariable(environment, "IMAGES_BUCKET"),
        originalsPrefix = requiredVariable(environment, "ORIGINALS_PREFIX"),
        s3PublicEndpoint = requiredVariable(environment, "S3_PUBLIC_ENDPOINT"),
        taskTable = loadTaskTableConfig(environment),
        retention = Duration.ofDays(retentionDays),
        sdkCallTimeout = Duration.ofMillis(sdkCallTimeoutMs),
    )
}
