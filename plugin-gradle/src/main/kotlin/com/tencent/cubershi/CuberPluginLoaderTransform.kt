package com.tencent.cubershi

import com.android.SdkConstants
import com.android.build.api.transform.TransformInvocation
import com.android.utils.FileUtils
import com.tencent.cubershi.special.SpecialTransform
import javassist.ClassPool
import javassist.CtClass
import java.io.*
import java.util.function.BiConsumer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuberPluginLoaderTransform() : MyCustomClassTransform() {

    companion object {
        val classPool: ClassPool = ClassPool.getDefault()
        const val AndroidApplicationClassname = "android.app.Application"
        const val MockApplicationClassname = "com.tencent.cubershi.mock_interface.MockApplication"
        const val AndroidActivityClassname = "android.app.Activity"
        const val MockActivityClassname = "com.tencent.cubershi.mock_interface.MockActivity"
        const val AndroidServiceClassname = "android.app.Service"
        const val MockServiceClassname = "com.tencent.cubershi.mock_interface.MockService"
        const val AndroidFragment = "android.app.Fragment"
        val SpecialTransformMap = mapOf<String, SpecialTransform>(
        )
        val FragmentCtClassCache = mutableSetOf<String>()
        val ContainerFragmentCtClass = classPool["com.tencent.cubershi.mock_interface.ContainerFragment"]
    }

    override fun loadTransformFunction(): BiConsumer<InputStream, OutputStream> =
            BiConsumer { input, output ->
                val ctClass: CtClass = classPool.makeClass(input, false)

                val ctClassOriginName = ctClass.name
                if (SpecialTransformMap.containsKey(ctClassOriginName)) {
                    SpecialTransformMap[ctClassOriginName]!!.transform(classPool, ctClass)
                    ctClass.toBytecode(DataOutputStream(output))
                } else {
                    ctClass.replaceClassName(AndroidActivityClassname, MockActivityClassname)
                    ctClass.replaceClassName(AndroidApplicationClassname, MockApplicationClassname)
                    ctClass.replaceClassName(AndroidServiceClassname, MockServiceClassname)
                    renameFragment(ctClass)
                    if (ctClass.isFragment()) {
                        val newContainerFragmentCtClass = classPool.makeClass(ctClassOriginName, ContainerFragmentCtClass)
                        newContainerFragmentCtClass.toBytecode(DataOutputStream(output))
                        when (output) {
                            is FileOutputStream -> {
                                val newPath = currentFile.absolutePath.replace(currentFile.nameWithoutExtension, ctClass.simpleName)
                                FileOutputStream(newPath).use {
                                    ctClass.toBytecode(DataOutputStream(it))
                                }
                            }
                            is ZipOutputStream -> {
                                val newEntryPath = ctClass.name.replace(".", "/") + ".class"
                                output.putNextEntry(ZipEntry(newEntryPath))
                                ctClass.toBytecode(DataOutputStream(output))
                            }
                        }
                        return@BiConsumer
                    }
                    ctClass.toBytecode(DataOutputStream(output))
                }
            }

    override fun transform(invocation: TransformInvocation) {
        System.out.println("CuberPluginLoaderTransform开始")

        loadAppCtClass(invocation)

        super.transform(invocation)

        System.out.println("CuberPluginLoaderTransform结束")
    }

    private fun loadAppCtClass(invocation: TransformInvocation) {
        for (ti in invocation.inputs) {
            for (jarInput in ti.jarInputs) {
                val inputJar = jarInput.file
                loadJar(inputJar)
            }
            for (di in ti.directoryInputs) {
                val inputDir = di.file
                for (`in` in FileUtils.getAllFiles(inputDir)) {
                    if (`in`.name.endsWith(SdkConstants.DOT_CLASS)) {
                        loadClassFile(`in`)
                    }
                }
            }
        }
    }

    private fun loadJar(jarFile: File) {
        classPool.appendClassPath(jarFile.absolutePath)
    }

    private fun loadClassFile(classFile: File) {
        classPool.makeClass(classFile.inputStream(), false)
    }

    private fun renameFragment(ctClass: CtClass) {
        ctClass.refClasses.forEach {
            val refClassName: String = it as String
            if (refClassName == AndroidFragment) {
                return@forEach
            }
            if (refClassName in FragmentCtClassCache) {
                ctClass.replaceFragmentName(refClassName)
            } else if (classPool.getOrNull(refClassName) != null
                    && classPool.getOrNull(refClassName).isFragment()) {
                FragmentCtClassCache.add(refClassName)
                ctClass.replaceFragmentName(refClassName)
            }
        }
    }

    private fun CtClass.replaceFragmentName(fragmentClassName: String) {
        this.replaceClassName(fragmentClassName, fragmentClassName + "_")
    }

    private fun CtClass.isFragment(): Boolean {
        var tmp: CtClass? = this
        do {
            if (tmp?.name == AndroidFragment) {
                return true
            }
            tmp = tmp?.superclass
        } while (tmp != null)
        return false
    }

    override fun getName(): String = "CuberPluginLoaderTransform"
}