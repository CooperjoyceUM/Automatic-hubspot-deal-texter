package com.utemaster.dealtexter.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.utemaster.dealtexter.core.SmsGateway
import com.utemaster.dealtexter.core.SmsResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Sends from the phone's own SIM (the default SMS SIM on dual-SIM phones) and waits for the
 * radio to confirm every part. Because the app isn't the default SMS app, Android itself
 * copies the sent message into Samsung Messages' "Sent" history.
 */
class AndroidSmsGateway(private val context: Context) : SmsGateway {

    override suspend fun send(to: String, text: String): SmsResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsResult.Failed("SMS permission not granted – open the app and allow it")
        }
        val sms = try {
            smsManager()
        } catch (e: Exception) {
            return SmsResult.Failed("No SIM available for SMS (${e.javaClass.simpleName})")
        }

        val parts = sms.divideMessage(text)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sentIntents = ArrayList(
                parts.indices.map { i ->
                    PendingIntent.getBroadcast(
                        context,
                        i,
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                    )
                },
            )
            try {
                if (parts.size == 1) {
                    sms.sendTextMessage(to, null, parts[0], sentIntents[0], null)
                } else {
                    sms.sendMultipartTextMessage(to, null, parts, sentIntents, null)
                }
            } catch (e: Exception) {
                // Thrown before handing anything to the radio, so nothing was sent.
                return SmsResult.Failed("Couldn't send (${e.javaClass.simpleName}: ${e.message})")
            }

            val codes = try {
                withTimeout(CONFIRM_TIMEOUT_MS) { List(parts.size) { results.receive() } }
            } catch (e: TimeoutCancellationException) {
                return SmsResult.Unknown("The phone didn't confirm the text within ${CONFIRM_TIMEOUT_MS / 1000} seconds")
            }
            val failed = codes.firstOrNull { it != Activity.RESULT_OK }
            return when {
                failed == null -> SmsResult.Sent
                // Some parts went and some didn't: retrying would resend the parts that went.
                codes.any { it == Activity.RESULT_OK } -> SmsResult.Unknown("Only part of the text was sent (${describe(failed)})")
                else -> SmsResult.Failed(describe(failed))
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            results.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager {
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val valid = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (valid) base.createForSubscriptionId(subId) else base
        } else {
            if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun describe(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "phone radio is off (flight mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message could not be encoded"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS limit exceeded"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short code not allowed"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short code never allowed"
        else -> "error code $code"
    }

    companion object {
        private const val CONFIRM_TIMEOUT_MS = 120_000L
    }
}
