package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.PhoneNumbers

/**
 * A phone number as a country code you pick and a number you type.
 *
 * ## Why the code is visible at all
 *
 * `whatsAppNumber()` already accepts every shape a number is typed in, and has a test per
 * shape. The field gave no sign of that, so the user was guessing at a question the app had
 * already answered — and guessing wrong is silent: WhatsApp opens a chat with a number that
 * does not exist, or its "not on WhatsApp" sheet, and nobody can tell why.
 *
 * `+91` as a default the user can see turns the guess into a default they can change.
 *
 * ## The code is a dropdown rather than a fixed prefix
 *
 * "for phone we can have +91" most obviously means prefixing the field with a hardcoded
 * `+91`. That would take away a case this app already supports: `whatsAppNumber()` handles
 * `+44 7700 900123` and is tested on it, and WishKaro is for remembering the birthdays of
 * **families**, which in this country are spread across the Gulf, the UK, the US and
 * Singapore. India is the default; it is not the only one.
 *
 * ## What is stored
 *
 * One string, as before — `"+91 9876543210"` — which `whatsAppNumber()` parses as its first
 * test case. Nothing migrates. `PhoneNumbers.join` returns **null** for an empty number so
 * that "no phone" stays a real absence rather than becoming a bare country code that the
 * completeness score would count and WhatsApp would try to dial.
 */
@Composable
fun PhoneField(
    dialCode: String,
    national: String,
    onDialCodeChange: (String) -> Unit,
    onNationalChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Phone",
    imeAction: ImeAction = ImeAction.Next,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Narrow, because "+971" is the longest thing it ever shows and the number beside it
        // is what the user is actually typing.
        WishKaroDropdown(
            label = "Code",
            options = PhoneNumbers.COUNTRY_CODES.map { it.dialCode },
            selected = dialCode,
            onSelect = onDialCodeChange,
            placeholder = PhoneNumbers.DEFAULT_DIAL_CODE,
            tag = "phone_code",
            // The country name in the OPEN menu, the bare code in the CLOSED field. A list
            // of bare dial codes is unreadable; "+44  United Kingdom" in a field this narrow
            // renders as "+44  Unite", which looks like a bug rather than a truncation.
            // Caught on the device, not in a preview.
            optionLabel = { code ->
                PhoneNumbers.COUNTRY_CODES.firstOrNull { it.dialCode == code }
                    ?.let { "${it.dialCode}  ${it.label}" } ?: code
            },
            selectedLabel = { it },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = national,
            onValueChange = { typed ->
                onNationalChange(typed.filter(Char::isDigit).take(FieldLimits.PHONE))
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = imeAction,
            ),
            modifier = Modifier.weight(1.6f).testTag("phone_number"),
        )
    }
}
