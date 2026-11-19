package imageprocessor.api.contract

fun specPath(): String =
    requireNotNull(System.getProperty("openapi.spec")) {
        "System property openapi.spec is not set"
    }
