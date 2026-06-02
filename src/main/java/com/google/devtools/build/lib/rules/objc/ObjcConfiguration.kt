// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.objc

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.config.BuildOptions
import com.google.devtools.build.lib.concurrent.ThreadSafety
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkThread

/** A compiler configuration containing flags required for Objective-C compilation.  */
@ThreadSafety.Immutable
@RequiresOptions(options = [ObjcCommandLineOptions::class])
class ObjcConfiguration(buildOptions: BuildOptions) : Fragment(), ObjcConfigurationApi {
    private val compilationMode: CompilationMode
    private val deviceDebugEntitlements: Boolean
    private val disallowSdkFrameworksAttributes: Boolean
    private val alwayslinkByDefault: Boolean
    private val stripExecutableSafely: Boolean
    private val builtinObjcStripAction: Boolean
    private val disableObjcFragment: Boolean

    init {
        val options: CoreOptions = buildOptions.get(CoreOptions::class.java)
        val objcOptions: ObjcCommandLineOptions = buildOptions.get(ObjcCommandLineOptions::class.java)

        this.compilationMode =
            Preconditions.checkNotNull(options.getCompilationMode(), "compilationMode")
        this.deviceDebugEntitlements = objcOptions.getDeviceDebugEntitlements()
        this.disallowSdkFrameworksAttributes =
            objcOptions.getIncompatibleDisallowSdkFrameworksAttributes()
        this.alwayslinkByDefault = objcOptions.getIncompatibleObjcAlwayslinkByDefault()
        this.stripExecutableSafely = objcOptions.getIncompatibleStripExecutableSafely()
        this.builtinObjcStripAction = objcOptions.getIncompatibleBuiltinObjcStripAction()
        this.disableObjcFragment = objcOptions.getDisableObjcFragment()
    }

    public override fun shouldInclude(): Boolean {
        return !disableObjcFragment
    }

    /**
     * Returns the current compilation mode.
     */
    fun getCompilationMode(): CompilationMode {
        return compilationMode
    }

    val coptsForCompilationMode: ImmutableList<String?>
        get() {
            when (compilationMode) {
                DBG, OPT -> {
                    return ImmutableList.of<String?>()
                }

                FASTBUILD -> {
                    return ImmutableList.of<String?>("-O0", "-DDEBUG=1")
                }

                else -> throw AssertionError()
            }
        }

    /**
     * Returns whether device debug entitlements should be included when signing an application.
     * 
     * 
     * Note that debug entitlements will be included only if the --device_debug_entitlements flag
     * is set **and** the compilation mode is not `opt`.
     */
    override fun useDeviceDebugEntitlements(): Boolean {
        return deviceDebugEntitlements && compilationMode !== CompilationMode.OPT
    }

    /** Returns whether sdk_frameworks and weak_sdk_frameworks attributes are disallowed.  */
    override fun disallowSdkFrameworksAttributes(): Boolean {
        return disallowSdkFrameworksAttributes
    }

    /** Returns whether objc_library and objc_import should default to alwayslink=True.  */
    override fun alwayslinkByDefault(): Boolean {
        return alwayslinkByDefault
    }

    /**
     * Looks at any explicit value for alwayslink on ctx and then falls back to the value of
     * alwayslink_by_default.
     */
    @Throws(EvalException::class)
    override fun targetShouldAlwayslink(ruleContext: StarlarkRuleContext, thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)

        val attributes: AttributeMap = ruleContext.getRuleContext().attributes()
        if (attributes.isAttributeValueExplicitlySpecified("alwayslink")) {
            return attributes.get("alwayslink", Type.BOOLEAN)
        }

        return alwayslinkByDefault
    }

    /**
     * Returns whether executable strip action should use flag -x, which does not break dynamic symbol
     * resolution.
     */
    override fun stripExecutableSafely(): Boolean {
        return stripExecutableSafely
    }

    /** Returns whether to emit a strip action as part of objc linking.  */
    override fun builtinObjcStripAction(): Boolean {
        return builtinObjcStripAction
    }

    companion object {
        @kotlin.jvm.JvmField
        @VisibleForTesting
        val DBG_COPTS: ImmutableList<String?> =
            ImmutableList.of<String?>("-O0", "-DDEBUG=1", "-fstack-protector", "-fstack-protector-all", "-g")

        @kotlin.jvm.JvmField
        @VisibleForTesting
        val OPT_COPTS: ImmutableList<String?> = ImmutableList.of<String?>(
            "-Os", "-DNDEBUG=1", "-Wno-unused-variable", "-Winit-self", "-Wno-extra"
        )
    }
}
