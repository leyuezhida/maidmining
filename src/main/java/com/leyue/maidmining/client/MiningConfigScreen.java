package com.leyue.maidmining.client;

import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.cfg.MiningConfigSpec;
import com.leyue.maidmining.mining.MiningValidator;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组配置界面（1.1.0）。
 * <p>
 * <b>为什么自己写</b>：Forge 1.20.1 没有内置配置界面，只提供
 * {@code ConfigScreenHandler} 扩展点；而引入 Cloth Config 之类的库会给玩家
 * 增加一个强制依赖。这里直接遍历 {@link MiningConfigSpec} 的
 * {@code ModConfigSpec}，按类型生成控件，不依赖任何第三方库。
 * <p>
 * <b>控件映射</b>：
 * <ul>
 *   <li>boolean → 开关按钮；</li>
 *   <li>enum → 循环按钮（如 {@code targets.mode}）；</li>
 *   <li>int / double（带范围）→ 滑块；</li>
 *   <li>{@code List<String>} → 按钮进入编辑页，逗号分隔（工具/目标白名单）；</li>
 *   <li>其他 → 只读显示。</li>
 * </ul>
 * <b>保存时机</b>：改动立刻写入内存配置并让运行时缓存失效（行为马上变），
 * 点「完成」时才落盘（{@code SPEC.save()}），避免拖滑块时每帧写文件。
 * <p>
 * <b>线程/侧别</b>：纯客户端类。服务端不会加载它（见
 * {@link ConfigScreenRegistration}）。
 */
public class MiningConfigScreen extends Screen {

    /** 右侧控件的固定宽度。 */
    private static final int WIDGET_WIDTH = 150;

    private final Screen parent;
    private ConfigList list;
    private boolean dirty;

    public MiningConfigScreen(Screen parent) {
        super(Component.translatable("maidmining.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.list = new ConfigList(this.minecraft, this.width, this.height,
                30, this.height - 32, 24, this::markDirty);
        this.addRenderableWidget(this.list);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20)
                .build());
    }

    private void markDirty() {
        this.dirty = true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.dirty) {
            // 落盘 + 让"字符串 → 集合"的缓存重建（配置重载事件也会做，这里保证立即生效）
            MiningConfigSpec.SPEC.save();
            MiningConfig.invalidateCaches();
            MiningValidator.invalidateCaches();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    /** 配置界面不该暂停游戏：它只是个编辑面板。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 列表 ====================

    private static class ConfigList extends ContainerObjectSelectionList<Row> {

        ConfigList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight,
                   Runnable markDirty) {
            // 1.20.5+：AbstractSelectionList 不再接收 bottom，第 3 个参数是列表自身高度
            super(minecraft, width, bottom - top, top, itemHeight);
            build(markDirty);
        }

        /**
         * 行宽必须铺满界面。
         * <p>
         * {@code AbstractSelectionList} 默认行宽只有 220px，右侧还要留 150px 给控件，
         * 剩下的标签区会窄到看不清——这是照着原版 {@code OptionsList} 覆写这两个方法的。
         */
        @Override
        public int getRowWidth() {
            return this.width - 24;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 6;
        }

        /**
         * 界面上不展示的项：都是开发者/管理员级的调试开关，普通玩家用不到，
         * 摆出来只会让人误点（1.1.0 实测反馈）。配置项本身仍保留在 toml 里。
         */
        private static final Set<String> HIDDEN = Set.of(
                "diag.logLevel",
                "compat.fakePlayerMode");

        /**
         * 遍历 spec 的取值树，按 section 分组生成行。
         * <p>
         * {@code getValues()} 的叶子是 {@code ConfigValue}（可读写），
         * {@code getSpec()} 的叶子是 {@code ValueSpec}（范围、默认值等元信息），
         * 两棵树的路径一一对应。先收集再决定是否加分组标题，避免某个分组被全部隐藏后
         * 只剩一个孤零零的标题。
         */
        private void build(Runnable markDirty) {
            UnmodifiableConfig values = MiningConfigSpec.SPEC.getValues();
            UnmodifiableConfig specs = MiningConfigSpec.SPEC.getSpec();
            for (var sectionEntry : values.valueMap().entrySet()) {
                String section = sectionEntry.getKey();
                if (!(sectionEntry.getValue() instanceof UnmodifiableConfig sectionValues)) {
                    continue;
                }
                UnmodifiableConfig sectionSpecs = specs.valueMap().get(section) instanceof UnmodifiableConfig c
                        ? c : null;

                List<Row> rows = new ArrayList<>();
                for (var entry : sectionValues.valueMap().entrySet()) {
                    List<String> path = List.of(section, entry.getKey());
                    if (HIDDEN.contains(String.join(".", path))) {
                        continue;
                    }
                    if (!(entry.getValue() instanceof ModConfigSpec.ConfigValue<?> configValue)) {
                        continue;
                    }
                    Object specNode = sectionSpecs == null ? null : sectionSpecs.valueMap().get(entry.getKey());
                    ModConfigSpec.ValueSpec valueSpec =
                            specNode instanceof ModConfigSpec.ValueSpec vs ? vs : null;
                    rows.add(new Row(path, configValue, valueSpec, markDirty));
                }
                if (rows.isEmpty()) {
                    continue;
                }
                addEntry(Row.header(sectionLabel(List.of(section))));
                rows.forEach(this::addEntry);
            }
        }
    }

    // ==================== 行 ====================

    private static final class Row extends ContainerObjectSelectionList.Entry<Row> {

        private final Component label;
        private final AbstractWidget widget;
        private final boolean header;
        /** 按钮类控件的文案要跟着当前值走（"开/关"、"3 项"），故保留一个取值函数。 */
        private final Supplier<Component> labelSupplier;
        private int lastTop;
        private int lastLeft;
        private int lastWidth;

        private Row(List<String> path, ModConfigSpec.ConfigValue<?> value,
                    ModConfigSpec.ValueSpec spec, Runnable markDirty) {
            this.label = valueLabel(path);
            Built built = buildWidget(path, value, spec, markDirty);
            this.widget = built.widget();
            this.labelSupplier = built.label();
            this.header = false;
        }

        private Row(Component label) {
            this.label = label;
            this.widget = null;
            this.header = true;
            this.labelSupplier = null;
        }

        static Row header(Component label) {
            return new Row(label);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            this.lastTop = top;
            this.lastLeft = left;
            this.lastWidth = width;
            Minecraft minecraft = Minecraft.getInstance();
            if (this.header) {
                graphics.drawString(minecraft.font, this.label, left + 4, top + 7, 0xFFC85C, false);
                return;
            }
            graphics.drawString(minecraft.font, this.label, left + 4, top + 7, 0xFFFFFF, false);
            if (this.widget != null) {
                // 每帧按当前值刷新文案：从子页返回、或值被别处改过时都不会显示过期内容
                if (this.labelSupplier != null && this.widget instanceof Button button) {
                    button.setMessage(this.labelSupplier.get());
                }
                positionWidget();
                this.widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (this.widget == null) {
                return false;
            }
            // 控件位置是在 render 里算的；点击前一定渲染过，但这里再摆一次更稳
            positionWidget();
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private void positionWidget() {
            this.widget.setX(this.lastLeft + this.lastWidth - WIDGET_WIDTH - 4);
            this.widget.setY(this.lastTop + 2);
            this.widget.setWidth(WIDGET_WIDTH);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widget == null ? List.of() : List.of(this.widget);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widget == null ? List.of() : List.of(this.widget);
        }
    }

    // ==================== 控件生成 ====================

    /** 控件 + 文案取值函数（开关/枚举/名单需要每帧按当前值刷新）。 */
    private record Built(AbstractWidget widget, Supplier<Component> label) {
    }

    @SuppressWarnings("unchecked")
    private static Built buildWidget(List<String> path, ModConfigSpec.ConfigValue<?> value,
                                     ModConfigSpec.ValueSpec spec, Runnable markDirty) {
        Object current;
        try {
            current = value.get();
        } catch (IllegalStateException notLoadedYet) {
            return new Built(null, null); // 配置还没加载：只显示项名，不提供编辑
        }

        if (value instanceof ModConfigSpec.BooleanValue booleanValue && current instanceof Boolean) {
            // 不用 CycleButton：它在列表条目里的行为不稳（1.1.0 实测点击即崩），
            // 这里用普通按钮自己切换，文案与状态完全可控。
            Button button = Button.builder(Component.empty(), b -> {
                apply(booleanValue, !booleanValue.get(), markDirty);
            }).bounds(0, 0, WIDGET_WIDTH, 20).build();
            return new Built(button, () -> boolLabel(booleanValue));
        }

        if (value instanceof ModConfigSpec.EnumValue<?> && current instanceof Enum<?>) {
            Button button = Button.builder(Component.empty(), b -> cycleEnum(value, markDirty))
                    .bounds(0, 0, WIDGET_WIDTH, 20).build();
            return new Built(button, () -> enumLabel(value));
        }

        if (value instanceof ModConfigSpec.IntValue intValue && current instanceof Integer intNow) {
            double[] range = rangeOf(spec, intNow - 64, intNow + 64);
            return new Built(new NumberSlider(0, 0, WIDGET_WIDTH, 20, range[0], range[1], intNow, true,
                    v -> apply(intValue, (int) Math.round(v), markDirty)), null);
        }

        if (value instanceof ModConfigSpec.DoubleValue doubleValue && current instanceof Double doubleNow) {
            double[] range = rangeOf(spec, doubleNow / 2.0D, doubleNow * 2.0D);
            return new Built(new NumberSlider(0, 0, WIDGET_WIDTH, 20, range[0], range[1], doubleNow, false,
                    v -> apply(doubleValue, v, markDirty)), null);
        }

        if (current instanceof List<?> listNow) {
            boolean blocks = isBlockList(path);
            Button button = Button.builder(listSummary(listNow), b -> Minecraft.getInstance().setScreen(
                            new ConfigListValueScreen(Minecraft.getInstance().screen,
                                    (ModConfigSpec.ConfigValue<List<? extends Object>>) value,
                                    blocks, markDirty)))
                    .bounds(0, 0, WIDGET_WIDTH, 20)
                    .build();
            return new Built(button, () -> listLabel(value));
        }

        return new Built(null, null); // 未知类型：只读展示项名
    }

    /** 开关按钮的文案（"开/关"）。 */
    private static Component boolLabel(ModConfigSpec.ConfigValue<?> value) {
        boolean on;
        try {
            on = Boolean.TRUE.equals(value.get());
        } catch (IllegalStateException notLoadedYet) {
            on = false;
        }
        return Component.translatable(on ? "options.on" : "options.off");
    }

    /** 枚举按钮的文案（直接显示枚举名，如 WARN / MAID）。 */
    private static Component enumLabel(ModConfigSpec.ConfigValue<?> value) {
        Object current;
        try {
            current = value.get();
        } catch (IllegalStateException notLoadedYet) {
            return Component.empty();
        }
        return current instanceof Enum<?> e ? Component.literal(e.name()) : Component.empty();
    }

    /** 名单按钮的文案（"3 项" / "（空）"）。 */
    private static Component listLabel(ModConfigSpec.ConfigValue<?> value) {
        Object current;
        try {
            current = value.get();
        } catch (IllegalStateException notLoadedYet) {
            return Component.empty();
        }
        return current instanceof List<?> list ? listSummary(list) : Component.empty();
    }

    /**
     * 切到下一个枚举值。
     * <p>
     * 泛型在运行时被擦除，这里用原始类型 + 显式枚举数组；改完立刻写回配置并让缓存失效。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cycleEnum(ModConfigSpec.ConfigValue value, Runnable markDirty) {
        Object current = value.get();
        if (!(current instanceof Enum now)) {
            return;
        }
        Enum[] constants = (Enum[]) now.getDeclaringClass().getEnumConstants();
        if (constants == null || constants.length == 0) {
            return;
        }
        int index = 0;
        for (int i = 0; i < constants.length; i++) {
            if (constants[i] == now) {
                index = i;
                break;
            }
        }
        applyRaw(value, constants[(index + 1) % constants.length], markDirty);
    }

    /** 把新值写进配置，并立刻让运行时缓存失效（不必等关界面）。 */
    private static <T> void apply(ModConfigSpec.ConfigValue<T> value, T newValue, Runnable markDirty) {
        value.set(newValue);
        MiningConfig.invalidateCaches();
        MiningValidator.invalidateCaches();
        markDirty.run();
    }

    /** 泛型擦除后的写入（enum 用）：与 {@link #apply} 语义相同。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyRaw(ModConfigSpec.ConfigValue value, Object newValue, Runnable markDirty) {
        value.set(newValue);
        MiningConfig.invalidateCaches();
        MiningValidator.invalidateCaches();
        markDirty.run();
    }

    /** 取滑块范围；没有范围时用传入的兜底值。 */
    private static double[] rangeOf(ModConfigSpec.ValueSpec spec, double fallbackMin, double fallbackMax) {
        if (spec != null) {
            ModConfigSpec.Range<?> range = spec.getRange();
            if (range != null
                    && range.getMin() instanceof Number min
                    && range.getMax() instanceof Number max
                    && max.doubleValue() > min.doubleValue()) {
                return new double[]{min.doubleValue(), max.doubleValue()};
            }
        }
        return new double[]{fallbackMin, fallbackMax};
    }

    /**
     * 该名单读哪个注册表：{@code targets.*} 与 {@code items.scaffold*} 是方块，
     * {@code tools.*} 是物品。选择页据此决定列 BLOCKS 还是 ITEMS。
     */
    private static boolean isBlockList(List<String> path) {
        String section = path.isEmpty() ? "" : path.get(0);
        if ("targets".equals(section)) {
            return true;
        }
        return "items".equals(section) && path.size() > 1 && path.get(1).startsWith("scaffold");
    }

    /** 列表值的摘要文案：空 = 「（空）」，否则显示「N 项」。 */
    private static Component listSummary(List<?> list) {
        return list.isEmpty()
                ? Component.translatable("maidmining.config.list.empty")
                : Component.translatable("maidmining.config.list.count", list.size());
    }

    /** 项名：优先用语言文件里的 {@code maidmining.config.<path>}，没有就显示原始路径。 */
    private static Component valueLabel(List<String> path) {
        String key = "maidmining.config." + String.join(".", path);
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(String.join(".", path));
    }

    /** 分组标题：{@code maidmining.config.section.<name>}，缺翻译时显示原名。 */
    private static Component sectionLabel(List<String> path) {
        String name = path.isEmpty() ? "" : path.get(path.size() - 1);
        String key = "maidmining.config.section." + name;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(name);
    }

    // ==================== 数值滑块 ====================

    /** 带范围的数值滑块；整数模式下按整数取整显示。 */
    private static final class NumberSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final boolean integerMode;
        private final Consumer<Double> onChange;

        NumberSlider(int x, int y, int width, int height, double min, double max, double initial,
                     boolean integerMode, Consumer<Double> onChange) {
            super(x, y, width, height, Component.empty(),
                    max > min ? Mth.clamp((initial - min) / (max - min), 0.0D, 1.0D) : 0.0D);
            this.min = min;
            this.max = max;
            this.integerMode = integerMode;
            this.onChange = onChange;
            this.updateMessage();
        }

        private double actualValue() {
            return this.min + (this.max - this.min) * this.value;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.integerMode
                    ? String.valueOf((int) Math.round(actualValue()))
                    : String.format(Locale.ROOT, "%.2f", actualValue())));
        }

        @Override
        protected void applyValue() {
            this.onChange.accept(this.actualValue());
        }
    }
}
