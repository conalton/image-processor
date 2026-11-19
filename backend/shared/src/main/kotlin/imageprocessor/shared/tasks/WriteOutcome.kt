package imageprocessor.shared.tasks

sealed interface WriteOutcome {
    data object Written : WriteOutcome
    data object Skipped : WriteOutcome
}
