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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor
import com.google.devtools.build.lib.bazel.repository.RepoRule
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.packages.Attribute
import com.google.devtools.build.lib.packages.Types
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.eval.*
import net.starlark.java.syntax.Location
import java.util.function.Function

/**
 * The Starlark module containing the definition of `repository_rule` function to define a
 * Starlark remote repository.
 */
class StarlarkRepositoryModule : RepositoryModuleApi {
    @Throws(EvalException::class)
    override fun repositoryRule(
        implementation: StarlarkCallable?,
        attrs: Any?,
        local: Boolean,
        environ: Sequence<*>?,  // <String> expected
        configure: Boolean,
        remotable: Boolean,
        doc: Any?,  // <String> or Starlark.NONE
        thread: StarlarkThread
    ): StarlarkCallable {
        val builder: RepoRule.Builder =
            RepoRule.Companion.builder()
                .impl(implementation)
                .local(local)
                .configure(configure)
                .remotable(remotable)
                .environ(
                    ImmutableSet.copyOf<String?>(
                        Sequence.cast<String?>(
                            environ,
                            String::class.java,
                            "repository_rule"
                        )
                    )
                )
                .doc(
                    Starlark.toJavaOptional<String?>(doc, String::class.java)
                        .map<String?>(Function { docString: String? -> Starlark.trimDocString(docString) })
                )
        if (thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC)) {
            builder.addAttribute(
                Attribute.attr<MutableMap<String?, String?>?>("exec_properties", Types.STRING_DICT).defaultValue(
                    ImmutableMap.of<Any?, Any?>()
                ).build()
            )
        }
        if (attrs !== Starlark.NONE) {
            for (attr in Dict.cast<String?, Descriptor?>(attrs, String::class.java, Descriptor::class.java, "attrs")
                .entrySet()) {
                val attrDescriptor: Descriptor = attr.getValue()
                val source: AttributeValueSource = attrDescriptor.getValueSource()
                val attrName: String? = source.convertToNativeName(attr.getKey())
                if (builder.hasAttribute(attrName)) {
                    throw Starlark.errorf(
                        "There is already a built-in attribute '%s' which cannot be overridden", attrName
                    )
                }
                builder.addAttribute(attrDescriptor.build(attrName))
            }
        }
        val bzlInitContext: BzlInitThreadContext =
            BzlInitThreadContext.fromOrFail(thread, "repository_rule")
        builder.idBuilder().bzlFileLabel(bzlInitContext.getBzlFile())
        builder.transitiveBzlDigest(ByteString.copyFrom(bzlInitContext.getTransitiveDigest()))
        val repoMappingRecorder: SimpleRepoMappingRecorder? =
            thread.getThreadLocal<RepoMappingRecorder?>(RepoMappingRecorder::class.java) as SimpleRepoMappingRecorder?
        if (repoMappingRecorder != null) {
            builder.recordedRepoMappingEntries(repoMappingRecorder.recordedEntries())
        }
        return StarlarkRepoRule(builder)
    }

    /**
     * The value returned by calling the `repository_rule` function in Starlark. It itself is a
     * callable value; calling it defines a repo.
     */
    @StarlarkBuiltin(
        name = "repository_rule", category = DocCategory.BUILTIN, doc = """
A callable value that may be invoked within the implementation function of a module extension to instantiate and return a repository rule. Created by <a href="../globals/bzl.html#repository_rule"><code>repository_rule()</code></a>.

""".trimIndent()
    )
    class StarlarkRepoRule
    private constructor(private val builder: RepoRule.Builder) : StarlarkCallable, StarlarkExportable,
        RepoRule.Supplier {
        // Populated on first use after export to avoid recreating the repo rule on each usage.
        @kotlin.concurrent.Volatile
        private var repoRule: RepoRule? = null

        override fun getName(): String {
            return "repository_rule"
        }

        override fun isImmutable(): Boolean {
            return true
        }

        override fun export(
            handler: EventHandler?,
            extensionLabel: Label?,
            exportedName: String?,
            exportedLocation: Location?
        ) {
            builder.idBuilder().ruleName(exportedName)
        }

        val isExported: Boolean
            get() = builder.idBuilder().isRuleNameSet()

        override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
            if (!this.isExported) {
                printer.append("<anonymous starlark repository rule>")
            } else {
                printer.append("<starlark repository rule " + builder.idBuilder().build() + ">")
            }
        }

        @Throws(EvalException::class, InterruptedException::class)
        override fun call(thread: StarlarkThread?, args: Tuple, kwargs: Dict<String?, Any?>?): Any? {
            if (!args.isEmpty()) {
                throw EvalException("unexpected positional arguments")
            }
            val extensionEvalContext: ModuleExtensionEvalStarlarkThreadContext =
                ModuleExtensionEvalStarlarkThreadContext.fromOrNull(thread)
            if (extensionEvalContext == null) {
                throw EvalException(
                    "repo rules can only be called from within module extension impl functions"
                )
            }
            if (!this.isExported) {
                throw EvalException("attempting to instantiate a non-exported repository rule")
            }
            extensionEvalContext.lazilyCreateRepo(thread, kwargs, getRepoRule())
            return Starlark.NONE
        }

        override fun getRepoRule(): RepoRule? {
            if (repoRule != null) {
                return repoRule
            }
            synchronized(this) {
                if (repoRule != null) {
                    return repoRule
                }
                repoRule = builder.build()
                return repoRule
            }
        }
    }

    @Throws(EvalException::class)
    override fun moduleExtension(
        implementation: StarlarkCallable?,
        tagClasses: Dict<*, *>?,  // Dict<String, TagClass>
        doc: Any?,  // <String> or Starlark.NONE
        environ: Sequence<*>?,  // <String>
        osDependent: Boolean,
        archDependent: Boolean,
        factsVersion: StarlarkInt,
        thread: StarlarkThread
    ): Any {
        val factsVersionInt = factsVersion.toInt("facts_version")
        if (factsVersionInt < 0) {
            throw Starlark.errorf("facts_version must be non-negative, got %d", factsVersionInt)
        }
        return ModuleExtension.builder()
            .setImplementation(implementation)
            .setTagClasses(
                ImmutableMap.copyOf<K?, V?>(
                    Dict.cast<K?, V?>(
                        tagClasses,
                        String::class.java,
                        TagClass::class.java,
                        "tag_classes"
                    )
                )
            )
            .setDoc(
                Starlark.toJavaOptional<String?>(doc, String::class.java)
                    .map<U?>(Function { docString: String? -> Starlark.trimDocString(docString) })
            )
            .setDefiningBzlFileLabel(
                BzlInitThreadContext.fromOrFail(thread, "module_extension()").getBzlFile()
            )
            .setEnvVariables(
                ImmutableList.< E > copyOf < E ? > (Sequence.cast<T?>(
                    environ,
                    String::class.java,
                    "environ"
                ))
            )
            .setLocation(thread.getCallerLocation())
            .setOsDependent(osDependent)
            .setArchDependent(archDependent)
            .setFactsVersion(factsVersionInt)
            .build()
    }

    @Throws(EvalException::class)
    override fun tagClass(
        attrs: Dict<*, *>?,  // Dict<String, StarlarkAttrModule.Descriptor>
        doc: Any? // <String> or Starlark.NONE
    ): TagClass {
        val attrBuilder = ImmutableList.builder<Attribute?>()
        for (attr in Dict.cast<String?, Descriptor?>(attrs, String::class.java, Descriptor::class.java, "attrs")
            .entrySet()) {
            val attrDescriptor: Descriptor = attr.getValue()
            val source: AttributeValueSource = attrDescriptor.getValueSource()
            val attrName: String? = source.convertToNativeName(attr.getKey())
            attrBuilder.add(attrDescriptor.build(attrName))
            // TODO(wyv): validate attributes. No selects, no latebound defaults, or any crazy stuff like
            //   that.
        }
        return TagClass.create(
            attrBuilder.build(),
            Starlark.toJavaOptional<String?>(doc, String::class.java)
                .map<U?>(Function { docString: String? -> Starlark.trimDocString(docString) })
        )
    }
}
