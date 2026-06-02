// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.authandtls.credentialhelper

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Policy
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions
import com.google.devtools.build.lib.testutil.ManualClock
import com.google.devtools.common.options.Options
import org.junit.Test
import java.net.URI
import java.time.Duration

/** Tests for [CredentialModule].  */
@RunWith(JUnit4::class)
class CredentialModuleTest {
    private val clock = ManualClock()
    private val module: CredentialModule = CredentialModule(clock)
    private val cache: Cache<URI?, GetCredentialsResponse?> = module.getCredentialCache()

    @Test
    fun putWithExplicitExpiration() {
        initModule("build", Duration.ofMinutes(60))

        val expiry: Instant? = clock.now()!!.plus(Duration.ofMinutes(123))

        cache.put(TEST_URI, GetCredentialsResponse.newBuilder().setExpires(expiry).build())
        assertInCache(TEST_URI, expiry)

        clock.advance(Duration.ofMinutes(122))
        assertInCache(TEST_URI, expiry)

        clock.advance(Duration.ofMinutes(2))
        assertNotInCache(TEST_URI)
    }

    @Test
    fun putWithNonzeroDefaultExpiration() {
        initModule("build", Duration.ofMinutes(60))

        val expiry: Instant? = clock.now()!!.plus(Duration.ofMinutes(60))

        cache.put(TEST_URI, DEFAULT_RESPONSE)
        assertInCache(TEST_URI, expiry)

        clock.advance(Duration.ofMinutes(59))
        assertInCache(TEST_URI, expiry)

        clock.advance(Duration.ofMinutes(2))
        assertNotInCache(TEST_URI)
    }

    @Test
    fun putWithZeroDefaultExpiration() {
        initModule("build", Duration.ZERO)

        cache.put(TEST_URI, DEFAULT_RESPONSE)

        assertNotInCache(TEST_URI)
    }

    @Test
    fun keepingDefaultDoesNotClearCache() {
        initModule("build", Duration.ofMinutes(60))

        cache.put(TEST_URI, DEFAULT_RESPONSE)
        assertInCache(TEST_URI, clock.now()!!.plus(Duration.ofMinutes(60)))

        initModule("build", Duration.ofMinutes(60))

        assertInCache(TEST_URI, clock.now()!!.plus(Duration.ofMinutes(60)))
    }

    @Test
    fun changingDefaultToSmallerValueClearsCache() {
        initModule("build", Duration.ofMinutes(60))

        cache.put(TEST_URI, DEFAULT_RESPONSE)
        assertInCache(TEST_URI, clock.now()!!.plus(Duration.ofMinutes(60)))

        initModule("build", Duration.ofMinutes(30))

        assertNotInCache(TEST_URI)
    }

    @Test
    fun changingDefaultToLargerValueClearsCache() {
        initModule("build", Duration.ofMinutes(30))

        cache.put(TEST_URI, DEFAULT_RESPONSE)
        assertInCache(TEST_URI, clock.now()!!.plus(Duration.ofMinutes(30)))

        initModule("build", Duration.ofMinutes(60))

        assertNotInCache(TEST_URI)
    }

    @Test
    fun cleanCommandClearsCache() {
        initModule("build", Duration.ofMinutes(60))

        cache.put(TEST_URI, DEFAULT_RESPONSE)
        assertInCache(TEST_URI, clock.now()!!.plus(Duration.ofMinutes(60)))

        initModule("clean", Duration.ofMinutes(60))

        assertNotInCache(TEST_URI)
    }

    private fun initModule(commandName: String?, cacheTimeout: Duration?) {
        module.beforeCommand(createCommandEnvironment(commandName, cacheTimeout))
    }

    private fun assertInCache(uri: URI?, expiry: Instant?) {
        val entry: Policy.CacheEntry<URI?, GetCredentialsResponse?>? = cache.policy().getEntryIfPresentQuietly(uri)
        Truth.assertThat(entry).isNotNull()
        Truth.assertThat<Instant?>(fromEpochNano(entry!!.expiresAt())).isEqualTo(expiry)
    }

    private fun assertNotInCache(uri: URI?) {
        Truth.assertThat(cache.policy().getEntryIfPresentQuietly(uri)).isNull()
    }

    companion object {
        private val TEST_URI: URI = URI.create("https://example.com")
        private val DEFAULT_RESPONSE: GetCredentialsResponse? = GetCredentialsResponse.newBuilder().build()

        private fun createCommandEnvironment(
            commandName: String?, cacheTimeout: Duration?
        ): CommandEnvironment {
            val authAndTlsOptions: AuthAndTLSOptions = Options.getDefaults<O>(AuthAndTLSOptions::class.java)
            authAndTlsOptions.setCredentialHelperCacheTimeout(cacheTimeout)

            val optionsParsingResult: OptionsParsingResult =
                Mockito.mock<OptionsParsingResult>(OptionsParsingResult::class.java)
            Mockito.`when`<T?>(optionsParsingResult.getOptions<O?>(AuthAndTLSOptions::class.java))
                .thenReturn(authAndTlsOptions)

            val env: CommandEnvironment = Mockito.mock<CommandEnvironment>(CommandEnvironment::class.java)
            Mockito.`when`<T?>(env.getCommandName()).thenReturn(commandName)
            Mockito.`when`<T?>(env.getOptions()).thenReturn(optionsParsingResult)

            return env
        }

        private fun fromEpochNano(nano: Long): Instant? {
            return Instant.ofEpochSecond(0, nano)
        }
    }
}
