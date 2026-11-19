package com.aura.aura_ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkCheckTest {

    private val installed = ApkFacts("com.aura.aura_ui.feature", 600L, setOf("abc"))
    private val good = installed.copy(versionCode = 612L)

    @Test fun `same app newer and same key is ok`() =
        assertEquals(ApkVerdict.Ok, checkUpdateApk(good, installed))

    @Test fun `unparseable file is unreadable`() =
        assertEquals(ApkVerdict.Unreadable, checkUpdateApk(null, installed))

    @Test fun `another package is the wrong app`() =
        assertEquals(ApkVerdict.WrongApp, checkUpdateApk(good.copy(packageName = "com.evil"), installed))

    @Test fun `same version is not newer`() =
        assertEquals(ApkVerdict.NotNewer, checkUpdateApk(good.copy(versionCode = 600L), installed))

    @Test fun `older version is not newer`() =
        assertEquals(ApkVerdict.NotNewer, checkUpdateApk(good.copy(versionCode = 599L), installed))

    @Test fun `different key is the wrong signer`() =
        assertEquals(ApkVerdict.WrongSigner, checkUpdateApk(good.copy(signers = setOf("xyz")), installed))

    @Test fun `an extra key is the wrong signer`() =
        assertEquals(ApkVerdict.WrongSigner, checkUpdateApk(good.copy(signers = setOf("abc", "xyz")), installed))
}
