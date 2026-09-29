package org.ethereumphone.andyclaw

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePrefsRecoveryTest {

  /** Same simple name as Tink's shaded protobuf exception, which is what the check keys on. */
  private class InvalidProtocolBufferException(msg: String) : IOException(msg)

  @Test
  fun `a freshly minted master key makes any crypto failure final`() {
    assertTrue(SecurePrefs.isUnreadableStoreFailure(KeyStoreException("x"), masterKeyExisted = false))
    assertTrue(SecurePrefs.isUnreadableStoreFailure(IOException("x"), masterKeyExisted = false))
  }

  @Test
  fun `with the master key present only keyset-level failures count`() {
    assertTrue(SecurePrefs.isUnreadableStoreFailure(AEADBadTagException(), masterKeyExisted = true))
    assertTrue(
      SecurePrefs.isUnreadableStoreFailure(InvalidProtocolBufferException("invalid tag (zero)"), masterKeyExisted = true)
    )
    // Wrapped, as Tink and keystore2 often do.
    assertTrue(
      SecurePrefs.isUnreadableStoreFailure(
        GeneralSecurityException("decrypt", AEADBadTagException()),
        masterKeyExisted = null,
      )
    )
  }

  @Test
  fun `a transient keystore failure never moves the store aside`() {
    assertFalse(SecurePrefs.isUnreadableStoreFailure(KeyStoreException("busy"), masterKeyExisted = true))
    assertFalse(SecurePrefs.isUnreadableStoreFailure(KeyStoreException("busy"), masterKeyExisted = null))
    assertFalse(
      SecurePrefs.isUnreadableStoreFailure(ProviderException("keystore2", KeyStoreException("busy")), true)
    )
  }

  @Test
  fun `failures that are not crypto or IO never count`() {
    assertFalse(SecurePrefs.isUnreadableStoreFailure(IllegalStateException("locked"), masterKeyExisted = false))
    assertFalse(SecurePrefs.isUnreadableStoreFailure(RuntimeException("x"), masterKeyExisted = false))
  }

  @Test
  fun `moving aside renames and never deletes`() {
    val root = Files.createTempDirectory("secureprefs").toFile()
    try {
      val sharedPrefs = File(root, "shared_prefs").apply { mkdirs() }
      File(sharedPrefs, "openclaw.node.secure.xml").writeText("store")
      File(sharedPrefs, "openclaw.node.secure.xml.bak").writeText("bak")
      File(sharedPrefs, "other.xml").writeText("untouched")
      val quarantine = File(root, "no_backup/unreadable_prefs")

      val moved = SecurePrefs.moveStoreAside(sharedPrefs, "openclaw.node.secure", quarantine, stamp = 42L)

      assertEquals(2, moved.size)
      assertFalse(File(sharedPrefs, "openclaw.node.secure.xml").exists())
      assertFalse(File(sharedPrefs, "openclaw.node.secure.xml.bak").exists())
      assertEquals("store", File(quarantine, "openclaw.node.secure.xml.unreadable-42").readText())
      assertEquals("bak", File(quarantine, "openclaw.node.secure.xml.bak.unreadable-42").readText())
      assertEquals("untouched", File(sharedPrefs, "other.xml").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `import keeps security and identity keys on the device`() {
    val backup = mapOf<String, Any?>(
      "node.instanceId" to "other-device",
      "auth.walletAddress" to "0xother",
      "auth.walletSignature" to "0xsig",
      "agent.provenanceEnforcement" to false,
      "agent.yoloMode" to true,
      "agent.safetyEnabled" to false,
      "agent.autopilot.noConfirm" to true,
      "telegram.botToken" to "123:abc",
      "telegram.ownerChatId" to 99L,
      "telegram.botEnabled" to true,
      "ai.name" to "Andy",
      "anthropic.model" to "anthropic/claude-sonnet-5",
    )
    val imported = SecurePrefs.importableValues(backup)
    assertEquals(setOf("ai.name", "anthropic.model"), imported.keys)
  }
}
