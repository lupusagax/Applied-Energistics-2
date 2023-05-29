package appeng.client.guidebook.document.interaction;

import java.util.List;

import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;

import appeng.client.guidebook.document.LytRect;
import appeng.client.guidebook.document.block.LytBlock;
import appeng.client.guidebook.layout.LayoutContext;
import appeng.client.guidebook.layout.MinecraftFontMetrics;
import appeng.client.guidebook.render.SimpleRenderContext;

public class ContentTooltip implements GuideTooltip {
    private final List<ClientTooltipComponent> components;

    public ContentTooltip(LytBlock content) {
        var window = Minecraft.getInstance().getWindow();
        var viewport = new LytRect(
                0, 0,
                window.getGuiScaledWidth(),
                window.getGuiScaledHeight());
        var layoutContext = new LayoutContext(new MinecraftFontMetrics(), viewport);
        var layoutBox = content.layout(layoutContext, 0, 0, window.getGuiScaledWidth() / 2);

        this.components = List.of(
                new ClientTooltipComponent() {
                    @Override
                    public int getHeight() {
                        return layoutBox.height();
                    }

                    @Override
                    public int getWidth(Font font) {
                        return layoutBox.width();
                    }

                    @Override
                    public void renderText(Font font, int x, int y, Matrix4f matrix,
                            MultiBufferSource.BufferSource bufferSource) {
                        var guiGraphics = new GuiGraphics(Minecraft.getInstance(), bufferSource);
                        var poseStack = guiGraphics.pose();
                        poseStack.mulPoseMatrix(matrix);
                        poseStack.translate(x, y, 0);

                        var ctx = new SimpleRenderContext(viewport, guiGraphics);
                        content.renderBatch(ctx, bufferSource);
                    }

                    @Override
                    public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
                        var pose = guiGraphics.pose();
                        pose.pushPose();
                        pose.translate(x, y, 0);
                        var ctx = new SimpleRenderContext(viewport, guiGraphics);
                        content.render(ctx);
                        pose.popPose();
                    }
                });
    }

    @Override
    public List<ClientTooltipComponent> getLines() {
        return components;
    }
}
