package `in`.wishkaro.app.platform

/**
 * iOS is kept compiling but is not shipped in v1 (D15); the actuals here are deliberate
 * stubs. Reporting DENIED_PERMANENTLY rather than DENIED is the honest answer: there is no
 * dialog to show and asking again would do nothing, which is exactly the state that outcome
 * describes. Whoever ships iOS replaces this with the CNContactStore equivalent.
 */
class IosContactsGateway : ContactsGateway {
    override fun hasPermission(): Boolean = false

    override suspend fun requestPermission(): PermissionOutcome = PermissionOutcome.DENIED_PERMANENTLY

    override fun openAppSettings() = Unit

    override suspend fun readContacts(): List<DeviceContact> = emptyList()
}
