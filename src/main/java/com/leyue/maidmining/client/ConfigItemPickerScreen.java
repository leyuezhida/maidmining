package com.leyue.maidmining.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 物品/方块选择页（1.1.0）：从注册表里直接挑，不用手抄 id。
 * <p>
 * <b>候选来源</b>：整个 {@code ITEMS} 或 {@code BLOCKS} 注册表，外加该注册表的全部标签。
 * 标签排在最前面（名称以 {@code #} 开头），这样 {@code #minecraft:pickaxes} 这类
 * "一次选一类"的写法也能点出来，而不必回去手打。
 * <p>
 * <b>选择语义</b>：点一行就切换选中状态（已在名单里 → 移除；不在 → 加入）。
 * 名称两行显示：本地化名 + 注册名，避免"橡木台阶"这种重名选错。
 */
public class ConfigItemPickerScreen extends Screen {

    private final Screen parent;
    private final boolean blocks;
    /** 与名单编辑页共享的工作副本：改动即时可见，最终由编辑页的「完成」写回配置。 */
    private final List<String> working;
    private final Runnable onChanged;

    private final List<Candidate> all = new ArrayList<>();
    private CandidateList list;
    private EditBox search;
    private String query = "";
    /** 当前匹配条数（只用于界面显示，顺便让玩家确认"确实在过滤"）。 */
    private int shown;

    ConfigItemPickerScreen(Screen parent, boolean blocks, List<String> working, Runnable onChanged) {
        super(Component.translatable(blocks
                ? "maidmining.config.picker.title.blocks"
                : "maidmining.config.picker.title.items"));
        this.parent = parent;
        this.blocks = blocks;
        this.working = working;
        this.onChanged = onChanged;
    }

    @Override
    protected void init() {
        buildCandidates();

        // 先建列表、后建搜索框：后加入的控件渲染在上层，避免搜索框被列表的背景盖住
        this.list = new CandidateList(this.minecraft, this.width, this.height, 60, this.height - 28, 24);
        this.addRenderableWidget(this.list);

        // 搜索框铺满宽度，别再让玩家找不到它
        this.search = new EditBox(this.font, 20, 30, this.width - 40, 20,
                Component.translatable("maidmining.config.picker.search"));
        this.search.setMaxLength(64);
        this.search.setHint(Component.translatable("maidmining.config.picker.search"));
        this.search.setResponder(text -> {
            this.query = text.trim().toLowerCase(Locale.ROOT);
            this.list.rebuild();
            // 过滤后回到顶部，否则会停在旧滚动位置看起来像"没搜到"
            this.list.setScrollAmount(0.0D);
        });
        this.addRenderableWidget(this.search);
        // 焦点要在控件加入 children 之后再设，否则某些版本上会被后续 addWidget 清掉
        this.setFocused(this.search);
        this.setInitialFocus(this.search);

        this.addRenderableWidget(Button.builder(Component.translatable("maidmining.config.picker.clear"),
                        b -> clearAll())
                .bounds(this.width / 2 - 100, this.height - 24, 90, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("maidmining.config.picker.back"),
                        b -> onClose())
                .bounds(this.width / 2 + 10, this.height - 24, 90, 20).build());
    }

    /** 一次性把注册表读进候选表；之后搜索只在内存里过滤。 */
    private void buildCandidates() {
        if (this.blocks) {
            for (Block block : ForgeRegistries.BLOCKS) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                if (id == null) {
                    continue;
                }
                ItemStack icon = new ItemStack(block.asItem());
                if (icon.isEmpty()) {
                    continue; // 没有对应物品的方块（水、火…）没法在界面里表示
                }
                this.all.add(new Candidate(id.toString(), icon, block.getName().getString()));
            }
            ForgeRegistries.BLOCKS.tags().getTagNames().forEach(tag -> {
                TagKey<Block> key = TagKey.create(Registries.BLOCK, tag.location());
                Block first = null;
                int size = 0;
                for (Block block : ForgeRegistries.BLOCKS.tags().getTag(key)) {
                    if (first == null) {
                        first = block;
                    }
                    size++;
                }
                ItemStack icon = first == null ? new ItemStack(Items.NAME_TAG) : new ItemStack(first.asItem());
                if (icon.isEmpty()) {
                    icon = new ItemStack(Items.NAME_TAG);
                }
                this.all.add(new Candidate("#" + tag.location(), icon,
                        "#" + tag.location() + "  (" + size + ")"));
            });
        } else {
            for (Item item : ForgeRegistries.ITEMS) {
                if (item == Items.AIR) {
                    continue;
                }
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id == null) {
                    continue;
                }
                ItemStack icon = new ItemStack(item);
                this.all.add(new Candidate(id.toString(), icon, icon.getHoverName().getString()));
            }
            ForgeRegistries.ITEMS.tags().getTagNames().forEach(tag -> {
                TagKey<Item> key = TagKey.create(Registries.ITEM, tag.location());
                Item first = null;
                int size = 0;
                for (Item item : ForgeRegistries.ITEMS.tags().getTag(key)) {
                    if (first == null) {
                        first = item;
                    }
                    size++;
                }
                ItemStack icon = first == null ? new ItemStack(Items.NAME_TAG) : new ItemStack(first);
                this.all.add(new Candidate("#" + tag.location(), icon,
                        "#" + tag.location() + "  (" + size + ")"));
            });
        }
        // 标签在前（# 开头），其余按本地化名排序，便于浏览
        this.all.sort(Comparator
                .comparing((Candidate c) -> !c.id().startsWith("#"))
                .thenComparing(Candidate::name, String::compareToIgnoreCase));
    }

    private List<Candidate> filtered() {
        if (this.query.isEmpty()) {
            return this.all;
        }
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : this.all) {
            if (candidate.matches(this.query)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private void toggle(String id) {
        if (!this.working.remove(id)) {
            this.working.add(id);
        }
        this.onChanged.run();
    }

    private void clearAll() {
        this.working.clear();
        this.onChanged.run();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        // 搜索区底衬：输入框自带的深色底纹在菜单背景上几乎看不见，加一层让它显形
        graphics.fill(0, 26, this.width, 54, 0x66000000);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        Component count = Component.translatable("maidmining.config.picker.match",
                this.shown, this.all.size());
        graphics.drawString(this.font, count, this.width - 8 - this.font.width(count), 10,
                0xA0A0A0, false);
        graphics.drawString(this.font,
                Component.translatable("maidmining.config.picker.selected", this.working.size()),
                this.width / 2 - 150, this.height - 19, 0xA0A0A0, false);
    }

    /**
     * 保证键盘输入一定进到搜索框。
     * <p>
     * <b>为什么需要这段</b>：列表是可聚焦控件，点一下列表行之后焦点可能离开输入框，
     * 这时打字会被列表吞掉、看起来就是"搜索栏没反应"。这里把任何字符输入都路由回搜索框——
     * 这个界面只有这一个文本输入，不存在抢焦点的歧义。
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.search.isFocused()) {
            this.setFocused(this.search);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= this.search.getX() && mouseX < this.search.getX() + this.search.getWidth()
                && mouseY >= this.search.getY() && mouseY < this.search.getY() + this.search.getHeight()) {
            this.setFocused(this.search);
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

    /** 一个可选项：id 是写进配置的字符串（方块/物品/标签）。 */
    private record Candidate(String id, ItemStack icon, String name) {

        boolean matches(String lowerQuery) {
            String lowerId = this.id.toLowerCase(Locale.ROOT);
            return lowerId.contains(lowerQuery)
                    || this.name.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    // 允许用空格代替下划线搜 id，例如 "nether brick"
                    || lowerId.replace('_', ' ').contains(lowerQuery);
        }
    }

    // ==================== 候选列表 ====================

    private final class CandidateList extends ContainerObjectSelectionList<CandidateList.Row> {

        CandidateList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
            rebuild();
        }

        void rebuild() {
            this.clearEntries();
            List<Candidate> matches = filtered();
            shown = matches.size();
            for (Candidate candidate : matches) {
                addEntry(new Row(candidate));
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

            private final Candidate candidate;
            private int lastTop;
            private int lastLeft;
            private int lastWidth;
            private int lastHeight;

            Row(Candidate candidate) {
                this.candidate = candidate;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                this.lastTop = top;
                this.lastLeft = left;
                this.lastWidth = width;
                this.lastHeight = height;
                Minecraft minecraft = Minecraft.getInstance();
                boolean selected = working.contains(this.candidate.id());

                if (selected || hovered) {
                    graphics.fill(left - 2, top, left + width, top + height, selected ? 0x553C8C3C : 0x33FFFFFF);
                }
                graphics.renderItem(this.candidate.icon(), left + 4, top + 4);
                graphics.drawString(minecraft.font, this.candidate.name(), left + 26, top + 4,
                        selected ? 0x9CFF9C : 0xFFFFFF, false);
                graphics.drawString(minecraft.font, this.candidate.id(), left + 26, top + 13,
                        0x808080, false);
                if (selected) {
                    graphics.drawString(minecraft.font, "✓", left + width - 14, top + 7, 0x9CFF9C, false);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0
                        && mouseX >= this.lastLeft && mouseX < this.lastLeft + this.lastWidth
                        && mouseY >= this.lastTop && mouseY < this.lastTop + this.lastHeight) {
                    toggle(this.candidate.id());
                    return true;
                }
                return false;
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of();
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }
        }
    }
}
