package imageprocessor.api.tasks

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.files.fileExtension

// The extension follows the content type, not the user's name: a valid image with HTML appended would open as a page.
fun originalDownloadName(fileName: String, contentType: ContentType): String =
    "${stemOf(fileName)}.${contentType.fileExtension}"

fun variantDownloadName(fileName: String, variantName: String, contentType: ContentType): String =
    "${stemOf(fileName)}-$variantName.${contentType.fileExtension}"

private fun stemOf(fileName: String): String = fileName.substringBeforeLast('.').ifEmpty { "image" }
