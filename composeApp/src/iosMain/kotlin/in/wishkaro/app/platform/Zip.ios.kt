package `in`.wishkaro.app.platform

/**
 * iOS is kept compiling but is not shipped in v1 (D15), and the actuals in `iosMain` are
 * deliberate stubs. An empty map means "this file could not be read", which the import flow
 * already has a path for — so a future iOS build degrades to CSV-only rather than crashing,
 * and whoever ships it has one function to write.
 */
actual fun readZipTextEntries(bytes: ByteArray): Map<String, String> = emptyMap()
