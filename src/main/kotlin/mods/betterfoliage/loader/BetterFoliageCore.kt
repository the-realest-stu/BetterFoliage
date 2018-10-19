package mods.betterfoliage.loader

import mods.octarinecore.metaprog.ASMPlugin
import mods.octarinecore.metaprog.Transformer
import mods.octarinecore.metaprog.allAvailable
import net.minecraftforge.fml.relauncher.FMLLaunchHandler
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin
import org.objectweb.asm.Opcodes.*

@IFMLLoadingPlugin.TransformerExclusions(
    "mods.betterfoliage.loader",
    "mods.octarinecore.metaprog",
    "kotlin",
    "mods.octarinecore.kotlin"
)
@IFMLLoadingPlugin.SortingIndex(1400)
class BetterFoliageLoader : ASMPlugin(BetterFoliageTransformer::class.java)

class BetterFoliageTransformer : Transformer() {

    val isOptifinePresent = allAvailable(Refs.OptifineClassTransformer)

    init {
        if (FMLLaunchHandler.side().isClient) setupClient()
    }

    fun setupClient() {
        // where: WorldClient.showBarrierParticles(), right after invoking Block.randomDisplayTick
        // what: invoke BF code for every random display tick
        // why: allows us to catch random display ticks, without touching block code
        transformMethod(Refs.showBarrierParticles) {
            find(invokeRef(Refs.randomDisplayTick))?.insertAfter {
                log.info("[BetterFoliageLoader] Applying random display tick call hook")
                varinsn(ALOAD, 0)
                varinsn(ALOAD, 11)
                varinsn(ALOAD, 7)
                invokeStatic(Refs.onRandomDisplayTick)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply random display tick call hook!")
        }

        // where: BlockStateContainer$StateImplementation.getAmbientOcclusionLightValue()
        // what: invoke BF code to overrule AO transparency value
        // why: allows us to have light behave properly on non-solid log blocks
        transformMethod(Refs.getAmbientOcclusionLightValue) {
            find(FRETURN)?.insertBefore {
                log.info("[BetterFoliageLoader] Applying getAmbientOcclusionLightValue() override")
                varinsn(ALOAD, 0)
                invokeStatic(Refs.getAmbientOcclusionLightValueOverride)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply getAmbientOcclusionLightValue() override!")
        }

        // where: BlockStateContainer$StateImplementation.useNeighborBrightness()
        // what: invoke BF code to overrule _useNeighborBrightness_
        // why: allows us to have light behave properly on non-solid log blocks
        transformMethod(Refs.useNeighborBrightness) {
            find(IRETURN)?.insertBefore {
                log.info("[BetterFoliageLoader] Applying useNeighborBrightness() override")
                varinsn(ALOAD, 0)
                invokeStatic(Refs.useNeighborBrightnessOverride)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply useNeighborBrightness() override!")
        }

        // where: BlockStateContainer$StateImplementation.doesSideBlockRendering()
        // what: invoke BF code to overrule condition
        // why: allows us to make log blocks non-solid
        transformMethod(Refs.doesSideBlockRendering) {
            find(IRETURN)?.insertBefore {
                log.info("[BetterFoliageLoader] Applying doesSideBlockRendering() override")
                varinsn(ALOAD, 1)
                varinsn(ALOAD, 2)
                varinsn(ALOAD, 3)
                invokeStatic(Refs.doesSideBlockRenderingOverride)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply doesSideBlockRendering() override!")
        }

        // where: BlockStateContainer$StateImplementation.isOpaqueCube()
        // what: invoke BF code to overrule condition
        // why: allows us to make log blocks non-solid
        transformMethod(Refs.isOpaqueCube) {
            find(IRETURN)?.insertBefore {
                log.info("[BetterFoliageLoader] Applying isOpaqueCube() override")
                varinsn(ALOAD, 0)
                invokeStatic(Refs.isOpaqueCubeOverride)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply isOpaqueCube() override!")
        }

        // where: ModelLoader.setupModelRegistry(), right before the textures are loaded
        // what: invoke handler code with ModelLoader instance
        // why: allows us to iterate the unbaked models in ModelLoader in time to register textures
        transformMethod(Refs.setupModelRegistry) {
            find(invokeName("addAll"))?.insertAfter {
                log.info("[BetterFoliageLoader] Applying ModelLoader lifecycle callback")
                varinsn(ALOAD, 0)
                invokeStatic(Refs.onAfterLoadModelDefinitions)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply ModelLoader lifecycle callback!")
        }

        // where: RenderChunk.rebuildChunk()
        // what: replace call to BlockRendererDispatcher.renderBlock()
        // why: allows us to perform additional rendering for each block
        // what: invoke code to overrule result of Block.canRenderInLayer()
        // why: allows us to render transparent quads for blocks which are only on the SOLID layer
        transformMethod(Refs.rebuildChunk) {
            recompute = true
            find(invokeRef(Refs.renderBlock))?.replace {
                log.info("[BetterFoliageLoader] Applying RenderChunk block render override")
                varinsn(ALOAD, if (isOptifinePresent) 22 else 20)
                invokeStatic(Refs.renderWorldBlock)
            }
            if (isOptifinePresent) {
                find(varinsn(ISTORE, 23))?.insertAfter {
                    log.info("[BetterFoliageLoader] Applying RenderChunk block layer override")
                    varinsn(ALOAD, 19)
                    varinsn(ALOAD, 18)
                    varinsn(ALOAD, 22)
                    invokeStatic(Refs.canRenderBlockInLayer)
                    varinsn(ISTORE, 23)
                }
            } else {
                find(invokeRef(Refs.canRenderInLayer))?.replace {
                    log.info("[BetterFoliageLoader] Applying RenderChunk block layer override")
                    invokeStatic(Refs.canRenderBlockInLayer)
                }
            }
        }

        // where: net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace
        // what: make constructor public
        // why: use vanilla AO calculation at will without duplicating code
        transformMethod(Refs.AOF_constructor) {
            log.info("[BetterFoliageLoader] Setting AmbientOcclusionFace constructor public")
            makePublic()
        }

        // where: shadersmod.client.SVertexBuilder.pushEntity()
        // what: invoke code to overrule block data
        // why: allows us to change the block ID seen by shader programs
        transformMethod(Refs.pushEntity_state) {
            find(invokeRef(Refs.pushEntity_num))?.insertBefore {
                log.info("[BetterFoliageLoader] Applying SVertexBuilder.pushEntity() block ID override")
                varinsn(ALOAD, 0)
                invokeStatic(Refs.getBlockIdOverride)
            } ?: log.warn("[BetterFoliageLoader] Failed to apply SVertexBuilder.pushEntity() block ID override!")
        }
    }
}