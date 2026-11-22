package com.pare.app
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
class AuthTest {
 @Test fun confirmationResponseDoesNotInventSession() {assertNull(parseSession(wire.parseToJsonElement("""{"user":{"id":"a","email":"test@example.com"}}""").jsonObject,100))}
 @Test fun expiryUsesServerLifetimeAndOwnerComesFromAuthUser() {
  val response=wire.parseToJsonElement("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"a","email":"test@example.com"}}""").jsonObject
  val session=parseSession(response,100)!!
  assertEquals(3700,session.expiresAt);assertEquals("a",session.owner);assertEquals("refresh",session.refreshToken)
 }
 @Test fun missingRefreshTokenRejectsIncompleteSession() {assertThrows(IllegalArgumentException::class.java){parseSession(wire.parseToJsonElement("""{"access_token":"access","user":{"id":"a"}}""").jsonObject,0)}}
}
