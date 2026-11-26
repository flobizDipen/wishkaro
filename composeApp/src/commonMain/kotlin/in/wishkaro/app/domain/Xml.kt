package `in`.wishkaro.app.domain

/**
 * A scanner for the small, well-known slice of XML an `.xlsx` is made of.
 *
 * NOT a general XML parser, and it must not grow into one. It walks tags and reads
 * attributes and text, which is the whole of what `sheet1.xml`, `sharedStrings.xml` and
 * `styles.xml` need. Namespaces, DTDs, processing instructions and CDATA are skipped rather
 * than modelled, because a spreadsheet does not use them and because every feature added
 * here is a feature that has to be right against a hostile file.
 *
 * It exists because the alternative on Kotlin Multiplatform is a platform XML parser behind
 * an expect/actual — which would put the part most likely to be wrong on the side that
 * `commonTest` cannot reach. See docs/M3_PLAN.md §2.2.
 */
internal data class XmlTag(
    val name: String,
    val attributes: Map<String, String>,
    val isEnd: Boolean,
    val selfClosing: Boolean,
)

internal class XmlCursor(private val xml: String) {
    private var index = 0

    /** The next tag, or null at the end of the document. Comments and PIs are skipped. */
    fun next(): XmlTag? {
        while (true) {
            val open = xml.indexOf('<', index)
            if (open < 0) {
                index = xml.length
                return null
            }
            if (xml.startsWith("<!--", open)) {
                val close = xml.indexOf("-->", open)
                index = if (close < 0) xml.length else close + 3
                continue
            }
            if (xml.startsWith("<?", open) || xml.startsWith("<!", open)) {
                val close = xml.indexOf('>', open)
                index = if (close < 0) xml.length else close + 1
                continue
            }
            val close = findTagEnd(open)
            if (close < 0) {
                index = xml.length
                return null
            }
            val inner = xml.substring(open + 1, close)
            index = close + 1
            val isEnd = inner.startsWith("/")
            val selfClosing = inner.endsWith("/")
            val body = inner.trim('/', ' ', '\n', '\r', '\t')
            val name = body.takeWhile { !it.isWhitespace() }
            return XmlTag(
                // Namespace prefixes are dropped: `x:row` and `row` are the same element to
                // everything this reads, and some writers emit prefixes where others do not.
                name = name.substringAfterLast(':'),
                attributes = if (isEnd) emptyMap() else parseAttributes(body.removePrefix(name)),
                isEnd = isEnd,
                selfClosing = selfClosing,
            )
        }
    }

    /** The text between here and the next tag, with entities decoded. */
    fun text(): String {
        val open = xml.indexOf('<', index)
        val end = if (open < 0) xml.length else open
        val raw = xml.substring(index, end)
        index = end
        return decodeEntities(raw)
    }

    /** A `>` inside an attribute value is not the end of the tag. */
    private fun findTagEnd(open: Int): Int {
        var i = open + 1
        var quote: Char? = null
        while (i < xml.length) {
            val c = xml[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i += 1
        }
        return -1
    }

    private fun parseAttributes(source: String): Map<String, String> {
        if (source.isBlank()) return emptyMap()
        val attributes = mutableMapOf<String, String>()
        var i = 0
        while (i < source.length) {
            while (i < source.length && (source[i].isWhitespace() || source[i] == '/')) i += 1
            val nameStart = i
            while (i < source.length && source[i] != '=' && !source[i].isWhitespace()) i += 1
            if (i >= source.length) break
            val name = source.substring(nameStart, i)
            while (i < source.length && source[i] != '=') i += 1
            if (i >= source.length) break
            i += 1
            while (i < source.length && source[i].isWhitespace()) i += 1
            if (i >= source.length) break
            val quote = source[i]
            if (quote != '"' && quote != '\'') break
            i += 1
            val valueStart = i
            while (i < source.length && source[i] != quote) i += 1
            if (name.isNotEmpty()) {
                attributes[name.substringAfterLast(':')] = decodeEntities(source.substring(valueStart, i))
            }
            i += 1
        }
        return attributes
    }
}

internal fun decodeEntities(raw: String): String {
    if ('&' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '&') {
            out.append(c)
            i += 1
            continue
        }
        val end = raw.indexOf(';', i)
        if (end < 0 || end - i > 10) {
            out.append(c)
            i += 1
            continue
        }
        when (val entity = raw.substring(i + 1, end)) {
            "amp" -> out.append('&')
            "lt" -> out.append('<')
            "gt" -> out.append('>')
            "quot" -> out.append('"')
            "apos" -> out.append('\'')
            else -> {
                val code = when {
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.drop(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                    else -> null
                }
                // An entity this does not know is left exactly as written rather than
                // dropped: a name is better off carrying `&foo;` than losing characters.
                if (code != null) out.append(code.toChar()) else out.append(raw, i, end + 1)
            }
        }
        i = end + 1
    }
    return out.toString()
}
