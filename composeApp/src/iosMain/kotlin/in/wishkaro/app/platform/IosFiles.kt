package `in`.wishkaro.app.platform

/** iOS is compile-only in v1 (D15). A null pick is "the user backed out", which the flow handles. */
class IosFileGateway : FileGateway {
    override suspend fun pickSpreadsheet(): PickedFile? = null

    override suspend fun saveCsv(suggestedName: String, content: String): Boolean = false

    override suspend fun pickPhoto(): PickedPhoto? = null
}
