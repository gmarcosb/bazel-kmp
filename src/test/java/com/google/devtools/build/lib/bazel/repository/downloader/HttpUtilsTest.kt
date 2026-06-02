// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.downloader.HttpUtils.getExtension
import com.google.devtools.build.lib.bazel.repository.downloader.HttpUtils.getLocation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Unit tests for [HttpUtils].  */
@RunWith(JUnit4::class)
class HttpUtilsTest {
    @Rule
    val thrown: ExpectedException = ExpectedException.none()

    private val connection: HttpURLConnection = Mockito.mock<HttpURLConnection>(HttpURLConnection::class.java)

    @Test
    @Throws(Exception::class)
    fun getExtension_twoExtensions_returnsLast() {
        Truth.assertThat(getExtension("doodle.tar.gz")).isEqualTo("gz")
    }

    @Test
    @Throws(Exception::class)
    fun getExtension_isUppercase_returnsLowered() {
        Truth.assertThat(getExtension("DOODLE.TXT")).isEqualTo("txt")
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_missingInRedirect_throwsIOException() {
        thrown.expect(IOException::class.java)
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example").toURL())
        getLocation(connection)
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_absoluteInRedirect_returnsNewUrl() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("http://new.example/hi")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("http://new.example/hi"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_redirectOnlyHasPath_mergesHostFromOriginalUrl() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("/hi")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("http://lol.example/hi"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_onlyHasPathWithoutSlash_failsToMerge() {
        thrown.expect(IOException::class.java)
        thrown.expectMessage("Could not merge")
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("omg")
        getLocation(connection)
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_hasFragment_prefersNewFragment() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example#a").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("http://new.example/hi#b")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("http://new.example/hi#b"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_hasNoFragmentButOriginalDoes_mergesOldFragment() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example#a").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("http://new.example/hi")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("http://new.example/hi#a"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_oldUrlHasPassRedirectingToSameDomain_mergesPassword() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://a:b@lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("http://lol.example/hi")
        Truth.assertThat<URI>(getLocation(connection))
            .isEqualTo(URI.create("http://a:b@lol.example/hi"))
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://a:b@lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("/hi")
        Truth.assertThat<URI>(getLocation(connection))
            .isEqualTo(URI.create("http://a:b@lol.example/hi"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_oldUrlHasPasswordRedirectingToNewServer_doesntMerge() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://a:b@lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("http://new.example/hi")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("http://new.example/hi"))
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://a:b@lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("http://lol.example:81/hi")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("http://lol.example:81/hi"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_redirectToFtp_throwsIOException() {
        thrown.expect(IOException::class.java)
        thrown.expectMessage("Bad Location")
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("ftp://lol.example")
        getLocation(connection)
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_redirectToHttps_works() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://lol.example").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn("https://lol.example")
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create("https://lol.example"))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_preservesQuotingIfNotInheriting() {
        val redirect =
            ("http://redirected.example.org/foo?"
                    + "response-content-disposition=attachment%3Bfilename%3D%22bar.tar.gz%22")
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://original.example.org").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn(redirect)
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create(redirect))
    }

    @Test
    @Throws(Exception::class)
    fun getLocation_preservesQuotingWithUserIfNotInheriting() {
        val redirect =
            ("http://redirected.example.org/foo?"
                    + "response-content-disposition=attachment%3Bfilename%3D%22bar.tar.gz%22")
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://a:b@original.example.org").toURL())
        Mockito.`when`<String?>(connection.getHeaderField("Location")).thenReturn(redirect)
        Truth.assertThat<URI>(getLocation(connection)).isEqualTo(URI.create(redirect))
    }
}
