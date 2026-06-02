// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

/** A Starlark object representing a Bazel module in the external dependency graph.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "bazel_module",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = "Represents a Bazel module in the external dependency graph."
)
class StarlarkBazelModule private constructor(
  @kotlin.jvm.JvmField private val name: String?,
  @kotlin.jvm.JvmField private val version: String?,
  @kotlin.jvm.JvmField private val tags: Tags?,
  private val isRootModule: Boolean
) : net.starlark.java.eval.StarlarkValue {
    @net.starlark.java.annot.StarlarkBuiltin(
        name = "bazel_module_tags", category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN, doc = """
          Contains the tags in a module for the module extension currently being processed. This object has a field for each tag class of the extension, and the value of the field is a list containing an object for each tag instance. This "tag instance" object in turn has a field for each attribute of the tag class.
          <p>When passed as positional arguments to <code>print()</code> or <code>fail()</code>, tag instance objects turn into a meaningful string representation of the form "'install' tag at /home/user/workspace/MODULE.bazel:3:4". This can be used to construct error messages that point to the location of the tag in the module file, e.g. <code>fail("Conflict between", tag1, "and", tag2)</code>.
          """.trimIndent()
    )
    internal class Tags private constructor(typeCheckedTags: MutableMap<String?, net.starlark.java.eval.StarlarkList<TypeCheckedTag?>?>) :
        net.starlark.java.eval.Structure {
        private val typeCheckedTags: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.StarlarkList<TypeCheckedTag?>?>

        init {
            this.typeCheckedTags =
                com.google.common.collect.ImmutableMap.copyOf<String?, net.starlark.java.eval.StarlarkList<TypeCheckedTag?>?>(
                    typeCheckedTags
                )
        }

        override fun isImmutable(): Boolean {
            return true
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getValue(name: String?): Any? {
            return typeCheckedTags.get(name)
        }

        override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?> {
            return typeCheckedTags.keySet()
        }

        override fun getErrorMessageForUnknownField(field: String?): String? {
            return "unknown tag class " + field
        }
    }

    override fun isImmutable(): Boolean {
        return true
    }

    @net.starlark.java.annot.StarlarkMethod(name = "name", structField = true, doc = "The name of the module.")
    fun getName(): String? {
        return name
    }

    @net.starlark.java.annot.StarlarkMethod(name = "version", structField = true, doc = "The version of the module.")
    fun getVersion(): String? {
        return version
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "tags",
        structField = true,
        doc = "The tags in the module related to the module extension currently being processed."
    )
    fun getTags(): Tags? {
        return tags
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_root",
        structField = true,
        doc = "Whether this module is the root module."
    )
    fun isRoot(): Boolean {
        return isRootModule
    }

    companion object {
        /**
         * Creates a new [StarlarkBazelModule] object representing the given [AbridgedModule],
         * with its scope limited to the given [ModuleExtension]. It'll be populated with the tags
         * present in the given [ModuleExtensionUsage]. Any labels present in tags will be converted
         * using the given [RepositoryMapping].
         */
        @Throws(ExternalDepsException::class)
        fun create(
            module: AbridgedModule,
            extension: ModuleExtension,
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping,
            usage: ModuleExtensionUsage?,
            repoMappingRecorder: RepoMappingRecorder?,
            moduleIndex: Int
        ): StarlarkBazelModule {
            val labelConverter: LabelConverter =
                LabelConverter(
                    PackageIdentifier.create(repoMapping.contextRepo(), PathFragment.EMPTY_FRAGMENT),
                    repoMapping,
                    repoMappingRecorder
                )
            val tags: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag> =
                if (usage == null) com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.bazel.bzlmod.Tag?>() else usage.getTags()
            val typeCheckedTags: HashMap<String?, java.util.ArrayList<TypeCheckedTag?>?> =
                HashMap<String?, java.util.ArrayList<TypeCheckedTag?>?>()
            for (tagClassName in extension.tagClasses.keySet()) {
                typeCheckedTags.put(tagClassName, java.util.ArrayList<TypeCheckedTag?>())
            }
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return StarlarkBazelModule(
                module.getName(),
                module.getVersion().getNormalized(),
                com.google.devtools.build.lib.bazel.bzlmod.StarlarkBazelModule.Tags(
                    com.google.common.collect.Maps.transformValues<String?, java.util.ArrayList<TypeCheckedTag?>?, net.starlark.java.eval.StarlarkList<TypeCheckedTag?>?>(
                        typeCheckedTags,
                        com.google.common.base.Function { elems: java.util.ArrayList<TypeCheckedTag?>? ->
                            net.starlark.java.eval.StarlarkList.immutableCopyOf(elems)
                        })
                ),
                module.getKey() == ModuleKey.Companion.ROOT
            )
        }
    }
}
