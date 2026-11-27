# Bundled fonts

> The binaries are in `composeApp/src/commonMain/composeResources/font/`. The licences and this
> note are one directory away, in `composeResources/files/licenses/`, for a mechanical reason:
> Compose Multiplatform turns **every** file in a `font/` directory into a `FontResource`
> accessor, so a `.txt` beside the `.ttf`s is either a font the app thinks it has or a generated
> identifier that will not compile. Here they are still inside the resource tree, which means
> they are **shipped inside the app** as well as committed — which is more than "alongside"
> would have given.

Both families are bundled as **files in this repository**, not fetched through
`androidx.compose.ui.text.googlefonts`. `BUILD_PROMPT_M7.md` is explicit about why: *"the app
must render identically in airplane mode and on first launch with no network."* A downloadable
font provider cannot promise either, and the first launch — where the value carousel and the
sign-in wordmark are the whole of the first impression — is exactly when the provider is least
likely to have been primed.

Both are **unmodified upstream binaries**. Nothing here was instanced, subsetted or renamed.
That matters: both families carry a Reserved Font Name under the OFL, so a derivative built
here (by `fonttools varLib.instancer`, say) could not keep the name. Vendoring the foundry's
own static builds avoids the question rather than reasoning about it.

| File | Family | Weight | Upstream | Version | SHA-256 |
|---|---|---|---|---|---|
| `inter_regular.ttf` | Inter | 400 | [rsms/inter](https://github.com/rsms/inter) release `v4.1`, `extras/ttf/Inter-Regular.ttf` | 4.001 | `40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82` |
| `inter_medium.ttf` | Inter | 500 | same release, `extras/ttf/Inter-Medium.ttf` | 4.001 | `97ad806f526e41546d46365bb3a393145f75b7b1568913db74549ad8b8dba872` |
| `inter_semibold.ttf` | Inter | 600 | same release, `extras/ttf/Inter-SemiBold.ttf` | 4.001 | `78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3` |
| `fraunces_regular.ttf` | Fraunces 9pt | 400 | [undercasetype/Fraunces](https://github.com/undercasetype/Fraunces), `fonts/ttf/Fraunces9pt-Regular.ttf` | 2.001 | `54f7ec6290e8ddb967e1ebddd2cadb706d6b448254e3489c04f2bcd265db5fa2` |
| `fraunces_semibold.ttf` | Fraunces 9pt | 600 | same, `fonts/ttf/Fraunces9pt-SemiBold.ttf` | 2.001 | `b0959bfeb942f2a4dcd929a728f8010e4e5369b709c894a592a0bf5e454fc451` |
| `fraunces_bold.ttf` | Fraunces 9pt | 700 | same, `fonts/ttf/Fraunces9pt-Bold.ttf` | 2.001 | `f27f6cf1c5f4454c257cbaa564199ef2e74462f55c43c9087b9a7636445a71cd` |

~1.57 MB in all.

## Licences

Both families are under the **SIL Open Font License 1.1**, copied here in full beside the
binaries, as the licence itself requires:

- `OFL-Inter.txt` — Copyright (c) 2016 The Inter Project Authors
- `OFL-Fraunces.txt` — Copyright 2018 The Fraunces Project Authors

This repository is public. A public repository shipping fonts without their licence is a real
problem, not a formality.

## Why `Fraunces9pt` and not another optical size

Fraunces' `opsz` axis runs 9 to 144. Almost all the Fraunces in this app is between 15sp
(section headers) and 24sp (the countdown — the date is the hero); `9pt` is the instance drawn
for that range. Using one optical size is three files rather than nine, and the display sizes
are rare enough that the slightly sturdier letterforms are a fair trade.

## Devanagari

**Neither family covers Devanagari.** `WishLanguage.HINDI.label` is `"हिन्दी"` and appears as a
chip in the wish composer, a user's own Hindi draft goes in the text field beside it, and in an
India-first app a person's name can be in any Indic script.

Android's `Typeface.Builder` appends the default fallback family to a custom typeface, so a
glyph these files do not contain should resolve through the system font rather than render as
tofu. "Should" is not a verification: `docs/M7_VERIFY.md` item 3 checks it on the handset with a
screenshot, because an India-first app rendering Hindi as empty boxes would be a worse failure
than having no custom font at all.
