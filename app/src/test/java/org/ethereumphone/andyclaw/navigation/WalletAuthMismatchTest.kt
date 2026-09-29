package org.ethereumphone.andyclaw.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAuthMismatchTest {
  private val os = "0xAbCdEf0000000000000000000000000000000001"

  @Test
  fun `unknown OS address fails open`() {
    assertFalse(walletAuthMismatch("0xdead", null))
    assertFalse(walletAuthMismatch("0xdead", ""))
    assertFalse(walletAuthMismatch("", "error"))
  }

  @Test
  fun `same address in another case is not a mismatch`() {
    assertFalse(walletAuthMismatch(os.lowercase(), os))
  }

  @Test
  fun `a different or missing stored address is`() {
    assertTrue(walletAuthMismatch("0x0000000000000000000000000000000000000002", os))
    assertTrue(walletAuthMismatch("", os))
  }
}
