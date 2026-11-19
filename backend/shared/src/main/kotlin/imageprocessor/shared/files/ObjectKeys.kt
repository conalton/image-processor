package imageprocessor.shared.files

fun originalKey(originalsPrefix: String, id: String): String = "$originalsPrefix$id"

fun taskIdOfOriginalKey(originalsPrefix: String, key: String): String? {
    if (!key.startsWith(originalsPrefix)) {
        return null
    }
    val id = key.removePrefix(originalsPrefix)
    return if (id.isEmpty() || id.contains('/')) null else id
}
