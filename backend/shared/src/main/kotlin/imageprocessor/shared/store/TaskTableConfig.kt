package imageprocessor.shared.store

import imageprocessor.shared.requiredVariable

data class TaskTableConfig(val tableName: String)

fun loadTaskTableConfig(environment: Map<String, String>): TaskTableConfig =
    TaskTableConfig(tableName = requiredVariable(environment, "TASKS_TABLE"))
