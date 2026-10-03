package org.ethereumphone.andyclaw.skills.builtin

import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Where `fetch_webpage` may connect: the public internet, and nothing on the phone or its network.
 *
 * Checked whatever the safety switch says, which is off by default. The tool is READ and reaches
 * no private data, so a run replying to a stranger may use it, and "fetch http://192.168.1.1/"
 * handed a Telegram stranger the router's page. Checked on every hop (redirects are followed by
 * hand, see WebSearchSkill) and again at connect time by [dns], so neither a redirect nor a name
 * that resolves to a private address the second time gets through.
 */
internal object PublicNetwork {

    /** Why [url] may not be fetched, or null. An IP literal is judged here: OkHttp never asks [dns] for one. */
    fun refusal(url: HttpUrl): String? {
        if (url.scheme != "http" && url.scheme != "https") return "only http and https pages can be fetched"
        val host = url.host.lowercase().trimEnd('.')
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return "$host is a local name"
        // OkHttp's own test for "this is an address": all digits and dots, or a colon. Such a host
        // is connected to directly, and Java reads `2130706433` as 127.0.0.1.
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            val address = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                return "$host is not an address that can be fetched"
            }
            if (isPrivate(address)) return "$host is a private or local address"
        }
        return null
    }

    /** Resolves names to their public addresses only; a name with none does not resolve. */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val public = Dns.SYSTEM.lookup(hostname).filterNot(::isPrivate)
            if (public.isEmpty()) throw UnknownHostException("$hostname resolves only to private or local addresses")
            return public
        }
    }

    /** Loopback, link-local, private, unique-local, CGNAT, multicast and reserved ranges, IPv4 inside IPv6 included. */
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val b = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address ->
                b[0] == 0 ||                                  // 0.0.0.0/8
                    (b[0] == 100 && b[1] in 64..127) ||       // 100.64.0.0/10, carrier-grade NAT
                    (b[0] == 192 && b[1] == 0 && b[2] == 0) || // 192.0.0.0/24
                    (b[0] == 198 && b[1] in 18..19) ||        // 198.18.0.0/15
                    b[0] >= 240                               // 240.0.0.0/4 and broadcast
            is Inet6Address -> {
                if ((b[0] and 0xfe) == 0xfc) return true       // fc00::/7, unique local
                // ::ffff:a.b.c.d, 64:ff9b::a.b.c.d (NAT64) and ::a.b.c.d carry an IPv4 address.
                val mapped = b.subList(0, 10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff
                val nat64 = b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && b.subList(4, 12).all { it == 0 }
                val compatible = b.subList(0, 12).all { it == 0 }
                if (mapped || nat64 || compatible) {
                    isPrivate(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
                } else false
            }
            else -> true
        }
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa")
}

/**
 * HTML → readable text in one pass, for `fetch_webpage`.
 *
 * The regexes this replaces were quadratic in markup the page controls — 64 KB of `<a` took
 * 25 s in the first two of them, a crafted page held a core for minutes, and STOP could not
 * interrupt a regex — so this is a scanner whose every search resumes where the last one ended:
 * linear in the page, whatever the page is.
 *
 * What it drops, as before: elements a reader never sees (`display:none`, `visibility:hidden`,
 * `opacity:0`, `hidden`) with what they hold — the injection channel — and scripts, styles,
 * templates, forms, navigation, headers, footers, `noscript` and comments. Block ends become line
 * breaks, other tags disappear, and the common entities are decoded.
 */
internal object HtmlText {

    private val DROP_CONTENT = setOf("script", "style", "template", "form", "nav", "footer", "header", "noscript")
    private val BLOCKS = setOf(
        "br", "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "article", "section",
    )
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val HIDDEN = Regex("""display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0|\bhidden\b""", RegexOption.IGNORE_CASE)

    /** A line break in the scan's output; real whitespace is collapsed before it becomes "\n". */
    private const val BREAK = '\u0001'
    private val WHITESPACE = Regex("\\s+")
    private val BREAKS = Regex("[ \u0001]*\u0001[ \u0001]*")
    private val INVISIBLE = Regex("[​‌‍﻿‎‏‪‫‬‭‮⁦⁧⁨⁩]")

    fun readable(html: String): String {
        val out = StringBuilder(minOf(html.length, 1 shl 20))
        val tagEnd = Finder(html, ">")
        val anyClose = Finder(html, "</")
        val commentEnd = Finder(html, "-->")
        val closers = HashMap<String, Finder>()
        val n = html.length
        var i = 0
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(html, i, n)
                break
            }
            out.append(html, i, lt)
            if (html.startsWith("<!--", lt)) {
                val end = commentEnd.next(lt + 4)
                if (end >= 0) {
                    out.append(' ')
                    i = end + 3
                    continue
                }
            }
            val gt = tagEnd.next(lt + 1)
            if (gt < 0) {
                // No tag ends anywhere after this: the rest is text.
                out.append(html, lt, n)
                break
            }
            val tag = html.substring(lt + 1, gt)
            val closing = tag.startsWith("/")
            val name = tag.substring(if (closing) 1 else 0).takeWhile { it.isLetterOrDigit() }.lowercase()
            val selfClosing = tag.endsWith("/")
            i = gt + 1
            if (!closing && !selfClosing && name in DROP_CONTENT) {
                val end = closers.getOrPut(name) { Finder(html, "</$name", ignoreCase = true) }.next(i)
                if (end >= 0) {
                    val endGt = tagEnd.next(end)
                    i = if (endGt < 0) n else endGt + 1
                    out.append(' ')
                    continue
                }
            }
            if (!closing && name.isNotEmpty() && HIDDEN.containsMatchIn(tag)) {
                if (selfClosing || name in VOID) {
                    out.append(' ')
                    continue
                }
                // Up to the first closing tag, as the regex this replaces did.
                val end = anyClose.next(i)
                if (end >= 0) {
                    val endGt = tagEnd.next(end)
                    i = if (endGt < 0) n else endGt + 1
                    out.append(' ')
                    continue
                }
            }
            if (name in BLOCKS) out.append(BREAK)
        }
        val text = out.toString()
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace(INVISIBLE, "")
            .replace(WHITESPACE, " ")
            .replace(BREAKS, "\n")
        return text.trim()
    }

    /**
     * The next [needle] at or after a position, for positions that only ever grow: a search
     * resumes past the last answer instead of rescanning, which is what keeps the scan linear.
     */
    private class Finder(private val s: String, private val needle: String, private val ignoreCase: Boolean = false) {
        private var searchedFrom = -1
        private var found = -1

        fun next(from: Int): Int {
            if (searchedFrom in 0..from && (found < 0 || found >= from)) return found
            searchedFrom = from
            found = s.indexOf(needle, from, ignoreCase)
            return found
        }
    }
}
