package com.aura.aura_ui.remote

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What an update check needs to know about an APK: its identity, version and signing keys. */
data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** The outcome of [checkUpdateApk]. Only [Ok] may be installed. */
sealed interface ApkVerdict {
    data object Ok : ApkVerdict
    data object Unreadable : ApkVerdict
    data object WrongApp : ApkVerdict
    data object NotNewer : ApkVerdict
    data object WrongSigner : ApkVerdict
}

/**
 * Decides whether a downloaded APK may replace the running app. Pure; covered by `ApkCheckTest`.
 *
 * - Contract: [ApkVerdict.Ok] only for the same package, a higher version code, and exactly the
 *   same signing certificates as [installed].
 * - Why equal sets rather than any overlap: AURA has one release key and never rotates it, so any
 *   difference means the file wasn't built by the owner.
 */
fun checkUpdateApk(candidate: ApkFacts?, installed: ApkFacts): ApkVerdict = when {
    candidate == null -> ApkVerdict.Unreadable
    candidate.packageName != installed.packageName -> ApkVerdict.WrongApp
    candidate.versionCode <= installed.versionCode -> ApkVerdict.NotNewer
    candidate.signers != installed.signers -> ApkVerdict.WrongSigner
    else -> ApkVerdict.Ok
}

/**
 * Reads [ApkFacts] from an APK file on disk.
 *
 * - Fails: returns null when Android can't parse the file (truncated, not an APK).
 */
fun readApkFacts(context: Context, file: File): ApkFacts? {
    val info = context.packageManager.getPackageArchiveInfo(file.path, signingFlags()) ?: return null
    return info.toFacts()
}

/** [ApkFacts] for the app that is running now. */
fun installedApkFacts(context: Context): ApkFacts? =
    runCatching { context.packageManager.getPackageInfo(context.packageName, signingFlags()) }
        .getOrNull()
        ?.toFacts()

@Suppress("DEPRECATION")
private fun signingFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun PackageInfo.toFacts(): ApkFacts? {
    val certs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = signingInfo ?: return null
        if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
    } else {
        signatures
    } ?: return null
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
    return ApkFacts(packageName, code, certs.map { sha256Hex(it.toByteArray()) }.toSet())
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
