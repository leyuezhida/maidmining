package com.leyue.maidmining.client;

import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.mining.MiningValidator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 可视化的名单编辑页（1.1.0）。
 * <p>
 * <b>为什么重做</b>：最初版本只有一个文本框，让玩家手抄 {@code minecraft:iron_shovel}
 * 这种注册名——能存但不好用。现在改成：每一条都是「图标 + 名称 + 注册名」，
 * 点右侧 {@code ×} 移除，点「从列表选择…」进物品/方块选择页，从注册表里直接挑。
 * <p>
 * <b>仍保留手动输入</b>：{@code #minecraft:pickaxes}、{@code mekanism:*} 这类标签/通配
 * 无法用"挑一个物品"表达，所以在底部留了一个输入框（回车添加）。
 * <p>
 * <b>改动只在点「完成」时写回</b>：取消即丢弃，避免点错一下就写进配置文件。
 */
public class ConfigListValueScreen extends Screen {

    private final Screen parent;
    private final ModConfigSpec.ConfigValue<List<? extends Object>> value;
    /** true = 方块名单（targets / 垫脚建材），false = 物品名单（工具）。 */
    private final boolean blocks;
    private final Runnable markDirty;

    /** 工作副本；点「完成」才写回配置。 */
    private final List<String> working = new ArrayList<>();
    /** 是否成功读到原值：读不到就不允许写回，避免把名单清空。 */
    private final boolean loaded;

    private EntryList list;
    private EditBox manualInput;

    ConfigListValueScreen(Screen parent,
                          ModConfigSpec.ConfigValue<List<? extends Object>> value,
                          boolean blocks,
                          Runnable markDirty) {
        super(Component.translatable(blocks
                ? "maidmining.config.list.title.blocks"
                : "maidmining.config.list.title.items"));
        this.parent = parent;
        this.value = value;
        this.blocks = blocks;
        this.markDirty = markDirty;
        boolean readOk = true;
        try {
            for (Object entry : value.get()) {
                if (entry != null) {
                    this.working.add(String.valueOf(entry));
                }
            }
        } catch (IllegalStateException notLoadedYet) {
            // 配置没加载时不能从"空名单"开始编辑——那样点「完成」会把原名单覆盖成空的
            readOk = false;
        }
        this.loaded = readOk;
    }

    @Override
    protected void init() {
        this.list = new EntryList(this.minecraft, this.width, this.height, 30, this.height - 56, 24);
        this.addRenderableWidget(this.list);

        this.manualInput = new EditBox(this.font, this.width / 2 - 150, this.height - 50, 300, 20,
                Component.translatable("maidmining.config.list.manual"));
        this.manualInput.setMaxLength(256);
        this.manualInput.setHint(Component.translatable("maidmining.config.list.manual_hint"));
        this.addRenderableWidget(this.manualInput);

        int buttonY = this.height - 26;
        this.addRenderableWidget(Button.builder(Component.translatable("maidmining.config.list.pick"),
                        b -> this.openPicker())
                .bounds(this.width / 2 - 150, buttonY, 120, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.saveAndClose())
                .bounds(this.width / 2 - 22, buttonY, 80, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(this.width / 2 + 66, buttonY, 80, 20).build());
    }

    private void openPicker() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ConfigItemPickerScreen(this, this.blocks, this.working,
                    () -> this.list.rebuild()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.addManualEntry();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void addManualEntry() {
        String text = this.manualInput.getValue().trim().replace(" ", "");
        if (text.isEmpty()) {
            return;
        }
        addEntry(text);
        this.manualInput.setValue("");
    }

    /** 去重后加入工作副本并刷新列表。 */
    void addEntry(String entry) {
        if (entry.isEmpty() || this.working.contains(entry)) {
            return;
        }
        this.working.add(entry);
        this.list.rebuild();
    }

    void removeEntry(String entry) {
        this.working.remove(entry);
        this.list.rebuild();
    }

    private void saveAndClose() {
        if (!this.loaded) {
            onClose();
            return;
        }
        this.value.set(new ArrayList<>(this.working));
        // 立刻让"字符串 → 集合"的缓存失效：关掉界面就能按新名单干活
        MiningConfig.invalidateCaches();
        MiningValidator.invalidateCaches();
        this.markDirty.run();
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 已选列表 ====================

    private final class EntryList extends ContainerObjectSelectionList<EntryList.Row> {

        EntryList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            // 1.20.5+：AbstractSelectionList 不再接收 bottom，第 3 个参数是列表自身高度
            super(minecraft, width, bottom - top, top, itemHeight);
            rebuild();
        }

        void rebuild() {
            this.clearEntries();
            for (String entry : new ArrayList<>(working)) {
                addEntry(new Row(entry));
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 24;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 6;
        }

        final class Row extends ContainerObjectSelectionList.Entry<Row> {

            private final String entry;
            private final ItemStack icon;
            private final Component name;
            private final Button removeButton;
            private int lastTop;
            private int lastLeft;
            private int lastWidth;

            Row(String entry) {
                this.entry = entry;
                this.icon = ConfigEntryIcons.iconFor(entry, blocks);
                this.name = this.icon.isEmpty()
                        ? Component.literal(entry)
                        : this.icon.getHoverName();
                this.removeButton = Button.builder(Component.literal("×"),
                        b -> ConfigListValueScreen.this.removeEntry(entry))
                        .bounds(0, 0, 20, 20).build();
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                this.lastTop = top;
                this.lastLeft = left;
                this.lastWidth = width;
                Minecraft minecraft = Minecraft.getInstance();

                if (!this.icon.isEmpty()) {
                    graphics.renderItem(this.icon, left + 4, top + 4);
                }
                graphics.drawString(minecraft.font, this.name, left + 26, top + 4, 0xFFFFFF, false);
                // 第二行显示注册名，便于核对到底选了哪个（本地化名称可能重名）
                graphics.drawString(minecraft.font, this.entry, left + 26, top + 13, 0x808080, false);

                this.removeButton.setX(left + width - 22);
                this.removeButton.setY(top + 2);
                this.removeButton.render(graphics, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                this.removeButton.setX(this.lastLeft + this.lastWidth - 22);
                this.removeButton.setY(this.lastTop + 2);
                return super.mouseClicked(mouseX, mouseY, button);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(this.removeButton);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(this.removeButton);
            }
        }
    }

    // ==================== 图标解析 ====================

    /** 把配置条目解析成图标：{@code #标签} 取第一个成员，未知 id 用屏障图标。 */
    static final class ConfigEntryIcons {

        private ConfigEntryIcons() {
        }

        static ItemStack iconFor(String entry, boolean blocks) {
            if (entry.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(entry.substring(1));
                if (tagId == null) {
                    return new ItemStack(Items.BARRIER);
                }
                // 1.21：原版 Registry 用 getTag(TagKey) → Optional<HolderSet.Named>，
                // 遍历得到的是 Holder，需要再取 value()。
                if (blocks) {
                    TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
                    return BuiltInRegistries.BLOCK.getTag(tag)
                            .flatMap(set -> set.stream().findFirst())
                            .map(holder -> new ItemStack(holder.value().asItem()))
                            .filter(stack -> !stack.isEmpty())
                            .orElse(new ItemStack(Items.NAME_TAG));
                }
                TagKey<net.minecraft.world.item.Item> tag = TagKey.create(Registries.ITEM, tagId);
                return BuiltInRegistries.ITEM.getTag(tag)
                        .flatMap(set -> set.stream().findFirst())
                        .map(holder -> new ItemStack(holder.value()))
                        .orElse(new ItemStack(Items.NAME_TAG));
            }

            ResourceLocation id = ResourceLocation.tryParse(entry);
            if (id == null) {
                return new ItemStack(Items.BARRIER);
            }
            // 原版 Registry#get 对缺失 id 会回退到默认值（空气），所以必须先 containsKey
            if (blocks) {
                return BuiltInRegistries.BLOCK.containsKey(id)
                        ? new ItemStack(BuiltInRegistries.BLOCK.get(id))
                        : new ItemStack(Items.BARRIER);
            }
            return BuiltInRegistries.ITEM.containsKey(id)
                    ? new ItemStack(BuiltInRegistries.ITEM.get(id))
                    : new ItemStack(Items.BARRIER);
        }
    }
}
