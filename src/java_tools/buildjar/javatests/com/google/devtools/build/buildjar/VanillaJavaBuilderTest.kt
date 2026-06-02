// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar

import com.google.common.base.StandardSystemProperty
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.io.ByteStreams
import com.google.common.truth.Subject
import com.google.devtools.build.buildjar.VanillaJavaBuilder.VanillaJavaBuilderResult
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarEntry

/** [VanillaJavaBuilder]Test  */
@RunWith(JUnit4::class)
class VanillaJavaBuilderTest {
    @Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Throws(Exception::class)
    fun run(args: MutableList<String?>?): VanillaJavaBuilderResult {
        VanillaJavaBuilder().use { builder ->
            return builder.run(args)
        }
    }

    @Throws(IOException::class)
    fun readJar(file: File): ImmutableMap<String?, ByteArray?> {
        val result = ImmutableMap.builder<String?, ByteArray?>()
        JarFile(file).use { jf ->
            val entries: Enumeration<JarEntry> = jf.entries()
            while (entries.hasMoreElements()) {
                val je: JarEntry = entries.nextElement()
                result.put(je.getName(), ByteStreams.toByteArray(jf.getInputStream(je)))
            }
        }
        return result.buildOrThrow()
    }

    @Test
    @Throws(Exception::class)
    fun hello() {
        val source = temporaryFolder.newFile("Test.java").toPath()
        val output = temporaryFolder.newFile("out.jar").toPath()
        Files.write(
            source,
            ImmutableList.of<String?>(
                "class A {",  //
                "}"
            ),
            StandardCharsets.UTF_8
        )
        val sourceJar = temporaryFolder.newFile("src.srcjar").toPath()
        Files.newOutputStream(sourceJar).use { os ->
            JarOutputStream(os).use { jos ->
                jos.putNextEntry(JarEntry("B.java"))
                jos.write("class B {}".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val result: VanillaJavaBuilderResult =
            run(
                ImmutableList.of<String?>(
                    "--javacopts",
                    "-Xep:FallThrough:ERROR",
                    "--",
                    "--sources",
                    source.toString(),
                    "--source_jars",
                    sourceJar.toString(),
                    "--output",
                    output.toString(),
                    "--bootclasspath",
                    Paths.get(StandardSystemProperty.JAVA_HOME.value()).resolve("lib/rt.jar").toString()
                )
            )

        assertThat(result.output()).isEmpty()
        assertThat(result.ok()).isTrue()

        val outputEntries = readJar(output.toFile())
        Truth.assertThat(outputEntries.keys)
            .containsExactly("META-INF/", "META-INF/MANIFEST.MF", "A.class", "B.class")
    }

    @Test
    @Throws(Exception::class)
    fun error() {
        val source = temporaryFolder.newFile("Test.java").toPath()
        val output = temporaryFolder.newFolder().toPath().resolve("out.jar")
        Files.write(
            source,
            ImmutableList.of<String?>(
                "class A {",  //
                "  void f(int x) {",
                "    switch (x) {",
                "      case 0:",
                "        System.err.println(0);",
                "      case 1:",
                "        System.err.println(0);",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        )

        val result: VanillaJavaBuilderResult =
            run(
                ImmutableList.of<String?>(
                    "--javacopts",
                    "-Xlint:all",
                    "-Werror",
                    "--",
                    "--sources",
                    source.toString(),
                    "--output",
                    output.toString(),
                    "--bootclasspath",
                    Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString()
                )
            )

        Subject.contains("possible fall-through")
        assertThat(result.ok()).isFalse()
        Truth.assertThat(Files.exists(output)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun diagnosticWithoutSource() {
        val source = temporaryFolder.newFile("Test.java").toPath()
        val output = temporaryFolder.newFolder().toPath().resolve("out.jar")
        Files.write(
            source,
            ImmutableList.of<String?>(
                "import java.util.ArrayList;",
                "import java.util.List;",
                "abstract class A {",
                "  void test() {",
                "      f(0L);",
                "   }",
                "   void f(int i) {}",
                "}"
            ),
            StandardCharsets.UTF_8
        )

        val result: VanillaJavaBuilderResult =
            run(
                ImmutableList.of<String?>(
                    "--javacopts",
                    "-Xlint:none",
                    "--",
                    "--sources",
                    source.toString(),
                    "--output",
                    output.toString()
                )
            )

        Subject.contains("note: Some messages have been simplified")
        assertThat(result.ok()).isFalse()
        Truth.assertThat(Files.exists(output)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun cleanOutputDirectories() {
        val source = temporaryFolder.newFile("Test.java").toPath()
        val output = temporaryFolder.newFile("out.jar").toPath()
        Files.write(
            source,
            ImmutableList.of<String?>(
                "class A {",  //
                "}"
            ),
            StandardCharsets.UTF_8
        )
        val sourceJar = temporaryFolder.newFile("src.srcjar").toPath()
        Files.newOutputStream(sourceJar).use { os ->
            JarOutputStream(os).use { jos ->
                jos.putNextEntry(JarEntry("B.java"))
                jos.write("class B {}".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val classDir = temporaryFolder.newFolder().toPath()
        Files.write(
            classDir.resolve("extra.class"),
            byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())
        )

        val result: VanillaJavaBuilderResult =
            run(
                ImmutableList.of<String?>(
                    "--javacopts",
                    "-Xep:FallThrough:ERROR",
                    "--",
                    "--sources",
                    source.toString(),
                    "--source_jars",
                    sourceJar.toString(),
                    "--output",
                    output.toString(),
                    "--bootclasspath",
                    Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString()
                )
            )

        assertThat(result.output()).isEmpty()
        assertThat(result.ok()).isTrue()

        val outputEntries = readJar(output.toFile())
        Truth.assertThat(outputEntries.keys)
            .containsExactly("META-INF/", "META-INF/MANIFEST.MF", "A.class", "B.class")
    }

    // suppress unpopular deferred diagnostic notes for sunapi, deprecation, and unchecked
    @Test
    @Throws(Exception::class)
    fun testDeferredDiagnostics() {
        val b = temporaryFolder.newFile("B.java").toPath()
        val a = temporaryFolder.newFile("A.java").toPath()
        val output = temporaryFolder.newFile("out.jar").toPath()
        Files.write(
            b,
            ImmutableList.of<String?>(
                "@Deprecated",  //
                "class B {}"
            ),
            StandardCharsets.UTF_8
        )
        Files.write(
            a,
            ImmutableList.of<String?>(
                "import java.util.*;",  //
                "public class A {",
                "  sun.misc.Unsafe theUnsafe;",
                "  B b;",
                "  List l = new ArrayList<>();",
                "}"
            ),
            StandardCharsets.UTF_8
        )

        val result: VanillaJavaBuilderResult =
            run(
                ImmutableList.of<String?>(
                    "--sources",
                    a.toString(),
                    b.toString(),
                    "--output",
                    output.toString(),
                    "--bootclasspath",
                    Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString()
                )
            )

        assertThat(result.output()).isEmpty()
        assertThat(result.ok()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun nativeHeaders() {
        val foo = temporaryFolder.newFile("FooWithNativeMethod.java").toPath()
        val bar = temporaryFolder.newFile("BarWithNativeMethod.java").toPath()
        val output = temporaryFolder.newFile("out.jar").toPath()
        val nativeHeaderOutput = temporaryFolder.newFile("out-native-headers.jar").toPath()
        Files.write(
            foo,
            ImmutableList.of<String?>(
                "package test;",
                "public class FooWithNativeMethod {",
                "  public static native byte[] g(String s);",
                "}"
            ),
            StandardCharsets.UTF_8
        )
        Files.write(
            bar,
            ImmutableList.of<String?>(
                "package test;",
                "public class BarWithNativeMethod {",
                "  public static native byte[] g(String s);",
                "}"
            ),
            StandardCharsets.UTF_8
        )

        val result: VanillaJavaBuilderResult =
            run(
                ImmutableList.of<String?>(
                    "--javacopts",
                    "-Xep:FallThrough:ERROR",
                    "--",
                    "--sources",
                    foo.toString(),
                    bar.toString(),
                    "--output",
                    output.toString(),
                    "--native_header_output",
                    nativeHeaderOutput.toString(),
                    "--bootclasspath",
                    Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString()
                )
            )

        assertThat(result.output()).isEmpty()
        assertThat(result.ok()).isTrue()

        val outputEntries = readJar(nativeHeaderOutput.toFile())
        Truth.assertThat(outputEntries.keys)
            .containsAtLeast("test_BarWithNativeMethod.h", "test_FooWithNativeMethod.h")
    }
}
