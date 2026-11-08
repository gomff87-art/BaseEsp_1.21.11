package com.example.storageesp;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public class StorageEspClient implements ClientModInitializer {
    private static final String MOD_ID = "storageesp";
    private static final int RADIUS_CHUNKS = 5;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final float ALPHA = 0.4f;

    // Пайплайн без проверки глубины = рисуется поверх стен
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/esp_boxes"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private record Target(BlockPos pos, float r, float g, float b) {}

    private static boolean enabled = false;
    private static List<Target> targets = List.of();
    private static MappableRingBuffer vertexBuffer;
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        KeyMapping toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + MOD_ID + ".toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                tickCounter = SCAN_INTERVAL_TICKS; // сразу пересканировать
                if (client.player != null) {
                    client.player.displayClientMessage(Component.literal(
                            "[StorageESP] " + (enabled ? "§aВключен" : "§cВыключен")), false);
                }
            }
            if (!enabled || client.level == null || client.player == null) {
                targets = List.of();
                return;
            }
            if (++tickCounter >= SCAN_INTERVAL_TICKS) {
                tickCounter = 0;
                targets = scan(client);
            }
        });

        WorldRenderEvents.BEFORE_TRANSLUCENT.register(StorageEspClient::render);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ALLOCATOR.close();
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
        });
    }

    /** Ищет контейнеры в загруженных чанках вокруг игрока. */
    private static List<Target> scan(Minecraft mc) {
        List<Target> found = new ArrayList<>();
        BlockPos p = mc.player.blockPosition();
        int cx = p.getX() >> 4;
        int cz = p.getZ() >> 4;

        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                LevelChunk chunk = mc.level.getChunk(cx + dx, cz + dz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    float[] c = colorOf(be);
                    if (c != null) found.add(new Target(be.getBlockPos(), c[0], c[1], c[2]));
                }
            }
        }
        return found;
    }

    private static float[] colorOf(BlockEntity be) {
        if (be instanceof ChestBlockEntity)      return new float[]{1.0f, 0.6f, 0.0f}; // оранжевый
        if (be instanceof ShulkerBoxBlockEntity) return new float[]{0.8f, 0.3f, 1.0f}; // фиолетовый
        if (be instanceof BarrelBlockEntity)     return new float[]{0.6f, 0.4f, 0.2f}; // коричневый
        if (be instanceof SpawnerBlockEntity)    return new float[]{1.0f, 0.1f, 0.1f}; // красный
        return null;
    }

    private static void render(WorldRenderContext context) {
        if (!enabled || targets.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();

        PoseStack matrices = context.matrices();
        Vec3 cam = context.worldState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        BufferBuilder buffer = new BufferBuilder(ALLOCATOR, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());
        for (Target t : targets) {
            BlockPos p = t.pos();
            addBox(matrices.last().pose(), buffer,
                    p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                    t.r(), t.g(), t.b(), ALPHA);
        }
        matrices.popPose();

        draw(mc, buffer);
    }

    private static void draw(Minecraft mc, BufferBuilder buffer) {
        MeshData mesh = buffer.buildOrThrow();
        MeshData.DrawState state = mesh.drawState();
        VertexFormat format = state.format();

        // 1. Загрузка вершин в GPU-буфер
        int size = state.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < size) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, mesh.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(mesh.vertexBuffer(), view.data());
        }
        GpuBuffer vertices = vertexBuffer.currentBuffer();

        // 2. Индексы (для QUADS сортируем из-за прозрачности)
        mesh.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
        GpuBuffer indices = PIPELINE.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());

        // 3. Отрисовка
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> MOD_ID + " render",
                mc.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                mc.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, state.indexType());
            pass.drawIndexed(0, 0, state.indexCount(), 1);
        }

        mesh.close();
        vertexBuffer.rotate();
    }

    /** Закрашенный куб из 6 граней. */
    private static void addBox(Matrix4fc m, BufferBuilder b,
                               float x1, float y1, float z1, float x2, float y2, float z2,
                               float r, float g, float bl, float a) {
        // перед
        b.addVertex(m, x1, y1, z2).setColor(r, g, bl, a); b.addVertex(m, x2, y1, z2).setColor(r, g, bl, a);
        b.addVertex(m, x2, y2, z2).setColor(r, g, bl, a); b.addVertex(m, x1, y2, z2).setColor(r, g, bl, a);
        // зад
        b.addVertex(m, x2, y1, z1).setColor(r, g, bl, a); b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y2, z1).setColor(r, g, bl, a); b.addVertex(m, x2, y2, z1).setColor(r, g, bl, a);
        // лево
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a); b.addVertex(m, x1, y1, z2).setColor(r, g, bl, a);
        b.addVertex(m, x1, y2, z2).setColor(r, g, bl, a); b.addVertex(m, x1, y2, z1).setColor(r, g, bl, a);
        // право
        b.addVertex(m, x2, y1, z2).setColor(r, g, bl, a); b.addVertex(m, x2, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x2, y2, z1).setColor(r, g, bl, a); b.addVertex(m, x2, y2, z2).setColor(r, g, bl, a);
        // верх
        b.addVertex(m, x1, y2, z2).setColor(r, g, bl, a); b.addVertex(m, x2, y2, z2).setColor(r, g, bl, a);
        b.addVertex(m, x2, y2, z1).setColor(r, g, bl, a); b.addVertex(m, x1, y2, z1).setColor(r, g, bl, a);
        // низ
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a); b.addVertex(m, x2, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x2, y1, z2).setColor(r, g, bl, a); b.addVertex(m, x1, y1, z2).setColor(r, g, bl, a);
    }
}
