package imageprocessor.shared

fun requiredVariable(environment: Map<String, String>, name: String): String {
    val value = environment[name]
    require(!value.isNullOrBlank()) { "Environment variable $name is not set" }
    return value
}
