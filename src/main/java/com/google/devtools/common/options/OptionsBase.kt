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
package com.google.devtools.common.options

/**
 * Base class for all options classes. Extend this class, adding public instance fields annotated
 * with [Option]. Then you can create instances either programmatically:
 * 
 * <pre>
 * X x = Options.getDefaults(X.class);
 * x.host = "localhost";
 * x.port = 80;
</pre> * 
 * 
 * or from an array of command-line arguments:
 * 
 * <pre>
 * OptionsParser parser = OptionsParser.builder()
 * .optionsClasses(X.class)
 * .build();
 * parser.parse("--host", "localhost", "--port", "80");
 * X x = parser.getOptions(X.class);
</pre> * 
 * 
 * 
 * Subclasses of `OptionsBase` *must* be constructed reflectively, i.e. using not
 * `new MyOptions()`, but one of the above methods instead. (Direct construction creates an
 * empty instance, not containing default values. This leads to surprising behavior and often `NullPointerExceptions`, etc.)
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
abstract class OptionsBase
/** Subclasses must provide a default (no argument) constructor.  */
protected constructor() {
    /**
     * Returns the "options class" that this object belongs to. By default it is the object's class.
     * However, for classes annotated with `@OptionsClass`, this returns the base class and not
     * the generated implementation class.
     */
    fun getOptionsClass(): java.lang.Class<out OptionsBase?> {
        return getClass()
    }
}
