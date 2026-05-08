package com.luckytap.app

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.util.Log
import java.nio.charset.Charset

/**
 * Stateless helper that encapsulates low-level NFC read/write operations,
 * keeping the Activity focused on lifecycle and UI concerns.
 */
object NfcHelper {

    private const val TAG = "NfcHelper"

    fun writeToTag(tag: Tag, dataToWrite: String): NfcWriteResult {
        val textRecord = NdefRecord.createTextRecord("en", dataToWrite)
        val ndefMessage = NdefMessage(arrayOf(textRecord))

        return try {
            val ndef = Ndef.get(tag)
                ?: return NfcWriteResult(success = false, error = "Tag is not NDEF formatted.")

            ndef.connect()
            ndef.use {
                if (it.maxSize < ndefMessage.toByteArray().size) {
                    NfcWriteResult(success = false, error = "Tag too small.")
                } else {
                    it.writeNdefMessage(ndefMessage)
                    NfcWriteResult(success = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to NFC", e)
            NfcWriteResult(success = false, error = e.message ?: "Exception during write.")
        }
    }

    fun readFromIntent(intent: Intent): NfcScanResult {
        val rawMessages: Array<NdefMessage>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.map { it as NdefMessage }?.toTypedArray()
        }

        if (rawMessages.isNullOrEmpty()) {
            return NfcScanResult(success = false, error = "No NDEF messages found on tag.")
        }

        return try {
            val record = rawMessages[0].records[0]
            val payload = record.payload
            val textEncoding = if ((payload[0].toInt() and 128) == 0) "UTF-8" else "UTF-16"
            val languageCodeLength = payload[0].toInt() and 63
            val text = String(
                payload,
                languageCodeLength + 1,
                payload.size - languageCodeLength - 1,
                Charset.forName(textEncoding),
            )
            NfcScanResult(success = true, data = text)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NDEF data", e)
            NfcScanResult(success = false, error = "Malformed NDEF Data: ${e.message}")
        }
    }
}

data class NfcWriteResult(val success: Boolean, val error: String? = null)
