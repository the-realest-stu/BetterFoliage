package mods.octarinecore.metaprog

import mods.octarinecore.metaprog.Namespace.*
import net.minecraft.launchwrapper.IClassTransformer
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin
import org.apache.logging.log4j.LogManager
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.io.File
import java.io.FileOutputStream

@IFMLLoadingPlugin.TransformerExclusions(
    "mods.octarinecore.metaprog",
    "kotlin"
)
open class ASMPlugin(vararg val classes: Class<*>) : IFMLLoadingPlugin {
    override fun getASMTransformerClass() = classes.map { it.canonicalName }.toTypedArray()
    override fun getAccessTransformerClass() = null
    override fun getModContainerClass() = null
    override fun getSetupClass() = null
    override fun injectData(data: Map<String, Any>) {}
}

/**
 * Base class for convenient bytecode transformers.
 */
open class Transformer : IClassTransformer {

    val log = LogManager.getLogger(this)

    /** The list of transformers and targets. */
    var methodTransformers: MutableList<Pair<MethodRef, MethodTransformContext.()->Unit>> = arrayListOf()

    /** Add a transformation to perform. Call this during instance initialization.
     *
     * @param[method] the target method of the transformation
     * @param[trans] method transformation lambda
     */
    fun transformMethod(method: MethodRef, trans: MethodTransformContext.()->Unit) = methodTransformers.add(method to trans)

    override fun transform(name: String?, transformedName: String?, classData: ByteArray?): ByteArray? {
        if (classData == null) return null
        val classNode = ClassNode().apply { val reader = ClassReader(classData); reader.accept(this, 0) }
        var workDone = false
        var flags = 0

        synchronized(this) {
            methodTransformers.forEach { (targetMethod, transform) ->
                if (transformedName != targetMethod.parentClass.name) return@forEach

                for (method in classNode.methods) {
                    val namespace = Namespace.values().find {
                        method.name == targetMethod.name(it) && method.desc == targetMethod.asmDescriptor(it)
                    } ?: continue
                    when (namespace) {
                        MCP -> log.info("Found method ${targetMethod.parentClass.name}.${targetMethod.name(MCP)} ${targetMethod.asmDescriptor(MCP)}")
                        SRG -> log.info("Found method ${targetMethod.parentClass.name}.${targetMethod.name(namespace)} ${targetMethod.asmDescriptor(namespace)} (matching ${targetMethod.name(MCP)})")
                    }

                    // write input bytecode for debugging - definitely not in production...
                    //File("BF_debug").mkdir()
                    //FileOutputStream(File("BF_debug/$transformedName.class")).apply {
                    //    write(classData)
                    //    close()
                    //}

                    // transform
                    val ctx = MethodTransformContext(method, namespace)
                    ctx.transform()

                    if (ctx.recompute) flags = 3

                    workDone = true
                }
            }
        }



        return if (!workDone) classData else ClassWriter(flags).apply { classNode.accept(this) }.toByteArray()
    }
}

/**
 * Allows builder-style declarative definition of transformations. Transformation lambdas are extension
 * methods on this class.
 *
 * @param[method] the [MethodNode] currently being transformed
 * @param[environment] the type of environment we are in
 */
class MethodTransformContext(val method: MethodNode, val environment: Namespace) {

    var recompute = false

    fun makePublic() {
        method.access = (method.access or Opcodes.ACC_PUBLIC) and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()
    }

    /**
     * Find the first instruction that matches a predicate.
     *
     * @param[start] the instruction node to start iterating from
     * @param[predicate] the predicate to check
     */
    fun find(start: AbstractInsnNode, predicate: (AbstractInsnNode) -> Boolean): AbstractInsnNode? {
        var current: AbstractInsnNode? = start
        while (current != null && !predicate(current)) current = current.next
        return current
    }

    /** Find the first instruction in the current [MethodNode] that matches a predicate. */
    fun find(predicate: (AbstractInsnNode)->Boolean): AbstractInsnNode? = find(method.instructions.first, predicate)

    /** Find the first instruction in the current [MethodNode] with the given opcode. */
    fun find(opcode: Int) = find { it.opcode == opcode }

    /**
     * Insert new instructions after this one.
     *
     * @param[init] builder-style lambda to assemble instruction list
     */
    fun AbstractInsnNode.insertAfter(init: InstructionList.()->Unit) = InstructionList(environment).apply{
        this.init(); list.reversed().forEach { method.instructions.insert(this@insertAfter, it) }
    }

    /**
     * Insert new instructions before this one.
     *
     * @param[init] builder-style lambda to assemble instruction list
     */
    fun AbstractInsnNode.insertBefore(init: InstructionList.()->Unit) = InstructionList(environment).apply{
        val insertBeforeNode = this@insertBefore //.let { if (it.previous is FrameNode) it.previous else it }
        this.init(); list.forEach { method.instructions.insertBefore(insertBeforeNode, it) }
    }

    fun AbstractInsnNode.replace(init: InstructionList.()->Unit) = InstructionList(environment).apply {
        insertAfter(init)
        method.instructions.remove(this@replace)
    }
    /** Remove all isntructiuons between the given two (inclusive). */
    fun Pair<AbstractInsnNode, AbstractInsnNode>.remove() {
        var current: AbstractInsnNode? = first
        while (current != null && current != second) {
            val next = current.next
            method.instructions.remove(current)
            current = next
        }
        if (current != null) method.instructions.remove(current)
    }

    /**
     * Replace all isntructiuons between the given two (inclusive) with the specified instruction list.
     *
     * @param[init] builder-style lambda to assemble instruction list
     */
    fun Pair<AbstractInsnNode, AbstractInsnNode>.replace(init: InstructionList.()->Unit) {
        val beforeInsn = first.previous
        remove()
        beforeInsn.insertAfter(init)
    }

    /**
     * Matches variable instructions.
     *
     * @param[opcode] instruction opcode
     * @param[idx] variable the opcode references
     */
    fun varinsn(opcode: Int, idx: Int): (AbstractInsnNode)->Boolean = { insn ->
        insn.opcode == opcode && insn is VarInsnNode && insn.`var` == idx
    }

    fun invokeName(name: String): (AbstractInsnNode)->Boolean = { insn ->
        (insn as? MethodInsnNode)?.name == name
    }

    fun invokeRef(ref: MethodRef): (AbstractInsnNode)->Boolean = { insn ->
        (insn as? MethodInsnNode)?.let {
            it.name == ref.name(environment) && it.owner == ref.parentClass.name.replace(".", "/")
        } ?: false
    }
}

/**
 * Allows builder-style declarative definition of instruction lists.
 *
 * @param[environment] the type of environment we are in
 */
class InstructionList(val environment: Namespace) {

    fun insn(opcode: Int) = list.add(InsnNode(opcode))

    /** The instruction list being assembled. */
    val list: MutableList<AbstractInsnNode> = arrayListOf()

    /**
     * Adds a variable instruction.
     *
     * @param[opcode] instruction opcode
     * @param[idx] variable the opcode references
     */
    fun varinsn(opcode: Int, idx: Int) = list.add(VarInsnNode(opcode, idx))

    /**
     * Adds an INVOKESTATIC instruction.
     *
     * @param[target] the target method of the instruction
     * @param[isInterface] true if the target method is defined by an interface
     */
    fun invokeStatic(target: MethodRef, isInterface: Boolean = false) = list.add(MethodInsnNode(
            Opcodes.INVOKESTATIC,
            target.parentClass.name.replace(".", "/"),
            target.name(environment),
            target.asmDescriptor(environment),
            isInterface
    ))

    /**
     * Adds a GETFIELD instruction.
     *
     * @param[target] the target field of the instruction
     */
    fun getField(target: FieldRef) = list.add(FieldInsnNode(
            Opcodes.GETFIELD,
            target.parentClass.name.replace(".", "/"),
            target.name(environment),
            target.asmDescriptor(environment)
    ))
}