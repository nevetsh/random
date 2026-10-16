package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.events.MeowEvents
import com.meow.mod.skyblock.Island
import com.meow.mod.skyblock.LocationUtils
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

// Ported from OdinClient (skies-starred/OdinClient, BSD-3-Clause)
object WorldScanner : Feature(
	"worldScanner",
	"Scans and highlights structures in the Crystal Hollows (crystals, mob spots, grottos, dragon nest, worm fishing).",
	subcategory = "World Scanner",
	displayName = "World Scanner",
	enabledByDefault = true
) {
	private const val GOLD = 0xFFFFAA00.toInt()
	private const val GREEN = 0xFF55FF55.toInt()
	private const val AQUA = 0xFF55FFFF.toInt()
	private const val DARK_PURPLE = 0xFFAA00AA.toInt()
	private const val LIGHT_PURPLE = 0xFFFF55FF.toInt()
	private const val WHITE = 0xFFFFFFFF.toInt()

	private val scanCrystals = addSwitch("scanCrystals", true, "Scans for crystal waypoints.", "Scan Crystals")
	private val scanMobSpots = addSwitch("scanMobSpots", true, "Scans for mob spawn locations.", "Scan Mob Spots")
	private val scanFairyGrottos = addSwitch("scanFairyGrottos", true, "Scans for fairy grottos.", "Scan Fairy Grottos")
	private val scanDragonNest = addSwitch("scanDragonNest", true, "Scans for the golden dragon nest.", "Scan Dragon Nest")
	private val scanWormFishing = addSwitch("scanWormFishing", false, "Scans for worm fishing spots.", "Scan Worm Fishing")
	private val lavaEsp = addSwitch("lavaEsp", false, "Highlights lava blocks.", "Lava ESP")
	private val waterEsp = addSwitch("waterEsp", false, "Highlights water blocks.", "Water ESP")
	private val espRange = addSlider("espRange", 32.0, 8.0, 128.0, "Range for lava and water ESP.", "ESP Range")
	private val sendCoordsInChat = addSwitch("sendCoordsInChat", false, "Sends coordinates to chat when a structure is found.", "Send Coords in Chat")

	private val waypoints = ConcurrentHashMap<String, WaypointData>()
	private val scannedChunks = HashSet<Long>()
	private val lavaBlocks = ConcurrentHashMap.newKeySet<Long>()
	private val waterBlocks = ConcurrentHashMap.newKeySet<Long>()

	@Volatile
	private var enabled = false
	private var initialized = false

	data class WaypointData(val pos: BlockPos, val category: Int, val color: Int)

	private enum class Quarter {
		NUCLEUS, JUNGLE, PRECURSOR, GOBLIN, MITHRIL, MAGMA, ANY;

		fun test(x: Int, y: Int, z: Int): Boolean = when (this) {
			NUCLEUS -> x in 449..576 && z in 449..576
			JUNGLE -> x <= 576 && z <= 576
			PRECURSOR -> x > 448 && z > 448
			GOBLIN -> x <= 576 && z > 448
			MITHRIL -> x > 448 && z <= 576
			MAGMA -> y < 80
			ANY -> true
		}
	}

	private enum class Structure(
		val displayName: String,
		val blocks: List<Block?>,
		val color: Int,
		val quarter: Quarter,
		val offset: BlockPos = BlockPos.ZERO,
		val category: Int // 0: Crystal, 1: Mob, 2: Grotto, 3: Dragon, 4: Worm
	) {
		KING("King", listOf(Blocks.WOOL.red(), Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS), GOLD, Quarter.GOBLIN, BlockPos(1, -1, 2), 0),
		QUEEN("Queen", listOf(Blocks.STONE, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.CAULDRON), GOLD, Quarter.ANY, BlockPos(0, 5, 0), 0),
		DIVAN("Divan", listOf(Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS), GREEN, Quarter.MITHRIL, BlockPos(0, 5, 0), 0),
		CITY("City", listOf(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS), AQUA, Quarter.PRECURSOR, BlockPos(24, 0, -17), 0),
		TEMPLE("Temple", listOf(Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.STONE, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, Blocks.OAK_LEAVES, Blocks.OAK_LEAVES, Blocks.DYED_TERRACOTTA.lime(), Blocks.DYED_TERRACOTTA.lime(), Blocks.DYED_TERRACOTTA.green()), DARK_PURPLE, Quarter.ANY, BlockPos(-45, 47, -18), 0),
		BAL("Bal", listOf(Blocks.LAVA, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER), GOLD, Quarter.MAGMA, BlockPos(0, 1, 0), 0),
		CORLEONE_DOCK("Corleone Dock", listOf(
			Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS,
			null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null,
			Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.FIRE, Blocks.STONE_BRICKS
		), GREEN, Quarter.MITHRIL, BlockPos(23, 11, 17), 1),
		CORLEONE_HOLE("Corleone Hole", listOf(Blocks.SMOOTH_STONE_SLAB, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.POLISHED_GRANITE), GREEN, Quarter.MITHRIL, BlockPos(-18, -1, 29), 1),
		KEY_GUARDIAN_SPIRAL("Key Guardian Spiral", listOf(Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE), DARK_PURPLE, Quarter.JUNGLE, BlockPos.ZERO, 1),
		KEY_GUARDIAN_TOWER("Key Guardian Tower", listOf(Blocks.STONE, Blocks.POLISHED_GRANITE, Blocks.JUNGLE_SLAB), DARK_PURPLE, Quarter.JUNGLE, BlockPos.ZERO, 1),
		XALX("Xalx", listOf(Blocks.STONE, Blocks.COAL_BLOCK, Blocks.FIRE, Blocks.NETHER_QUARTZ_ORE, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR), GREEN, Quarter.GOBLIN, BlockPos(-2, 1, -2), 1),
		PETE("Pete", listOf(Blocks.NETHERRACK, Blocks.FIRE, Blocks.IRON_BARS, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR), GOLD, Quarter.GOBLIN, BlockPos.ZERO, 1),
		ODAWA("Odawa", listOf(Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG), GREEN, Quarter.JUNGLE, BlockPos.ZERO, 1),
		GOLDEN_DRAGON("Golden Dragon", listOf(Blocks.STONE, Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), Blocks.PLAYER_HEAD, Blocks.WOOL.red()), WHITE, Quarter.ANY, BlockPos(0, -3, 5), 3)
	}

	fun initialize() {
		if (initialized) return
		initialized = true

		ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
			if (!enabled || !LocationUtils.isCurrentArea(Island.CrystalHollows)) return@register
			val key = chunkKey(chunk)
			if (scannedChunks.add(key)) handleChunkLoad(chunk)
		}

		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { ctx -> render(ctx) }

		MeowEvents.onWorldLoad { clearWaypoints() }
	}

	private fun render(ctx: LevelRenderContext) {
		if (!enabled) return
		if (!LocationUtils.isCurrentArea(Island.CrystalHollows)) return

		val minecraft = Minecraft.getInstance()
		val camera = minecraft.gameRenderer.mainCamera().position()
		val pose = ctx.poseStack()
		pose.pushPose()
		pose.translate(-camera.x, -camera.y, -camera.z)

		waypoints.forEach { (_, data) ->
			val shouldRender = when (data.category) {
				0 -> scanCrystals.get()
				1 -> scanMobSpots.get()
				2 -> scanFairyGrottos.get()
				3 -> scanDragonNest.get()
				4 -> scanWormFishing.get()
				else -> false
			}
			if (!shouldRender) return@forEach

			val pos = data.pos
			val color = data.color
			ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.lines()) { p, vc ->
				box(vc, p, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0, color)
			}
		}

		if ((lavaEsp.get() || waterEsp.get()) && minecraft.player != null) {
			val playerPos = minecraft.player!!.position()
			val rangeSq = espRange.get() * espRange.get()
			if (lavaEsp.get()) lavaBlocks.forEach { renderBlock(ctx, pose, it, rangeSq, playerPos, GOLD) }
			if (waterEsp.get()) waterBlocks.forEach { renderBlock(ctx, pose, it, rangeSq, playerPos, AQUA) }
		}

		pose.popPose()
	}

	private fun renderBlock(ctx: LevelRenderContext, pose: PoseStack, packed: Long, rangeSq: Double, playerPos: Vec3, color: Int) {
		val mutable = BlockPos.MutableBlockPos()
		mutable.set(packed)
		val center = Vec3.atCenterOf(mutable)
		if (center.distanceToSqr(playerPos) > rangeSq) return
		val x = mutable.x.toDouble()
		val y = mutable.y.toDouble()
		val z = mutable.z.toDouble()
		ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.lines()) { p, vc ->
			box(vc, p, x, y, z, x + 1.0, y + 1.0, z + 1.0, color)
		}
	}

	private fun box(vc: VertexConsumer, pose: PoseStack.Pose, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, argb: Int) {
		val a = (argb ushr 24) and 0xFF
		val r = (argb ushr 16) and 0xFF
		val g = (argb ushr 8) and 0xFF
		val b = argb and 0xFF
		val matrix = pose.pose()

		fun edge(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) {
			val nx = (bx - ax).toFloat()
			val ny = (by - ay).toFloat()
			val nz = (bz - az).toFloat()
			vc.addVertex(matrix, ax.toFloat(), ay.toFloat(), az.toFloat()).setColor(r, g, b, a).setNormal(nx, ny, nz)
			vc.addVertex(matrix, bx.toFloat(), by.toFloat(), bz.toFloat()).setColor(r, g, b, a).setNormal(nx, ny, nz)
		}

		edge(x1, y1, z1, x2, y1, z1); edge(x2, y1, z1, x2, y1, z2); edge(x2, y1, z2, x1, y1, z2); edge(x1, y1, z2, x1, y1, z1)
		edge(x1, y2, z1, x2, y2, z1); edge(x2, y2, z1, x2, y2, z2); edge(x2, y2, z2, x1, y2, z2); edge(x1, y2, z2, x1, y2, z1)
		edge(x1, y1, z1, x1, y2, z1); edge(x2, y1, z1, x2, y2, z1); edge(x2, y1, z2, x2, y2, z2); edge(x1, y1, z2, x1, y2, z2)
	}

	private fun clearWaypoints() {
		waypoints.clear()
		scannedChunks.clear()
		lavaBlocks.clear()
		waterBlocks.clear()
	}

	private fun handleChunkLoad(chunk: LevelChunk) {
		val mutable = BlockPos.MutableBlockPos()
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ
		for (x in 0..15) {
			for (z in 0..15) {
				val worldX = baseX + x
				val worldZ = baseZ + z

				for (y in 0..170) {
					val state = blockState(chunk, x, y, z)
					if (state.isAir) continue
					val block = state.block

					when (block) {
						Blocks.WOOL.red() -> {
							if (scanCrystals.get() && !waypoints.containsKey("King") && Quarter.GOBLIN.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.KING.blocks)) {
								addWaypoint(Structure.KING, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.STONE -> {
							if (scanCrystals.get() && !waypoints.containsKey("Queen") && Quarter.ANY.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.QUEEN.blocks)) {
								addWaypoint(Structure.QUEEN, mutable.set(worldX, y, worldZ))
							}
							if (scanMobSpots.get() && !waypoints.containsKey("Key Guardian Tower") && Quarter.JUNGLE.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.KEY_GUARDIAN_TOWER.blocks)) {
								addWaypoint(Structure.KEY_GUARDIAN_TOWER, mutable.set(worldX, y, worldZ))
							}
							if (scanMobSpots.get() && !waypoints.containsKey("Xalx") && Quarter.GOBLIN.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.XALX.blocks)) {
								addWaypoint(Structure.XALX, mutable.set(worldX, y, worldZ))
							}
							if (scanDragonNest.get() && !waypoints.containsKey("Golden Dragon") && Quarter.ANY.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.GOLDEN_DRAGON.blocks)) {
								addWaypoint(Structure.GOLDEN_DRAGON, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.QUARTZ_PILLAR -> {
							if (scanCrystals.get() && !waypoints.containsKey("Divan") && Quarter.MITHRIL.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.DIVAN.blocks)) {
								addWaypoint(Structure.DIVAN, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.STONE_BRICKS -> {
							if (scanCrystals.get() && !waypoints.containsKey("City") && Quarter.PRECURSOR.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.CITY.blocks)) {
								addWaypoint(Structure.CITY, mutable.set(worldX, y, worldZ))
							}
							if (scanMobSpots.get() && !waypoints.containsKey("Corleone Dock") && Quarter.MITHRIL.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.CORLEONE_DOCK.blocks)) {
								addWaypoint(Structure.CORLEONE_DOCK, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.BEDROCK -> {
							if (scanCrystals.get() && !waypoints.containsKey("Temple") && Quarter.ANY.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.TEMPLE.blocks)) {
								addWaypoint(Structure.TEMPLE, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.LAVA -> {
							if (lavaEsp.get() && blockState(chunk, x, y + 1, z).isAir) lavaBlocks.add(mutable.set(worldX, y, worldZ).asLong())
							if (scanCrystals.get() && !waypoints.containsKey("Bal") && Quarter.MAGMA.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.BAL.blocks)) {
								addWaypoint(Structure.BAL, mutable.set(worldX, y, worldZ))
							}
							if (scanWormFishing.get() && y > 63 && !waypoints.containsKey("Worm Fishing") && ((worldX >= 564 && worldZ >= 513) || (worldX >= 513 && worldZ >= 564))) {
								if (blockState(chunk, x, y + 1, z).isAir) addWaypointDirect("Worm Fishing", mutable.set(worldX, y, worldZ), GOLD, 4)
							}
						}

						Blocks.WATER -> {
							if (waterEsp.get() && blockState(chunk, x, y + 1, z).isAir) waterBlocks.add(mutable.set(worldX, y, worldZ).asLong())
						}

						Blocks.SMOOTH_STONE_SLAB -> {
							if (scanMobSpots.get() && !waypoints.containsKey("Corleone Hole") && Quarter.MITHRIL.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.CORLEONE_HOLE.blocks)) {
								addWaypoint(Structure.CORLEONE_HOLE, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.JUNGLE_STAIRS -> {
							if (scanMobSpots.get() && !waypoints.containsKey("Key Guardian Spiral") && Quarter.JUNGLE.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.KEY_GUARDIAN_SPIRAL.blocks)) {
								addWaypoint(Structure.KEY_GUARDIAN_SPIRAL, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.NETHERRACK -> {
							if (scanMobSpots.get() && !waypoints.containsKey("Pete") && Quarter.GOBLIN.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.PETE.blocks)) {
								addWaypoint(Structure.PETE, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.JUNGLE_LOG -> {
							if (scanMobSpots.get() && !waypoints.containsKey("Odawa") && Quarter.JUNGLE.test(worldX, y, worldZ) && checkSequence(chunk, x, y, z, Structure.ODAWA.blocks)) {
								addWaypoint(Structure.ODAWA, mutable.set(worldX, y, worldZ))
							}
						}

						Blocks.STAINED_GLASS.magenta(), Blocks.STAINED_GLASS_PANE.magenta() -> {
							if (scanFairyGrottos.get() && !waypoints.containsKey("Fairy Grotto") && !Quarter.NUCLEUS.test(worldX, y, worldZ)) {
								addWaypointDirect("Fairy Grotto", mutable.set(worldX, y, worldZ), LIGHT_PURPLE, 2)
							}
						}
					}
				}
			}
		}
	}

	private fun addWaypoint(structure: Structure, pos: BlockPos) {
		val target = pos.offset(structure.offset)
		if (waypoints.putIfAbsent(structure.displayName, WaypointData(target.immutable(), structure.category, structure.color)) == null) {
			if (sendCoordsInChat.get()) ChatUtils.sendMessage("${structure.displayName} found at ${target.x}, ${target.y}, ${target.z}", true)
		}
	}

	private fun addWaypointDirect(name: String, pos: BlockPos, color: Int, category: Int) {
		if (waypoints.putIfAbsent(name, WaypointData(pos.immutable(), category, color)) == null) {
			if (sendCoordsInChat.get()) ChatUtils.sendMessage("$name found at ${pos.x}, ${pos.y}, ${pos.z}", true)
		}
	}

	private fun checkSequence(chunk: LevelChunk, x: Int, y: Int, z: Int, sequence: List<Block?>): Boolean {
		for (i in sequence.indices) {
			val expected = sequence[i] ?: continue
			val py = y + i
			if (py > 255) return false
			if (blockState(chunk, x, py, z).block != expected) return false
		}
		return true
	}

	private fun blockState(chunk: LevelChunk, x: Int, y: Int, z: Int) =
		if (y < 0 || y > 255) Blocks.AIR.defaultBlockState()
		else run {
			val sections = chunk.sections
			val index = y shr 4
			if (index < 0 || index >= sections.size) Blocks.AIR.defaultBlockState()
			else {
				val section = sections[index]
				if (section.hasOnlyAir()) Blocks.AIR.defaultBlockState()
				else section.getBlockState(x, y and 15, z)
			}
		}

	private fun chunkKey(chunk: LevelChunk): Long = chunk.pos.pack()

	override fun onEnable() {
		enabled = true
	}

	override fun onDisable() {
		enabled = false
		clearWaypoints()
	}
}
