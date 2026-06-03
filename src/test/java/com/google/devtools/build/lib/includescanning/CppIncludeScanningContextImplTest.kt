// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning

import com.google.common.collect.*
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.actions.util.DummyExecutor
import org.junit.Test
import java.util.function.Function
import java.util.function.Supplier

@RunWith(JUnit4::class)
class CppIncludeScanningContextImplTest : BuildViewTestCase() {
    @Before
    @Throws(IOException::class)
    fun setupCppSupport() {
        analysisMock
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.Companion.builder()
                    .withFeatures(MockCcSupport.Companion.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC)
            )
    }

    @Test
    @Throws(Exception::class)
    fun treeArtifactHeader_scansExpandedArtifact() {
        writeTreeRuleBzl(scratch.file("foo/def.bzl"))
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":def.bzl", "tree")

        package(features = [
            "cc_include_scanning",
            "header_modules",
            "use_header_modules",
        ])

        tree(name = "headers")

        cc_library(
            name = "foo",
            hdrs = [":headers"],
        )
        
        """.trimIndent()
        )
        val includeScanner: IncludeScanner?
        IncludeScanner > Mockito.mock<IncludeScanner?>(IncludeScanner::class.java)
        val includeScanningContext: CppIncludeScanningContextImpl =
            createIncludeScanningContext(includeScanner)
        val action: CppCompileAction = getCppCompileAction("//foo")
        val headerTree: SpecialArtifact = getArtifact("//foo:headers") as SpecialArtifact
        val headerTreeFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            TreeFileArtifact.createTreeOutput(headerTree, "file1.h")
        val environment: Environment = environmentWithTreeValue(headerTree, headerTreeFile)
        val actionExecutionContext: ActionExecutionContext = createActionExecutionContext(environment)

        val result: MutableList<Artifact?>? =
            includeScanningContext.findAdditionalInputs(
                action, actionExecutionContext, EMPTY_HEADER_DATA
            )

        Truth.assertThat(result).isNotNull()
        val collector: ArgumentCaptor<MutableCollection<Artifact?>?>
        Collection > createCaptor<Any?, MutableCollection<*>?>(MutableCollection::class.java)
        TODO(
            """
            |Cannot convert element
            |With text:
            |Object>verify(includeScanner)
            |        .processAsync(<T>any(), collector.capture()
            """.trimMargin()
        )
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()

        Truth.assertThat(collector.getValue()).containsExactly(headerTreeFile)
    }

    @Test
    @Throws(Exception::class)
    fun treeArtifactAndRegularHeader_scansRegularAndExpandedArtifact() {
        writeTreeRuleBzl(scratch.file("foo/def.bzl"))
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":def.bzl", "tree")

        package(features = [
            "cc_include_scanning",
            "header_modules",
            "use_header_modules",
        ])

        tree(name = "headers")

        cc_library(
            name = "foo",
            hdrs = [
                "header.h",
                ":headers",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file("foo/header.h")
        val includeScanner: IncludeScanner?
        IncludeScanner > Mockito.mock<IncludeScanner?>(IncludeScanner::class.java)
        val includeScanningContext: CppIncludeScanningContextImpl =
            createIncludeScanningContext(includeScanner)
        val action: CppCompileAction = getCppCompileAction("//foo")
        val headerTree: SpecialArtifact = getArtifact("//foo:headers") as SpecialArtifact
        val headerTreeFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            TreeFileArtifact.createTreeOutput(headerTree, "file1.h")
        val environment: Environment = environmentWithTreeValue(headerTree, headerTreeFile)
        val actionExecutionContext: ActionExecutionContext = createActionExecutionContext(environment)

        val result: MutableList<Artifact?>? =
            includeScanningContext.findAdditionalInputs(
                action, actionExecutionContext, EMPTY_HEADER_DATA
            )

        Truth.assertThat(result).isNotNull()
        val collector: ArgumentCaptor<MutableCollection<Artifact?>?>
        Collection > createCaptor<Any?, MutableCollection<*>?>(MutableCollection::class.java)
        TODO(
            """
            |Cannot convert element
            |With text:
            |Object>verify(includeScanner)
            |        .processAsync(<T>any(), collector.capture()
            """.trimMargin()
        )
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()
        T > ArgumentMatchers.any<Any?>()

        Truth.assertThat(collector.getValue()).containsExactly(headerTreeFile, getArtifact("//foo:header.h"))
    }

    @Test
    @Throws(Exception::class)
    fun treeArtifactHeader_missingValue_returnsNull() {
        writeTreeRuleBzl(scratch.file("foo/def.bzl"))
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":def.bzl", "tree")

        package(features = [
            "cc_include_scanning",
            "header_modules",
            "use_header_modules",
        ])

        tree(name = "headers")

        cc_library(
            name = "foo",
            hdrs = [":headers"],
        )
        
        """.trimIndent()
        )
        val includeScanningContext: CppIncludeScanningContextImpl = createIncludeScanningContext(null)
        val action: CppCompileAction = getCppCompileAction("//foo")
        val actionExecutionContext: ActionExecutionContext = createActionExecutionContext(emptyEnvironment())

        val result: MutableList<Artifact?>? =
            includeScanningContext.findAdditionalInputs(
                action, actionExecutionContext, EMPTY_HEADER_DATA
            )

        Truth.assertThat(result).isNull()
    }

    private fun createActionExecutionContext(environment: Environment?): ActionExecutionContext {
        return ActionsTestUtil.createContextForInputDiscovery(
            DummyExecutor(),
            NullEventHandler.INSTANCE,
            ActionKeyContext(),
            FileOutErr(),
            scratch.resolve("/execroot"),
            environment,
            DiscoveredModulesPruner.DEFAULT
        )
    }

    @Throws(LabelSyntaxException::class)
    private fun getCppCompileAction(label: String?): CppCompileAction {
        return (getConfiguredTarget(label) as RuleConfiguredTarget)
            .getActions().stream()
            .filter({ obj: Any? -> CppCompileAction::class.java.isInstance(obj) })
            .map({ obj: Any? -> CppCompileAction::class.java.cast(obj) })
            .collect(MoreCollectors.onlyElement<T?>())
    }

    companion object {
        private val EMPTY_HEADER_DATA: IncludeScanningHeaderData = IncludeScanningHeaderData( /*pathToDeclaredHeader=*/
            ImmutableMap.of<K?, V?>(),  /*modularHeaders=*/
            ImmutableSet.of<E?>(),  /*systemIncludeDirs=*/
            ImmutableList.of<E?>(),  /*cmdlineIncludes=*/
            ImmutableList.of<E?>(),  /*isValidUndeclaredHeader=*/
            { ignored -> true })

        @Throws(IOException::class)
        private fun writeTreeRuleBzl(file: Path?) {
            FileSystemUtils.writeIsoLatin1(
                file,
                "def _tree(ctx):",
                "  dir = ctx.actions.declare_directory(ctx.label.name)",
                "  ctx.actions.run_shell(command = ':', outputs = [dir])",
                "  return DefaultInfo(files = depset([dir]))",
                "tree = rule(implementation = _tree)"
            )
        }

        private fun <T, S> createCaptor(clazz: Class<S?>): ArgumentCaptor<T?> {
            return ArgumentCaptor.forClass<S?, S?>(clazz) as ArgumentCaptor<T?>
        }

        private fun createIncludeScanningContext(
            includeScanner: IncludeScanner?
        ): CppIncludeScanningContextImpl {
            val includeScannerSupplier: IncludeScannerSupplier =
                Mockito.mock<IncludeScannerSupplier>(IncludeScannerSupplier::class.java)
            Mockito.`when`<Any?>(
                includeScannerSupplier.scannerFor(
                    ArgumentMatchers.any<MutableList<PathFragment>>(),
                    ArgumentMatchers.any<MutableList<PathFragment>>(),
                    ArgumentMatchers.any<MutableList<PathFragment>>()
                )
            ).thenReturn(includeScanner)
            return CppIncludeScanningContextImpl(Supplier { includeScannerSupplier })
        }

        private fun emptyEnvironment(): Environment {
            return environmentWithValues(ImmutableMap.of<SkyKey?, SkyValue?>())
        }

        private fun environmentWithTreeValue(
            tree: SpecialArtifact, vararg treeFiles: TreeFileArtifact?
        ): Environment {
            val treeValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                TreeArtifactValue.newBuilder(tree)
            for (treeFile in treeFiles) {
                treeValue.putChild(treeFile, < T > mock < T ? > (FileArtifactValue::class.java))
            }
            return environmentWithValues(ImmutableMap.of<K?, V?>(tree, treeValue.build()))
        }

        private fun environmentWithValues(values: ImmutableMap<SkyKey?, SkyValue?>): Environment {
            return object : AbstractSkyFunctionEnvironmentForTesting() {
                override fun getValueOrUntypedExceptions(
                    depKeys: Iterable<out SkyKey?>
                ): ImmutableMap<SkyKey?, ValueOrUntypedException?> {
                    return Streams.stream(depKeys)
                        .collect(
                            ImmutableMap.toImmutableMap(
                                Function.identity()
                            ) { key: SkyKey? ->
                                val value: SkyValue? = values.get(key)
                                if (value != null)
                                    ValueOrUntypedException.ofValueUntyped(value)
                                else
                                    ValueOrUntypedException.ofNull()
                            })
                }

                val listener: ExtendedEventHandler?
                    get() {
                        throw UnsupportedOperationException()
                    }
            }
        }
    }
}
