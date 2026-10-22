package com.mas.strictguard

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Persetujuan teman (versi lokal): teman mengetik sebuah kode di HP ini, dan hanya hash-nya yang disimpan.
 * Bila kode diset, keluar dari Mode Ketat juga membutuhkan kode itu. Teman HARUS ada di dekatmu.
 * Persetujuan jarak jauh (teman menyetujui dari HP-nya) belum ada: lihat [RemoteApprovalChannel].
 */
object FriendApproval {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    private fun hash(salt: String, code: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + ":" + code).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    fun isSet(c: Context): Boolean = sp(c).getString("friend_hash", null) != null

    /** Sisa menit penguncian setelah terlalu banyak salah, 0 bila tidak terkunci. */
    fun lockedMinutes(c: Context): Long {
        if (!SafeTimer.isRunning(c, "friendlock")) return 0L
        val left = 10L * 60000L - SafeTimer.elapsed(c, "friendlock")
        if (left <= 0L) {
            SafeTimer.cancel(c, "friendlock")
            return 0L
        }
        return left / 60000L + 1L
    }

    fun verify(c: Context, input: String): Boolean {
        val h = sp(c).getString("friend_hash", null) ?: return true
        val salt = sp(c).getString("friend_salt", "") ?: ""
        if (lockedMinutes(c) > 0L) return false
        val ok = hash(salt, input.trim()) == h
        val p = sp(c)
        if (ok) {
            p.edit().putInt("friend_fails", 0).commit()
        } else {
            val f = p.getInt("friend_fails", 0) + 1
            if (f >= 3) {
                p.edit().putInt("friend_fails", 0).commit()
                SafeTimer.start(c, "friendlock")
                Store.log(c, "Kode teman salah 3 kali: dikunci 10 menit")
            } else {
                p.edit().putInt("friend_fails", f).commit()
            }
        }
        return ok
    }

    /** Mengatur kode baru. Bila sudah ada kode, kode lama wajib benar. Mengembalikan pesan galat atau null bila sukses. */
    fun set(c: Context, oldCode: String, newCode: String): String? {
        val code = newCode.trim()
        if (code.length < 6) return "Kode minimal 6 karakter"
        if (isSet(c) && !verify(c, oldCode)) return "Kode lama salah"
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val salt = bytes.joinToString("") { String.format("%02x", it) }
        sp(c).edit().putString("friend_salt", salt).putString("friend_hash", hash(salt, code)).commit()
        Store.log(c, "Kode teman diatur")
        return null
    }

    fun clear(c: Context, code: String): String? {
        if (!isSet(c)) return null
        if (!verify(c, code)) return "Kode salah"
        sp(c).edit().remove("friend_hash").remove("friend_salt").commit()
        Store.log(c, "Kode teman dihapus")
        return null
    }
}

/** PLACEHOLDER: kanal persetujuan jarak jauh (mis. teman menyetujui lewat pesan). Belum ada implementasi. */
interface RemoteApprovalChannel {
    val name: String
    fun requestApproval(reason: String, onResult: (Boolean) -> Unit)
}
