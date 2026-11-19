package imageprocessor.worker.deadletter

import imageprocessor.shared.requiredVariable
import imageprocessor.shared.store.TaskTableConfig
import imageprocessor.shared.store.loadTaskTableConfig

data class DeadLetterConfig(
    val region: String,
    val originalsPrefix: String,
    val taskTable: TaskTableConfig,
)

fun loadDeadLetterConfig(environment: Map<String, String>): DeadLetterConfig =
    DeadLetterConfig(
        region = requiredVariable(environment, "AWS_REGION"),
        originalsPrefix = requiredVariable(environment, "ORIGINALS_PREFIX"),
        taskTable = loadTaskTableConfig(environment),
    )
