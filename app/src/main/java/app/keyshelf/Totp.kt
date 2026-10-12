package app.keyshelf

import java.net.URI
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

object Totp {
    fun code(value: String, seconds: Long = System.currentTimeMillis() / 1000): String {
        var secret = value.trim()
        var algorithm = "SHA1"
        var digits = 6
        var period = 30
        if (secret.startsWith("otpauth://")) {
            val uri = URI(secret)
            require(uri.host == "totp") { "Поддерживается только TOTP" }
            val params = uri.rawQuery.orEmpty().split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            secret = params["secret"].orEmpty()
            algorithm = params["algorithm"]?.uppercase() ?: "SHA1"
            digits = params["digits"]?.toInt() ?: 6
            period = params["period"]?.toInt() ?: 30
        }
        require(algorithm in listOf("SHA1", "SHA256", "SHA512") && digits in 6..8 && period in 1..300)
        val text = secret.uppercase().replace(" ", "").trimEnd('=')
        require(text.isNotEmpty() && text.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }) { "Неверный секрет 2FA" }
        var buffer = 0
        var bits = 0
        val bytes = mutableListOf<Byte>()
        text.forEach { c ->
            buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            bits += 5
            if (bits >= 8) { bits -= 8; bytes.add((buffer shr bits).toByte()); buffer = buffer and ((1 shl bits) - 1) }
        }
        require(bytes.isNotEmpty())
        val mac = Mac.getInstance("Hmac$algorithm")
        mac.init(SecretKeySpec(bytes.toByteArray(), "Hmac$algorithm"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / period).array())
        val offset = digest.last().toInt() and 15
        val number = ByteBuffer.wrap(digest, offset, 4).int and 0x7fffffff
        val modulus = Math.pow(10.0, digits.toDouble()).toInt()
        return (number % modulus).toString().padStart(digits, '0')
    }
}
