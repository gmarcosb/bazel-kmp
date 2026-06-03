// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.coverage

import com.google.testing.coverage.BranchExp
import com.google.testing.coverage.MethodProbesMapper
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import org.jacoco.core.internal.analysis.filter.IFilter
import org.jacoco.core.internal.analysis.filter.IFilterContext
import java.util.HashSet
import java.util.TreeMap

/** A visitor that maps each source code line to the probes corresponding to the lines.  */
class ClassProbesMapper(className: String?) : org.jacoco.core.internal.flow.ClassProbesVisitor(), IFilterContext {
    private val classLineToBranchExp: MutableMap<Int?, BranchExp?>

    private val allFilters: IFilter = org.jacoco.core.internal.analysis.filter.Filters.all()

    private val stringPool: org.jacoco.core.internal.analysis.StringPool

    // IFilterContext state updating during visitations
    val className: String?
    var superClassName: String? = null
        private set
    val classAnnotations: MutableSet<String?> = HashSet<String?>()
    val classAttributes: MutableSet<String?> = HashSet<String?>()
    var sourceFileName: String? = null
        private set
    var sourceDebugExtension: String? = null
        private set

    fun result(): MutableMap<Int?, BranchExp?> {
        return classLineToBranchExp
    }

    /** Create a new probe mapper object.  */
    init {
        classLineToBranchExp = TreeMap<Int?, BranchExp?>()
        stringPool = org.jacoco.core.internal.analysis.StringPool()
        this.className = stringPool.get(className)
    }

    override fun visitAnnotation(desc: String?, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
        classAnnotations.add(desc)
        return super.visitAnnotation(desc, visible)
    }

    override fun visitAttribute(attribute: org.objectweb.asm.Attribute) {
        classAttributes.add(attribute.type)
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceFileName = stringPool.get(source)
        sourceDebugExtension = debug
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<String?>?
    ) {
        superClassName = stringPool.get(superName)
    }

    /** Returns a visitor for mapping method code.  */
    override fun visitMethod(
        access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<String?>?
    ): org.jacoco.core.internal.flow.MethodProbesVisitor {
        return object : MethodProbesMapper(this, allFilters) {
            override fun visitEnd() {
                super.visitEnd()
                classLineToBranchExp.putAll(result())
            }
        }
    }

    override fun visitField(
        access: Int, name: String?, desc: String?, signature: String?, value: Any?
    ): org.objectweb.asm.FieldVisitor? {
        return super.visitField(access, name, desc, signature, value)
    }

    override fun visitTotalProbeCount(count: Int) {
        // Nothing to do. Maybe perform some checks here.
    }
}
