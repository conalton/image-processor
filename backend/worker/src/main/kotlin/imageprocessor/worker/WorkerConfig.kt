package imageprocessor.worker

import imageprocessor.shared.requiredVariable
import imageprocessor.shared.store.TaskTableConfig
import imageprocessor.shared.store.loadTaskTableConfig

data class WorkerConfig(
    val region: String,
    val imagesBucket: String,
    val originalsPrefix: String,
    val derivedPrefix: String,
    val taskTable: TaskTableConfig,
)

fun loadWorkerConfig(environment: Map<String, String>): WorkerConfig =
    WorkerConfig(
        region = requiredVariable(environment, "AWS_REGION"),
        imagesBucket = requiredVariable(environment, "IMAGES_BUCKET"),
        originalsPrefix = requiredVariable(environment, "ORIGINALS_PREFIX"),
        derivedPrefix = requiredVariable(environment, "DERIVED_PREFIX"),
        taskTable = loadTaskTableConfig(environment),
    )
