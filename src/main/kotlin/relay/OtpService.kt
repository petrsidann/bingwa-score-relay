package relay

import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class OtpRecord(
    val phone: String,
    val code: String,
    val createdAtMs: Long,
    val lastSentAtMs: Long,
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0L
)

sealed class OtpGenResult {
    data class Success(val code: String) : OtpGenResult()
    data class Cooldown(val remainingSec: Int) : OtpGenResult()
    data class Locked(val remainingSec: Int) : OtpGenResult()
}

sealed class OtpVerifyResult {
    object Success : OtpVerifyResult()
    data class InvalidCode(val attemptsLeft: Int) : OtpVerifyResult()
    object Expired : OtpVerifyResult()
    data class Locked(val remainingSec: Int) : OtpVerifyResult()
    object NotFound : OtpVerifyResult()
}

object OtpManager {
    private val records = ConcurrentHashMap<String, OtpRecord>()

    fun getRecord(phone: String): OtpRecord? = records[phone]

    fun generateOtp(phone: String, nowMs: Long = System.currentTimeMillis()): OtpGenResult {
        val existing = records[phone]
        if (existing != null) {
            if (nowMs < existing.lockedUntilMs) {
                val rem = ((existing.lockedUntilMs - nowMs) / 1000).toInt().coerceAtLeast(1)
                return OtpGenResult.Locked(rem)
            }
            val elapsedSec = (nowMs - existing.lastSentAtMs) / 1000
            if (elapsedSec < 60) {
                return OtpGenResult.Cooldown((60 - elapsedSec).toInt().coerceAtLeast(1))
            }
        }

        val code = "%06d".format((100000..999999).random())
        val newRecord = OtpRecord(
            phone = phone,
            code = code,
            createdAtMs = nowMs,
            lastSentAtMs = nowMs,
            failedAttempts = 0,
            lockedUntilMs = 0L
        )
        records[phone] = newRecord

        val smtpUrl = env("SMTP_URL")
        val maskedPhone = maskPhone(phone)
        val maskedCode = "${code.take(2)}****"
        println("[OTP] Generated OTP for $maskedPhone: $maskedCode")
        AdminState.addAudit("otp.generated", "Phone: $maskedPhone Code: $maskedCode")

        if (!smtpUrl.isNullOrBlank()) {
            SmtpService.sendOtpEmailAsync(smtpUrl, phone, code)
        }

        return OtpGenResult.Success(code)
    }

    fun verifyOtp(phone: String, code: String, nowMs: Long = System.currentTimeMillis()): OtpVerifyResult {
        val rec = records[phone] ?: return OtpVerifyResult.NotFound

        if (nowMs < rec.lockedUntilMs) {
            val rem = ((rec.lockedUntilMs - nowMs) / 1000).toInt().coerceAtLeast(1)
            return OtpVerifyResult.Locked(rem)
        }

        if (nowMs - rec.createdAtMs > 10 * 60 * 1000) {
            records.remove(phone)
            return OtpVerifyResult.Expired
        }

        if (rec.code == code) {
            records.remove(phone)
            return OtpVerifyResult.Success
        }

        val newFailCount = rec.failedAttempts + 1
        if (newFailCount >= 5) {
            val lockTime = nowMs + 15 * 60 * 1000
            records[phone] = rec.copy(failedAttempts = newFailCount, lockedUntilMs = lockTime)
            AdminState.addAudit("otp.lockout", "Phone ${maskPhone(phone)} locked for 15min after 5 failed attempts")
            return OtpVerifyResult.Locked(15 * 60)
        } else {
            records[phone] = rec.copy(failedAttempts = newFailCount)
            return OtpVerifyResult.InvalidCode(5 - newFailCount)
        }
    }

    fun getSandboxOtp(phone: String, nowMs: Long = System.currentTimeMillis()): String? {
        val smtpUrl = env("SMTP_URL")
        if (!smtpUrl.isNullOrBlank()) return null // sandbox only
        val rec = records[phone] ?: return null
        if (nowMs < rec.lockedUntilMs) return null
        if (nowMs - rec.createdAtMs > 10 * 60 * 1000) return null
        return rec.code
    }

    fun clear() {
        records.clear()
    }

    private fun maskPhone(phone: String): String {
        val p = phone.trim()
        if (p.length <= 6) return "***"
        return p.take(3) + "***" + p.takeLast(3)
    }
}

object SmtpService {
    fun sendOtpEmailAsync(smtpUrl: String, recipient: String, code: String) {
        Thread {
            try {
                sendOtpEmail(smtpUrl, recipient, code)
            } catch (e: Exception) {
                println("[SMTP Error] Failed to send OTP to $recipient: ${e.message}")
                AdminState.addAudit("smtp.error", "Recipient: $recipient Error: ${e.message}")
            }
        }.start()
    }

    fun sendOtpEmail(smtpUrl: String, recipient: String, code: String) {
        val uri = URI(smtpUrl)
        val scheme = uri.scheme ?: "smtp"
        val host = uri.host ?: "localhost"
        val port = if (uri.port != -1) uri.port else if (scheme == "smtps") 465 else 587

        val userInfo = uri.userInfo
        val user = userInfo?.substringBefore(":") ?: ""
        val pass = userInfo?.substringAfter(":", "") ?: ""

        val useSsl = scheme == "smtps" || port == 465

        var socket = if (useSsl) {
            SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
        } else {
            Socket(host, port)
        }

        var reader = socket.getInputStream().bufferedReader()
        var writer = socket.getOutputStream().bufferedWriter()

        fun readResp(): String {
            val line = reader.readLine() ?: ""
            return line
        }

        fun sendCmd(cmd: String): String {
            writer.write(cmd + "\r\n")
            writer.flush()
            return readResp()
        }

        readResp() // banner
        sendCmd("EHLO $host")

        if (!useSsl && port == 587) {
            val startTlsResp = sendCmd("STARTTLS")
            if (startTlsResp.startsWith("220")) {
                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
                sslSocket.startHandshake()
                socket = sslSocket
                reader = socket.getInputStream().bufferedReader()
                writer = socket.getOutputStream().bufferedWriter()
                sendCmd("EHLO $host")
            }
        }

        if (user.isNotBlank()) {
            sendCmd("AUTH LOGIN")
            val userB64 = Base64.getEncoder().encodeToString(user.toByteArray())
            sendCmd(userB64)
            val passB64 = Base64.getEncoder().encodeToString(pass.toByteArray())
            sendCmd(passB64)
        }

        val sender = user.ifBlank { "noreply@bingwa.app" }
        sendCmd("MAIL FROM:<$sender>")
        sendCmd("RCPT TO:<$recipient>")
        sendCmd("DATA")

        val body = "From: $sender\r\n" +
                "To: $recipient\r\n" +
                "Subject: Your Bingwa Verification Code\r\n\r\n" +
                "Your verification code is: $code\r\n" +
                "This code will expire in 10 minutes.\r\n" +
                ".\r\n"

        sendCmd(body)
        sendCmd("QUIT")
        try { socket.close() } catch (_: Exception) {}
    }
}
