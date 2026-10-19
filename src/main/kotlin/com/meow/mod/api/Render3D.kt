package com.meow.mod.api

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.awt.Color
import kotlin.math.sqrt

// World rendering for the ESP features (see TrevorHelper). Everything is submitted through
// the level render's submit-node API during LevelRenderEvents.END_MAIN: the level renderer
// owns the buffers now, so there is no buffer source of our own to endBatch.
//
// Coordinates: the pose stack offered by the render event carries the camera rotation but
// not the camera position, exactly like the old world render events, so each submission
// first translates by -camera and the vertices below are written in absolute world
// coordinates. This mirrors how devoniandoogan's Render3DImmediate treats its pose.
//
// Through walls: vanilla's line/quad render types depth-test, which hides an ESP behind
// terrain. Two render types of our own are built from the vanilla pipeline snippets -
// same shaders and vertex formats, but with the depth test set to ALWAYS_PASS and no
// depth writes - the same recipe devoniandoogan uses for its "_ESP" pipeline variants.
// Both snippet and registration members are package-private, so they are read
// reflectively; if anything about that ever fails (a rename in a future update) the
// feature silently falls back to the vanilla types: the ESP still draws, it is simply
// occluded by walls instead of glowing through them.
object Render3D {
	private val LOGGER = LoggerFactory.getLogger("MeowMod")
	private var warnedFallback = false

	private val espLines: RenderType by lazy { espType("meowmod/esp_lines", "LINES_SNIPPET") ?: RenderTypes.linesTranslucent() }
	private val espFill: RenderType by lazy { espType("meowmod/esp_fill", "DEBUG_FILLED_SNIPPET") ?: RenderTypes.debugQuads() }

	// A custom render type with the depth test forced to always pass. Returns null when the
	// reflective part does not resolve, so the caller can fall back to a vanilla type.
	private fun espType(name: String, snippetField: String): RenderType? = try {
		val field = RenderPipelines::class.java.getDeclaredField(snippetField)
		field.isAccessible = true
		val snippet = field.get(null) as RenderPipeline.Snippet
		val pipeline = RenderPipeline.builder(snippet)
			.withLocation(name)
			// ALWAYS_PASS with the depth write off: the geometry draws over everything
			// without corrupting the depth buffer the world is rendered against.
			.withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.build()
		val registered = register(pipeline) ?: pipeline
		val setup = RenderSetup.builder(registered).createRenderSetup()
		val create = RenderType::class.java.getDeclaredMethod("create", String::class.java, RenderSetup::class.java)
		create.isAccessible = true
		create.invoke(null, name, setup) as RenderType
	} catch (t: Throwable) {
		if (!warnedFallback) {
			warnedFallback = true
			LOGGER.info("[MeowMod] Custom ESP render types unavailable ({}), using vanilla types", t.message)
		}
		null
	}

	// Adds the pipeline to the vanilla pipeline registry (by location) so it is treated
	// like any other. Only needed for lookups by location, hence best effort.
	private fun register(pipeline: RenderPipeline): RenderPipeline? = try {
		val method = RenderPipelines::class.java.getDeclaredMethod("register", RenderPipeline::class.java)
		method.isAccessible = true
		method.invoke(null, pipeline) as RenderPipeline
	} catch (_: Throwable) {
		null
	}

	// Wireframe box around an absolute-world AABB, plus an optional translucent fill.
	@JvmOverloads
	fun submitBox(
		collector: SubmitNodeCollector,
		pose: PoseStack,
		camera: Vec3,
		box: AABB,
		color: Color,
		fill: Boolean = true,
		outline: Boolean = true,
		lineWidth: Float = 2.0f
	) {
		if (color.alpha == 0) return
		pose.pushPose()
		pose.translate(-camera.x, -camera.y, -camera.z)
		if (outline) {
			val rgb = color.rgb
			collector.submitCustomGeometry(pose, espLines) { _, vertex ->
				forEachEdge(box) { x1, y1, z1, x2, y2, z2 ->
					val length = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1))
					if (length <= 1.0e-6) return@forEachEdge
					val nx = ((x2 - x1) / length).toFloat()
					val ny = ((y2 - y1) / length).toFloat()
					val nz = ((z2 - z1) / length).toFloat()
					vertex.addVertex(x1.toFloat(), y1.toFloat(), z1.toFloat()).setColor(rgb).setNormal(nx, ny, nz).setLineWidth(lineWidth)
					vertex.addVertex(x2.toFloat(), y2.toFloat(), z2.toFloat()).setColor(rgb).setNormal(nx, ny, nz).setLineWidth(lineWidth)
				}
			}
		}
		if (fill) {
			val argb = (color.alpha shl 24) or (color.rgb and 0xFFFFFF)
			collector.submitCustomGeometry(pose, espFill) { _, vertex ->
				forEachFace(box) { quad ->
					for (point in quad) vertex.addVertex(point.x.toFloat(), point.y.toFloat(), point.z.toFloat()).setColor(argb)
				}
			}
		}
		pose.popPose()
	}

	// A single line from the camera out to a point in the world - the ESP tracer.
	fun submitTracer(collector: SubmitNodeCollector, pose: PoseStack, camera: Vec3, target: Vec3, color: Color, lineWidth: Float = 2.0f) {
		if (color.alpha == 0) return
		val dx = target.x - camera.x
		val dy = target.y - camera.y
		val dz = target.z - camera.z
		val length = sqrt(dx * dx + dy * dy + dz * dz)
		if (length <= 1.0e-4) return
		val nx = (dx / length).toFloat()
		val ny = (dy / length).toFloat()
		val nz = (dz / length).toFloat()
		val rgb = color.rgb
		pose.pushPose()
		pose.translate(-camera.x, -camera.y, -camera.z)
		collector.submitCustomGeometry(pose, espLines) { _, vertex ->
			vertex.addVertex(camera.x.toFloat(), camera.y.toFloat(), camera.z.toFloat()).setColor(rgb).setNormal(nx, ny, nz).setLineWidth(lineWidth)
			vertex.addVertex(target.x.toFloat(), target.y.toFloat(), target.z.toFloat()).setColor(rgb).setNormal(nx, ny, nz).setLineWidth(lineWidth)
		}
		pose.popPose()
	}

	// Floating text at a world position, through walls. submitNameTag builds the whole
	// billboard (camera facing, text scaling, the see-through second pass) itself; the
	// seeThrough flag is what makes it readable behind terrain, matching the upstream
	// "phase" rendering. Full-bright light so an area label is readable in the dark.
	fun submitLabel(collector: SubmitNodeCollector, pose: PoseStack, camera: Vec3, at: Vec3, text: Component, cameraState: net.minecraft.client.renderer.state.level.CameraRenderState) {
		pose.pushPose()
		pose.translate(-camera.x, -camera.y, -camera.z)
		collector.submitNameTag(pose, at, 0, text, true, 0xF000F0, cameraState)
		pose.popPose()
	}

	private inline fun forEachEdge(box: AABB, action: (Double, Double, Double, Double, Double, Double) -> Unit) {
		val x1 = box.minX; val y1 = box.minY; val z1 = box.minZ
		val x2 = box.maxX; val y2 = box.maxY; val z2 = box.maxZ
		// Bottom square, top square, then the four vertical edges.
		action(x1, y1, z1, x2, y1, z1); action(x2, y1, z1, x2, y1, z2)
		action(x2, y1, z2, x1, y1, z2); action(x1, y1, z2, x1, y1, z1)
		action(x1, y2, z1, x2, y2, z1); action(x2, y2, z1, x2, y2, z2)
		action(x2, y2, z2, x1, y2, z2); action(x1, y2, z2, x1, y2, z1)
		action(x1, y1, z1, x1, y2, z1); action(x2, y1, z1, x2, y2, z1)
		action(x2, y1, z2, x2, y2, z2); action(x1, y1, z2, x1, y2, z2)
	}

	// The six faces of the box, each as four corners for the quad topology. Cull is off in
	// the pipeline, so winding order does not matter: the fill is visible from inside too.
	private inline fun forEachFace(box: AABB, action: (Array<Vec3>) -> Unit) {
		val x1 = box.minX; val y1 = box.minY; val z1 = box.minZ
		val x2 = box.maxX; val y2 = box.maxY; val z2 = box.maxZ
		action(arrayOf(Vec3(x1, y1, z1), Vec3(x2, y1, z1), Vec3(x2, y2, z1), Vec3(x1, y2, z1)))
		action(arrayOf(Vec3(x1, y1, z2), Vec3(x2, y1, z2), Vec3(x2, y2, z2), Vec3(x1, y2, z2)))
		action(arrayOf(Vec3(x1, y1, z1), Vec3(x1, y1, z2), Vec3(x1, y2, z2), Vec3(x1, y2, z1)))
		action(arrayOf(Vec3(x2, y1, z1), Vec3(x2, y1, z2), Vec3(x2, y2, z2), Vec3(x2, y2, z1)))
		action(arrayOf(Vec3(x1, y1, z1), Vec3(x2, y1, z1), Vec3(x2, y1, z2), Vec3(x1, y1, z2)))
		action(arrayOf(Vec3(x1, y2, z1), Vec3(x2, y2, z1), Vec3(x2, y2, z2), Vec3(x1, y2, z2)))
	}
}
