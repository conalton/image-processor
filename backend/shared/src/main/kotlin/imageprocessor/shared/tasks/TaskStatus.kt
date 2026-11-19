package imageprocessor.shared.tasks

enum class TaskStatus {
    PENDING_UPLOAD,
    PROCESSING,
    DONE,
    INPUT_REJECTED,
    FAILED,
}
